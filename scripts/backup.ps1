<#
.SYNOPSIS
    Backs up every application database with pg_dump (custom format) and the question media bucket
    into .\backups\<yyyyMMdd-HHmm>\.

.DESCRIPTION
    Runs pg_dump inside the Postgres container, copies the dumps out, writes SHA256SUMS.txt and keeps
    only the newest backups. Exits non-zero on any failure; an incomplete backup is never left behind
    under a final name.

    Password: taken from the DB_PASSWORD environment variable or from DB_PASSWORD in .env when present,
    and handed to the container through the environment (never on a command line, never printed).
    Without one, the container's local connection rules apply (the stock image trusts them).

    Media: every object of the media bucket is copied through the S3 API (aws-cli container, attached
    to the storage container's network) into the "media" subfolder, with one checksum per object in
    the same SHA256SUMS.txt and the same retention. The credentials come from MEDIA_S3_ACCESS_KEY /
    MEDIA_S3_SECRET_KEY (environment or .env) and reach the helper container through the environment.
    Media is data: a storage container that is not running fails the backup, unless -SkipMedia is given.

.EXAMPLE
    .\scripts\backup.ps1
    .\scripts\backup.ps1 -Container exam-postgres-primary
#>
[CmdletBinding()]
param(
    [string]$Container = 'exam-postgres',
    [string]$DbUser = 'postgres',
    [string[]]$Databases = @('exam_auth', 'exam_db', 'exam_monitoring'),
    [string]$BackupRoot = '',   # default: <repository>\backups
    [int]$Keep = 14,
    [string]$MediaContainer = 'exam-media-storage',
    [string]$MediaBucket = 'exam-media',
    [string]$AwsCliImage = 'amazon/aws-cli:2.37.12',
    [switch]$SkipMedia
)

$ErrorActionPreference = 'Stop'
if (-not $BackupRoot) { $BackupRoot = Join-Path (Split-Path $PSScriptRoot -Parent) 'backups' }

# A setting from the environment, else from .env; never printed
function Get-Setting {
    param([string]$Name)
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ($value) { return $value }
    $envFile = Join-Path (Split-Path $PSScriptRoot -Parent) '.env'
    if (Test-Path $envFile) {
        foreach ($line in Get-Content $envFile) {
            if ($line -match "^\s*$Name\s*=\s*(.+?)\s*$") { return $Matches[1] }
        }
    }
    return $null
}

function Get-DbPassword { return Get-Setting 'DB_PASSWORD' }

# Runs aws-cli against the media storage (S3 API); throws when it fails. Output is returned.
function Invoke-Storage {
    param([string[]]$Arguments, [string]$What, [string]$MountDir)
    $dockerArgs = @('run', '--rm', '--network', "container:$MediaContainer",
        '-e', 'AWS_ACCESS_KEY_ID', '-e', 'AWS_SECRET_ACCESS_KEY', '-e', 'AWS_DEFAULT_REGION=us-east-1')
    if ($MountDir) { $dockerArgs += @('-v', "${MountDir}:/backup") }
    $dockerArgs += @($AwsCliImage, '--endpoint-url', 'http://127.0.0.1:8333') + $Arguments
    $out = & docker @dockerArgs
    if ($LASTEXITCODE -ne 0) { throw "$What failed (exit code $LASTEXITCODE)" }
    return $out
}

# Runs a command in the container; throws when it fails. Output of the command is returned.
function Invoke-InContainer {
    param([string[]]$Command, [string]$What)
    $out = & docker exec -e PGPASSWORD $Container @Command
    if ($LASTEXITCODE -ne 0) { throw "$What failed (exit code $LASTEXITCODE)" }
    return $out
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmm'
$final = Join-Path $BackupRoot $stamp
$partial = "$final.partial"
$remoteDir = "/tmp/backup-$stamp-$PID"
$exitCode = 0
$previousPgPassword = $env:PGPASSWORD
$previousAwsKey = $env:AWS_ACCESS_KEY_ID
$previousAwsSecret = $env:AWS_SECRET_ACCESS_KEY

try {
    if ($Keep -lt 1) { throw "-Keep must be at least 1" }
    if (Test-Path $final) { throw "Backup $final already exists (one backup per minute); nothing was done" }

    $password = Get-DbPassword
    if ($password) { $env:PGPASSWORD = $password }

    $running = & docker inspect -f '{{.State.Running}}' $Container
    if ($LASTEXITCODE -ne 0 -or $running -ne 'true') { throw "Container '$Container' is not running" }

    New-Item -ItemType Directory -Force $BackupRoot | Out-Null
    if (Test-Path $partial) { Remove-Item -Recurse -Force $partial }
    New-Item -ItemType Directory $partial | Out-Null
    Invoke-InContainer @('mkdir', '-p', $remoteDir) 'Creating the temporary directory' | Out-Null

    $sums = @()
    foreach ($db in $Databases) {
        $file = "$db.dump"
        Invoke-InContainer @('pg_dump', '-U', $DbUser, '-Fc', '-f', "$remoteDir/$file", $db) "pg_dump of $db" | Out-Null
        & docker cp "${Container}:$remoteDir/$file" (Join-Path $partial $file) | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Copying $file out of the container failed" }

        $local = Get-Item (Join-Path $partial $file)
        if ($local.Length -le 0) { throw "$file is empty" }
        $hash = (Get-FileHash $local.FullName -Algorithm SHA256).Hash.ToLower()
        $sums += "$hash  $file"
        Write-Host ("  {0,-18} {1,10:N0} bytes  sha256 {2}..." -f $db, $local.Length, $hash.Substring(0, 12))
    }

    if (-not $SkipMedia) {
        $running = & docker inspect -f '{{.State.Running}}' $MediaContainer
        if ($LASTEXITCODE -ne 0 -or $running -ne 'true') { throw "Container '$MediaContainer' is not running (media is not backed up; -SkipMedia skips it on purpose)" }
        $env:AWS_ACCESS_KEY_ID = Get-Setting 'MEDIA_S3_ACCESS_KEY'
        $env:AWS_SECRET_ACCESS_KEY = Get-Setting 'MEDIA_S3_SECRET_KEY'
        if (-not $env:AWS_ACCESS_KEY_ID -or -not $env:AWS_SECRET_ACCESS_KEY) { throw 'MEDIA_S3_ACCESS_KEY / MEDIA_S3_SECRET_KEY are not set (environment or .env)' }

        $mediaDir = Join-Path $partial 'media'
        New-Item -ItemType Directory $mediaDir | Out-Null
        $liveCount = 0
        # A bucket that was never created (no upload yet) is an empty backup, not an error
        $buckets = @((Invoke-Storage @('s3api', 'list-buckets', '--query', 'Buckets[].Name', '--output', 'text') 'Listing buckets') -split '\s+')
        if ($buckets -contains $MediaBucket) {
            Invoke-Storage @('s3', 'sync', "s3://$MediaBucket", '/backup', '--only-show-errors') "Copying bucket $MediaBucket" $mediaDir | Out-Null
            $liveCount = @(Invoke-Storage @('s3', 'ls', "s3://$MediaBucket", '--recursive') "Listing bucket $MediaBucket" | Where-Object { $_ }).Count
        }

        $mediaFiles = @(Get-ChildItem $mediaDir -Recurse -File)
        if ($mediaFiles.Count -ne $liveCount) { throw "Media backup has $($mediaFiles.Count) objects, the bucket has $liveCount" }
        $mediaBytes = 0
        foreach ($file in $mediaFiles) {
            $relative = $file.FullName.Substring($mediaDir.Length + 1).Replace('\', '/')
            $sums += "$((Get-FileHash $file.FullName -Algorithm SHA256).Hash.ToLower())  media/$relative"
            $mediaBytes += $file.Length
        }
        Write-Host ("  {0,-18} {1,10:N0} bytes  {2} objects" -f "media ($MediaBucket)", $mediaBytes, $mediaFiles.Count)
    }

    [IO.File]::WriteAllLines((Join-Path $partial 'SHA256SUMS.txt'), $sums)
    Rename-Item $partial $stamp
    Write-Host "Backup written to $final"

    # Retention: only folders this script names (yyyyMMdd-HHmm) are ever considered
    $all = @(Get-ChildItem $BackupRoot -Directory | Where-Object { $_.Name -match '^\d{8}-\d{4}$' } | Sort-Object Name -Descending)
    if ($all.Count -gt $Keep) {
        foreach ($old in $all[$Keep..($all.Count - 1)]) {
            Remove-Item -Recurse -Force $old.FullName
            Write-Host "Removed old backup $($old.Name)"
        }
    }
}
catch {
    Write-Host "BACKUP FAILED: $($_.Exception.Message)" -ForegroundColor Red
    if (Test-Path $partial) { Remove-Item -Recurse -Force $partial }
    $exitCode = 1
}
finally {
    & docker exec $Container rm -rf $remoteDir | Out-Null
    $env:PGPASSWORD = $previousPgPassword
    $env:AWS_ACCESS_KEY_ID = $previousAwsKey
    $env:AWS_SECRET_ACCESS_KEY = $previousAwsSecret
}

exit $exitCode
