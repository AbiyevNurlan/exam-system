<#
.SYNOPSIS
    Proves the latest backup can be restored: restores it into TEMPORARY databases and compares
    per-table row counts with the live databases.

.DESCRIPTION
    Live databases are only ever read (SELECT count(*)). Every statement that creates, restores into or
    drops a database goes through Assert-TemporaryDatabase, which refuses any name that is not a
    "restoretest_" database created by this run. Temporary databases are dropped at the end, also on failure.

    Media: the checksum of every backed-up object is verified, the objects are uploaded into a
    TEMPORARY bucket ("restoretest-...", removed at the end) and the object count is compared with the
    live media bucket, which is only listed. Buckets go through Assert-TemporaryBucket like databases.

    A table whose row count changed since the backup was taken shows as FAIL; take a fresh backup
    (.\scripts\backup.ps1) right before this test on a busy system.

    Exit code 0 = every table PASS, 1 = any FAIL or error.

.EXAMPLE
    .\scripts\restore-test.ps1
    .\scripts\restore-test.ps1 -Container exam-postgres-primary
#>
[CmdletBinding()]
param(
    [string]$Container = 'exam-postgres',
    [string]$DbUser = 'postgres',
    [string[]]$Databases = @('exam_auth', 'exam_db', 'exam_monitoring'),
    [string]$BackupRoot = '',   # default: <repository>\backups
    [string]$MediaContainer = 'exam-media-storage',
    [string]$MediaBucket = 'exam-media',
    [string]$AwsCliImage = 'amazon/aws-cli:2.37.12',
    [switch]$SkipMedia
)

$ErrorActionPreference = 'Stop'
if (-not $BackupRoot) { $BackupRoot = Join-Path (Split-Path $PSScriptRoot -Parent) 'backups' }
$TempPrefix = 'restoretest_'
$TempBucketPrefix = 'restoretest-'
$created = New-Object System.Collections.Generic.List[string]
$createdBuckets = New-Object System.Collections.Generic.List[string]

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
    if ($MountDir) { $dockerArgs += @('-v', "${MountDir}:/backup:ro") }
    $dockerArgs += @($AwsCliImage, '--endpoint-url', 'http://127.0.0.1:8333') + $Arguments
    $out = & docker @dockerArgs
    if ($LASTEXITCODE -ne 0) { throw "$What failed (exit code $LASTEXITCODE)" }
    return $out
}

# Same gate as Assert-TemporaryDatabase, for buckets: only a bucket this run created is ever written or removed
function Assert-TemporaryBucket {
    param([string]$Name, [switch]$MustBeOurs)
    if ($Name -notmatch "^$TempBucketPrefix[a-z0-9-]+$") { throw "REFUSED: '$Name' is not a temporary restore-test bucket" }
    if ($Name -eq $MediaBucket) { throw "REFUSED: '$Name' is the live media bucket" }
    if ($MustBeOurs -and -not $createdBuckets.Contains($Name)) { throw "REFUSED: bucket '$Name' was not created by this run" }
}

function Get-ObjectCount {
    param([string]$Bucket)
    return @(Invoke-Storage @('s3', 'ls', "s3://$Bucket", '--recursive') "Listing bucket $Bucket" | Where-Object { $_ }).Count
}

function Invoke-InContainer {
    param([string[]]$Command, [string]$What)
    $out = & docker exec -e PGPASSWORD $Container @Command
    if ($LASTEXITCODE -ne 0) { throw "$What failed (exit code $LASTEXITCODE)" }
    return $out
}

function Invoke-Sql {
    param([string]$Database, [string]$Sql)
    return @(Invoke-InContainer @('psql', '-U', $DbUser, '-d', $Database, '-v', 'ON_ERROR_STOP=1', '-At', '-c', $Sql) "Query on $Database")
}

# The only gate to anything destructive. A name passes only if it carries the temporary prefix,
# is not one of the live databases and (for restore/drop) was created by this run.
function Assert-TemporaryDatabase {
    param([string]$Name, [switch]$MustBeOurs)
    if ($Name -notmatch "^$TempPrefix[a-z0-9_]+$") { throw "REFUSED: '$Name' is not a temporary restore-test database" }
    if ($Databases -contains $Name) { throw "REFUSED: '$Name' is a live database" }
    if ($MustBeOurs -and -not $created.Contains($Name)) { throw "REFUSED: '$Name' was not created by this run" }
}

function Get-RowCounts {
    param([string]$Database)
    $counts = [ordered]@{}
    # Exact count of every table in one read-only statement; identifiers are quoted server-side (%I)
    $sql = "SELECT table_name || '|' || (xpath('/row/c/text()', query_to_xml(format('SELECT count(*) AS c FROM %I.%I', table_schema, table_name), false, true, '')))[1]::text " +
           "FROM information_schema.tables WHERE table_schema = 'public' AND table_type = 'BASE TABLE' ORDER BY table_name"
    foreach ($line in (Invoke-Sql $Database $sql)) {
        if (-not $line) { continue }
        $i = $line.LastIndexOf('|')
        $counts[$line.Substring(0, $i)] = [long]$line.Substring($i + 1)
    }
    return $counts
}

