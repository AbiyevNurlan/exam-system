<#
.SYNOPSIS
    Backs up every application database with pg_dump (custom format) into .\backups\<yyyyMMdd-HHmm>\.

.DESCRIPTION
    Runs pg_dump inside the Postgres container, copies the dumps out, writes SHA256SUMS.txt and keeps
    only the newest backups. Exits non-zero on any failure; an incomplete backup is never left behind
    under a final name.

    Password: taken from the DB_PASSWORD environment variable or from DB_PASSWORD in .env when present,
    and handed to the container through the environment (never on a command line, never printed).
    Without one, the container's local connection rules apply (the stock image trusts them).

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
    [int]$Keep = 14
)

$ErrorActionPreference = 'Stop'
if (-not $BackupRoot) { $BackupRoot = Join-Path (Split-Path $PSScriptRoot -Parent) 'backups' }

function Get-DbPassword {
    if ($env:DB_PASSWORD) { return $env:DB_PASSWORD }
    $envFile = Join-Path (Split-Path $PSScriptRoot -Parent) '.env'
    if (Test-Path $envFile) {
        foreach ($line in Get-Content $envFile) {
            if ($line -match '^\s*DB_PASSWORD\s*=\s*(.+?)\s*$') { return $Matches[1] }
        }
    }
    return $null
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
}

exit $exitCode
