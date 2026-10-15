<#
.SYNOPSIS
    Prepares .env for the temporary public mode: generates every missing or weak secret and puts the
    database password into effect. Never prints a value.

.DESCRIPTION
    Checks first that .env is ignored by git and not tracked; stops otherwise.

    Generated when missing, empty, CHANGE_ME or too weak (an existing good value is never changed):
      JWT_SECRET, OTP_HMAC_SECRET   base64 of 32 random bytes (different values)
      INTERNAL_SERVICE_SECRET       64 hex characters
      DB_PASSWORD                   48 hex characters (weak: shorter than 24, or "postgres")
      REDIS_PASSWORD                48 hex characters
      MEDIA_S3_ACCESS_KEY / _SECRET 20 / 40 letters and digits (only when missing: they are the
                                    storage's identity, an existing pair is kept)

    DB_PASSWORD is the password of the database role "postgres". When the role does not accept the
    value of .env yet, it is set with ALTER ROLE inside the database container (local socket; the
    value goes through standard input, never onto a command line). The running services keep their
    open connections; restart them with scripts\go-public.ps1 start (or scripts\up.ps1 for
    development), both of which read DB_PASSWORD from .env.

    Never generated, only reported:
      ADMIN_EMAIL / ADMIN_PASSWORD  the first admin is created from them only when no ADMIN exists.
                                    An existing admin account and its password are never touched.
      PUBLIC_MAIL_*                 the SMTP server of the public mode (.env.example, Gmail example)

    Writes .env through a temporary file in the same folder and replaces it in one step.

.EXAMPLE
    .\scripts\init-public-env.ps1
    .\scripts\init-public-env.ps1 -WhatIf
#>
[CmdletBinding(SupportsShouldProcess = $true)]
param(
    [string]$EnvFile = '',
    [string]$PostgresContainer = 'exam-postgres'
)

# Native commands (docker, git, curl) are judged by their exit codes; in Windows PowerShell their
# stderr would otherwise stop the script. Cmdlets that must not fail use -ErrorAction Stop.
$ErrorActionPreference = 'Continue'
$repoRoot = Split-Path $PSScriptRoot -Parent
if (-not $EnvFile) { $EnvFile = Join-Path $repoRoot '.env' }

# ---------- .env must stay out of git ----------
if (-not (Test-Path -LiteralPath $EnvFile)) {
    Write-Host "$EnvFile does not exist. Copy .env.example to .env first."
    exit 1
}
$relative = [IO.Path]::GetFileName($EnvFile)
& git -C $repoRoot check-ignore -q -- $relative
if ($LASTEXITCODE -ne 0) { Write-Host "$relative is NOT ignored by git (.gitignore). Nothing was changed."; exit 1 }
$tracked = & git -C $repoRoot ls-files -- $relative
if ($tracked) { Write-Host "$relative is tracked by git. Remove it from the index (git rm --cached) first. Nothing was changed."; exit 1 }
Write-Host "$relative is ignored by git and not tracked."

# ---------- read ----------
$lines = New-Object System.Collections.Generic.List[string]
foreach ($line in [IO.File]::ReadAllLines($EnvFile)) { $lines.Add($line) }

function Get-Value([string]$name) {
    $value = $null
    foreach ($line in $lines) {
        if ($line -match "^\s*(?:export\s+)?$name\s*=(.*)$") {
            $value = $Matches[1].Trim()
            if ($value.Length -ge 2 -and (($value[0] -eq '"' -and $value[-1] -eq '"') -or ($value[0] -eq "'" -and $value[-1] -eq "'"))) {
                $value = $value.Substring(1, $value.Length - 2)
            } else {
                # As docker compose reads it: " #..." after an unquoted value is a comment
                $value = $value -replace '\s+#.*$', ''
            }
        }
    }
    return $value
}

function Set-Value([string]$name, [string]$value) {
    $index = -1
    for ($i = 0; $i -lt $lines.Count; $i++) {
        if ($lines[$i] -match "^\s*(?:export\s+)?$name\s*=") { $index = $i }
    }
    if ($index -ge 0) { $lines[$index] = "$name=$value" } else { $script:appended.Add("$name=$value") }
}

function New-Bytes([int]$count) {
    $bytes = New-Object byte[] $count
    $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    return $bytes
}
function New-Base64Key { return [Convert]::ToBase64String((New-Bytes 32)) }
function New-Hex([int]$chars) { return (-join ((New-Bytes ($chars / 2)) | ForEach-Object { $_.ToString('x2') })) }
function New-Alnum([int]$chars) {
    $alphabet = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz'
    # 248 = 4 * 62: bytes above it are dropped so every character is equally likely
    $result = New-Object Text.StringBuilder
    while ($result.Length -lt $chars) {
        foreach ($b in (New-Bytes 64)) { if ($b -lt 248 -and $result.Length -lt $chars) { [void]$result.Append($alphabet[$b % 62]) } }
    }
    return $result.ToString()
}

function Test-Missing([string]$value) { return [string]::IsNullOrWhiteSpace($value) -or $value -eq 'CHANGE_ME' }
function Test-Base64Key([string]$value) {
    try { return ([Convert]::FromBase64String($value)).Length -ge 32 } catch { return $false }
}

$script:appended = New-Object System.Collections.Generic.List[string]
$report = New-Object System.Collections.Generic.List[object]
function Note([string]$name, [string]$state) { $report.Add([pscustomobject]@{ Key = $name; State = $state }) }

# ---------- secrets ----------
$jwt = Get-Value 'JWT_SECRET'
if ((Test-Missing $jwt) -or -not (Test-Base64Key $jwt)) { $jwt = New-Base64Key; Set-Value 'JWT_SECRET' $jwt; Note 'JWT_SECRET' 'generated (signed-in users must sign in again)' } else { Note 'JWT_SECRET' 'kept' }

$otp = Get-Value 'OTP_HMAC_SECRET'
if ((Test-Missing $otp) -or -not (Test-Base64Key $otp) -or $otp -ceq $jwt) {
    do { $otp = New-Base64Key } while ($otp -ceq $jwt)
    Set-Value 'OTP_HMAC_SECRET' $otp; Note 'OTP_HMAC_SECRET' 'generated (codes already sent no longer match)'
} else { Note 'OTP_HMAC_SECRET' 'kept' }

$internal = Get-Value 'INTERNAL_SERVICE_SECRET'
if ((Test-Missing $internal) -or $internal.Length -lt 32) { Set-Value 'INTERNAL_SERVICE_SECRET' (New-Hex 64); Note 'INTERNAL_SERVICE_SECRET' 'generated' } else { Note 'INTERNAL_SERVICE_SECRET' 'kept' }

$dbPassword = Get-Value 'DB_PASSWORD'
$dbGenerated = $false
if ((Test-Missing $dbPassword) -or $dbPassword.Length -lt 24 -or $dbPassword -eq 'postgres') {
    $dbPassword = New-Hex 48; Set-Value 'DB_PASSWORD' $dbPassword; $dbGenerated = $true; Note 'DB_PASSWORD' 'generated'
} else { Note 'DB_PASSWORD' 'kept' }

$redis = Get-Value 'REDIS_PASSWORD'
if ((Test-Missing $redis) -or $redis.Length -lt 24) { Set-Value 'REDIS_PASSWORD' (New-Hex 48); Note 'REDIS_PASSWORD' 'generated' } else { Note 'REDIS_PASSWORD' 'kept' }

if (Test-Missing (Get-Value 'MEDIA_S3_ACCESS_KEY')) { Set-Value 'MEDIA_S3_ACCESS_KEY' (New-Alnum 20); Note 'MEDIA_S3_ACCESS_KEY' 'generated' } else { Note 'MEDIA_S3_ACCESS_KEY' 'kept' }
if (Test-Missing (Get-Value 'MEDIA_S3_SECRET_KEY')) { Set-Value 'MEDIA_S3_SECRET_KEY' (New-Alnum 40); Note 'MEDIA_S3_SECRET_KEY' 'generated' } else { Note 'MEDIA_S3_SECRET_KEY' 'kept' }

# ---------- write .env ----------
$changed = @($report | Where-Object { $_.State -like 'generated*' }).Count -gt 0
if ($changed) {
    if ($script:appended.Count -gt 0) {
        if ($lines.Count -gt 0 -and $lines[$lines.Count - 1].Trim() -ne '') { $lines.Add('') }
        $lines.Add("# Added by scripts\init-public-env.ps1 on $((Get-Date).ToString('yyyy-MM-dd'))")
        foreach ($line in $script:appended) { $lines.Add($line) }
    }
    if ($PSCmdlet.ShouldProcess($EnvFile, 'write the generated secrets')) {
        $temp = "$EnvFile.$PID.tmp"
        try {
            [IO.File]::WriteAllLines($temp, $lines, (New-Object Text.UTF8Encoding $false))
            Move-Item -LiteralPath $temp -Destination $EnvFile -Force -ErrorAction Stop
        } catch {
            Remove-Item -LiteralPath $temp -Force -ErrorAction SilentlyContinue
            Write-Host "Writing $relative failed; it was not changed."
            exit 1
        }
        Write-Host "$relative updated."
    } else {
        $dbGenerated = $false
    }
} else {
    Write-Host "$relative already holds every secret; nothing was written."
}

# ---------- database role password ----------
# Accepted already? Checked the way the services connect: over the network from another container
# (inside the database container 127.0.0.1 is trusted without a password, so a login there proves
# nothing). The password reaches psql through the environment only.
function Test-DbPassword([string]$password) {
    $network = (& docker inspect -f '{{range $name, $n := .NetworkSettings.Networks}}{{$name}} {{end}}' $PostgresContainer).Trim().Split(' ')[0]
    $image = (& docker inspect -f '{{.Config.Image}}' $PostgresContainer)
    $previous = $env:PGPASSWORD
    try {
        $env:PGPASSWORD = $password
        & docker run --rm --network $network -e PGPASSWORD $image psql -h postgres -U postgres -d postgres -tAc 'SELECT 1' *> $null
        return $LASTEXITCODE -eq 0
    } finally { $env:PGPASSWORD = $previous }
}

$dbState = 'not checked (database container not running)'
$running = (& docker inspect -f '{{.State.Running}}' $PostgresContainer 2>$null) -eq 'true'
if ($running -and -not $WhatIfPreference) {
    if (Test-DbPassword $dbPassword) {
        $dbState = 'the database accepts DB_PASSWORD'
    } else {
        # Local socket (trusted by the image) and the statement on standard input
        "ALTER ROLE postgres WITH PASSWORD '$dbPassword';" | & docker exec -i $PostgresContainer psql -q -U postgres -d postgres -v ON_ERROR_STOP=1 *> $null
        if ($LASTEXITCODE -ne 0) { Write-Host 'ALTER ROLE failed: the database password was NOT changed.'; exit 1 }
        if (-not (Test-DbPassword $dbPassword)) { Write-Host 'The database does not accept the new password after ALTER ROLE.'; exit 1 }
        $dbState = 'role password set to DB_PASSWORD (restart the services: go-public.ps1 start or up.ps1)'
    }
} elseif ($dbGenerated) {
    $dbState = 'NOT applied: start Postgres and run this script again before starting any service'
}
Note 'database role postgres' $dbState

# ---------- reported only ----------
$adminEmail = Get-Value 'ADMIN_EMAIL'; $adminPassword = Get-Value 'ADMIN_PASSWORD'
if ((Test-Missing $adminEmail) -or (Test-Missing $adminPassword)) {
    $admins = $null
    if ($running) { $admins = (& docker exec $PostgresContainer psql -U postgres -d exam_auth -tAc "SELECT count(*) FROM users WHERE role = 'ADMIN'" 2>$null) }
    if ("$admins".Trim() -match '^[1-9]') { Note 'ADMIN_EMAIL / ADMIN_PASSWORD' 'not set; an ADMIN exists, nothing needed' }
    else { Note 'ADMIN_EMAIL / ADMIN_PASSWORD' 'REQUIRED: no ADMIN exists yet; set both by hand (password: 12+ characters)' }
} else {
    Note 'ADMIN_EMAIL / ADMIN_PASSWORD' 'set (used only when no ADMIN exists; an existing admin is never changed)'
}
$mailMissing = @('PUBLIC_MAIL_HOST', 'PUBLIC_MAIL_USERNAME', 'PUBLIC_MAIL_PASSWORD', 'PUBLIC_MAIL_FROM') | Where-Object { Test-Missing (Get-Value $_) }
if ($mailMissing) { Note 'PUBLIC_MAIL_*' "REQUIRED: missing $($mailMissing -join ', ') (.env.example, Gmail App Password)" }
else { Note 'PUBLIC_MAIL_*' 'set' }

$report | Format-Table -AutoSize | Out-String -Width 200 | Write-Host
if ($mailMissing) { exit 2 }
exit 0
