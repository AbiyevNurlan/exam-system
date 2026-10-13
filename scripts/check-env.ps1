<#
.SYNOPSIS
    Checks that .env holds every setting docker-compose.yml cannot start without.

.DESCRIPTION
    The required keys are read from the compose file itself: every ${NAME:?...} and ${NAME?...}
    reference. For each of them .env must have a line NAME=value with a value that is not empty and
    not the CHANGE_ME placeholder of .env.example. JWT_SECRET and OTP_HMAC_SECRET must also be valid
    base64 of at least 32 bytes, which is what the services themselves demand at startup.

    Only key NAMES are printed, never a value or its length. Exits 0 when everything is in place,
    1 otherwise.

    Only the .env file is checked: a variable set in the shell overrides .env for docker-compose and
    is not looked at here.

.EXAMPLE
    .\scripts\check-env.ps1
    .\scripts\check-env.ps1 -ComposeFile .\docker-compose.production.yml
#>
[CmdletBinding()]
param(
    [string]$EnvFile = '',       # default: <repository>\.env
    [string]$ComposeFile = ''    # default: <repository>\docker-compose.yml
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
if (-not $EnvFile) { $EnvFile = Join-Path $repoRoot '.env' }
if (-not $ComposeFile) { $ComposeFile = Join-Path $repoRoot 'docker-compose.yml' }

# Keys whose value must be base64 of at least this many bytes
$base64Keys = @('JWT_SECRET', 'OTP_HMAC_SECRET')
$minKeyBytes = 32

if (-not (Test-Path -LiteralPath $ComposeFile)) {
    Write-Host "Compose file not found: $ComposeFile"
    exit 1
}
if (-not (Test-Path -LiteralPath $EnvFile)) {
    Write-Host "Env file not found: $EnvFile"
    Write-Host 'Copy .env.example to .env and replace every CHANGE_ME value.'
    exit 1
}

# Required keys, in the order the compose file mentions them
$required = New-Object System.Collections.Generic.List[string]
foreach ($match in [regex]::Matches((Get-Content -LiteralPath $ComposeFile -Raw), '\$\{([A-Za-z_][A-Za-z0-9_]*):?\?')) {
    $name = $match.Groups[1].Value
    if (-not $required.Contains($name)) { $required.Add($name) }
}

# KEY=value lines of .env; the last one wins, like in docker-compose
$values = @{}
foreach ($line in Get-Content -LiteralPath $EnvFile) {
    if ($line -match '^\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=(.*)$') {
        $value = $Matches[2].Trim()
        if ($value.Length -ge 2 -and (($value[0] -eq '"' -and $value[-1] -eq '"') -or ($value[0] -eq "'" -and $value[-1] -eq "'"))) {
            $value = $value.Substring(1, $value.Length - 2)
        }
        $values[$Matches[1]] = $value
    }
}

$problems = New-Object System.Collections.Generic.List[string]
foreach ($name in $required) {
    if (-not $values.ContainsKey($name)) { $problems.Add("$name is missing"); continue }
    if ($values[$name] -eq '') { $problems.Add("$name is empty"); continue }
    if ($values[$name] -eq 'CHANGE_ME') { $problems.Add("$name still holds the CHANGE_ME placeholder"); continue }
    if ($base64Keys -contains $name) {
        $bytes = $null
        try { $bytes = [Convert]::FromBase64String($values[$name]) } catch { $bytes = $null }
        if ($null -eq $bytes) { $problems.Add("$name is not valid base64") }
        elseif ($bytes.Length -lt $minKeyBytes) { $problems.Add("$name is shorter than $minKeyBytes bytes") }
    }
}

$composeName = Split-Path $ComposeFile -Leaf
if ($problems.Count -gt 0) {
    Write-Host "$EnvFile is not ready for ${composeName}:"
    foreach ($problem in $problems) { Write-Host "  - $problem" }
    Write-Host 'See .env.example for what each key is and how to generate it.'
    exit 1
}

if ($values.ContainsKey('JWT_SECRET') -and $values.ContainsKey('OTP_HMAC_SECRET') -and
    $values['JWT_SECRET'] -ceq $values['OTP_HMAC_SECRET']) {
    Write-Host 'Warning: OTP_HMAC_SECRET has the same value as JWT_SECRET; they should differ.'
}

Write-Host "$EnvFile is ready for ${composeName}: $($required.Count) required keys are set ($($required -join ', '))."
exit 0