$exitCode = 0
$previousPgPassword = $env:PGPASSWORD
$previousAwsKey = $env:AWS_ACCESS_KEY_ID
$previousAwsSecret = $env:AWS_SECRET_ACCESS_KEY
$remoteDir = "/tmp/restore-test-$PID"
$results = @()

try {
    foreach ($db in $Databases) {
        if ($db -like "$TempPrefix*") { throw "REFUSED: live database list contains a temporary name ('$db')" }
    }

    $password = Get-DbPassword
    if ($password) { $env:PGPASSWORD = $password }

    $running = & docker inspect -f '{{.State.Running}}' $Container
    if ($LASTEXITCODE -ne 0 -or $running -ne 'true') { throw "Container '$Container' is not running" }

    $latest = Get-ChildItem $BackupRoot -Directory -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -match '^\d{8}-\d{4}$' } | Sort-Object Name -Descending | Select-Object -First 1
    if (-not $latest) { throw "No backup found in $BackupRoot (run .\scripts\backup.ps1 first)" }
    Write-Host "Testing backup $($latest.Name)"

    # Checksums first: a damaged dump must not be mistaken for a restore problem
    $sumFile = Join-Path $latest.FullName 'SHA256SUMS.txt'
    if (-not (Test-Path $sumFile)) { throw "SHA256SUMS.txt is missing in $($latest.FullName)" }
    $expected = @{}
    foreach ($line in Get-Content $sumFile) { if ($line -match '^([0-9a-f]{64})\s+(\S+)$') { $expected[$Matches[2]] = $Matches[1] } }
    $mediaDir = Join-Path $latest.FullName 'media'
    if (-not $SkipMedia) {
        if (-not (Test-Path $mediaDir)) { throw "The backup has no media folder (taken with -SkipMedia or by an older backup.ps1)" }
        $mediaFiles = @(Get-ChildItem $mediaDir -Recurse -File)
        foreach ($file in $mediaFiles) {
            $name = 'media/' + $file.FullName.Substring($mediaDir.Length + 1).Replace('\', '/')
            if (-not $expected.ContainsKey($name)) { throw "No checksum recorded for $name" }
            if ((Get-FileHash $file.FullName -Algorithm SHA256).Hash.ToLower() -ne $expected[$name]) { throw "Checksum mismatch for $name" }
        }
        $recorded = @($expected.Keys | Where-Object { $_ -like 'media/*' }).Count
        if ($recorded -ne $mediaFiles.Count) { throw "SHA256SUMS.txt lists $recorded media objects, the backup holds $($mediaFiles.Count)" }
    }
    foreach ($db in $Databases) {
        $dump = Join-Path $latest.FullName "$db.dump"
        if (-not (Test-Path $dump)) { throw "$db.dump is missing in the backup" }
        if (-not $expected.ContainsKey("$db.dump")) { throw "No checksum recorded for $db.dump" }
        if ((Get-FileHash $dump -Algorithm SHA256).Hash.ToLower() -ne $expected["$db.dump"]) { throw "Checksum mismatch for $db.dump" }
    }
    Write-Host "Checksums OK"

    Invoke-InContainer @('mkdir', '-p', $remoteDir) 'Creating the temporary directory' | Out-Null
    $suffix = "$(Get-Date -Format 'yyyyMMddHHmmss')_$PID"

    foreach ($db in $Databases) {
        $temp = "$TempPrefix${db}_$suffix"
        Assert-TemporaryDatabase $temp
        if ((Invoke-Sql 'postgres' "SELECT count(*) FROM pg_database WHERE datname = '$temp'")[0] -ne '0') {
            throw "REFUSED: database '$temp' already exists and was not created by this run"
        }

        Invoke-InContainer @('createdb', '-U', $DbUser, $temp) "Creating $temp" | Out-Null
        $created.Add($temp)

        & docker cp (Join-Path $latest.FullName "$db.dump") "${Container}:$remoteDir/$db.dump" | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Copying $db.dump into the container failed" }

        Assert-TemporaryDatabase $temp -MustBeOurs
        Invoke-InContainer @('pg_restore', '-U', $DbUser, '-d', $temp, '--no-owner', '--exit-on-error', "$remoteDir/$db.dump") "pg_restore into $temp" | Out-Null

        $live = Get-RowCounts $db
        $restored = Get-RowCounts $temp
        $tables = @($live.Keys) + @($restored.Keys | Where-Object { -not $live.Contains($_) })

        if ($tables.Count -eq 0) {
            $results += [pscustomobject]@{ Database = $db; Table = '(no tables)'; Live = 0; Restored = 0; Result = 'PASS' }
        }
        foreach ($table in $tables) {
            $l = if ($live.Contains($table)) { $live[$table] } else { $null }
            $r = if ($restored.Contains($table)) { $restored[$table] } else { $null }
            $ok = ($null -ne $l) -and ($null -ne $r) -and ($l -eq $r)
            $results += [pscustomobject]@{
                Database = $db; Table = $table
                Live = $(if ($null -ne $l) { $l } else { 'missing' })
                Restored = $(if ($null -ne $r) { $r } else { 'missing' })
                Result = $(if ($ok) { 'PASS' } else { 'FAIL' })
            }
        }
    }

    if (-not $SkipMedia) {
        $running = & docker inspect -f '{{.State.Running}}' $MediaContainer
        if ($LASTEXITCODE -ne 0 -or $running -ne 'true') { throw "Container '$MediaContainer' is not running" }
        $env:AWS_ACCESS_KEY_ID = Get-Setting 'MEDIA_S3_ACCESS_KEY'
        $env:AWS_SECRET_ACCESS_KEY = Get-Setting 'MEDIA_S3_SECRET_KEY'
        if (-not $env:AWS_ACCESS_KEY_ID -or -not $env:AWS_SECRET_ACCESS_KEY) { throw 'MEDIA_S3_ACCESS_KEY / MEDIA_S3_SECRET_KEY are not set (environment or .env)' }

        $buckets = @((Invoke-Storage @('s3api', 'list-buckets', '--query', 'Buckets[].Name', '--output', 'text') 'Listing buckets') -split '\s+')
        $liveObjects = if ($buckets -contains $MediaBucket) { Get-ObjectCount $MediaBucket } else { 0 }

        $tempBucket = "$TempBucketPrefix$(Get-Date -Format 'yyyyMMddHHmmss')-$PID"
        Assert-TemporaryBucket $tempBucket
        if ($buckets -contains $tempBucket) { throw "REFUSED: bucket '$tempBucket' already exists and was not created by this run" }
        Invoke-Storage @('s3', 'mb', "s3://$tempBucket") "Creating bucket $tempBucket" | Out-Null
        $createdBuckets.Add($tempBucket)

        Assert-TemporaryBucket $tempBucket -MustBeOurs
        Invoke-Storage @('s3', 'sync', '/backup', "s3://$tempBucket", '--only-show-errors') "Restoring media into $tempBucket" $mediaDir | Out-Null
        $restoredObjects = Get-ObjectCount $tempBucket

        $results += [pscustomobject]@{
            Database = 'media'; Table = "$MediaBucket (objects)"
            Live = $liveObjects; Restored = $restoredObjects
            Result = $(if ($liveObjects -eq $restoredObjects) { 'PASS' } else { 'FAIL' })
        }
    }
}
catch {
    Write-Host "RESTORE TEST ERROR: $($_.Exception.Message)" -ForegroundColor Red
    $exitCode = 1
}
finally {
    foreach ($temp in $created) {
        try {
            Assert-TemporaryDatabase $temp -MustBeOurs
            Invoke-InContainer @('dropdb', '-U', $DbUser, '--if-exists', $temp) "Dropping $temp" | Out-Null
        }
        catch {
            Write-Host "Could not drop temporary database ${temp}: $($_.Exception.Message)" -ForegroundColor Red
            $exitCode = 1
        }
    }
    foreach ($bucket in $createdBuckets) {
        try {
            Assert-TemporaryBucket $bucket -MustBeOurs
            Invoke-Storage @('s3', 'rb', "s3://$bucket", '--force') "Removing bucket $bucket" | Out-Null
        }
        catch {
            Write-Host "Could not remove temporary bucket ${bucket}: $($_.Exception.Message)" -ForegroundColor Red
            $exitCode = 1
        }
    }
    & docker exec $Container rm -rf $remoteDir | Out-Null
    $env:PGPASSWORD = $previousPgPassword
    $env:AWS_ACCESS_KEY_ID = $previousAwsKey
    $env:AWS_SECRET_ACCESS_KEY = $previousAwsSecret
}

if ($results.Count -gt 0) {
    $results | Format-Table Database, Table, Live, Restored, Result -AutoSize | Out-String | Write-Host
    $failed = @($results | Where-Object { $_.Result -ne 'PASS' }).Count
    if ($failed -gt 0) { $exitCode = 1 }
    if ($exitCode -eq 0) { Write-Host "RESULT: PASS ($($results.Count) rows)" -ForegroundColor Green }
    else { Write-Host "RESULT: FAIL ($failed of $($results.Count) rows differ, or an error occurred)" -ForegroundColor Red }
}

exit $exitCode
