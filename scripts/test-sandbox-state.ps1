<#
.SYNOPSIS
    Checks the sandbox refusal of go-public.ps1 with fake .env files in a temporary folder. Never touches the stack.

.DESCRIPTION
    1. Get-SandboxState (sandbox-state.ps1) for every setting that turns the sandbox on, for values that do not, for
       quotes and comments, and for a variable of the process that wins over .env.
    2. A copy of go-public.ps1 and sandbox-state.ps1 in a temporary folder with a fake .env: "start" with the sandbox on
       refuses (exit 1, "NOT STARTED: the payment sandbox ...") and never prints a value; with -AllowSandbox, or with the
       sandbox off, it passes the sandbox step and stops at the next preflight step (the temporary folder is no git
       repository), before any docker command.
    Exit code 0 when every check passes, 1 otherwise.

.EXAMPLE
    powershell -NoProfile -ExecutionPolicy Bypass -File scripts\test-sandbox-state.ps1
#>
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'sandbox-state.ps1')

$root = Join-Path ([IO.Path]::GetTempPath()) ("sandbox-state-test-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $root | Out-Null
$failed = 0

function Check([string]$what, [bool]$ok) {
    if ($ok) { Write-Host "PASS  $what" } else { Write-Host "FAIL  $what"; $script:failed++ }
}

function Write-Env([string]$path, [string[]]$lines) {
    Set-Content -LiteralPath $path -Encoding ascii -Value $lines
}

# Variables of this process would win over the fake files
$names = 'PAYMENTS_SANDBOX_ALLOWED', 'PAYMENTS_SANDBOX_ALLOWED_PUBLIC', 'PAYMENTS_SANDBOX_ALLOW_IN_PRODUCTION'
$saved = @{}
foreach ($name in $names) { $saved[$name] = [Environment]::GetEnvironmentVariable($name, 'Process'); [Environment]::SetEnvironmentVariable($name, $null, 'Process') }

try {
    $env1 = Join-Path $root 'state.env'
    $cases = @(
        @{ what = 'no sandbox setting at all'; lines = @('JWT_SECRET=x'); expect = @() },
        @{ what = 'everything false or empty'; lines = @('PAYMENTS_SANDBOX_ALLOWED=false', 'PAYMENTS_SANDBOX_ALLOWED_PUBLIC=false', 'PAYMENTS_SANDBOX_ALLOW_IN_PRODUCTION='); expect = @() },
        @{ what = 'a commented line does not count'; lines = @('# PAYMENTS_SANDBOX_ALLOWED=true'); expect = @() },
        @{ what = 'PAYMENTS_SANDBOX_ALLOWED=true'; lines = @('PAYMENTS_SANDBOX_ALLOWED=true'); expect = @('PAYMENTS_SANDBOX_ALLOWED') },
        @{ what = 'TRUE in quotes'; lines = @('PAYMENTS_SANDBOX_ALLOWED="TRUE"'); expect = @('PAYMENTS_SANDBOX_ALLOWED') },
        @{ what = 'unquoted true with a trailing comment'; lines = @('PAYMENTS_SANDBOX_ALLOWED=true # demo'); expect = @('PAYMENTS_SANDBOX_ALLOWED') },
        @{ what = 'the last line wins'; lines = @('PAYMENTS_SANDBOX_ALLOWED=true', 'PAYMENTS_SANDBOX_ALLOWED=false'); expect = @() },
        @{ what = 'PAYMENTS_SANDBOX_ALLOWED_PUBLIC=true'; lines = @('PAYMENTS_SANDBOX_ALLOWED_PUBLIC=true'); expect = @('PAYMENTS_SANDBOX_ALLOWED_PUBLIC') },
        @{ what = 'any override value'; lines = @('PAYMENTS_SANDBOX_ALLOW_IN_PRODUCTION=I-UNDERSTAND'); expect = @('PAYMENTS_SANDBOX_ALLOW_IN_PRODUCTION') },
        @{ what = 'all three'; lines = @('PAYMENTS_SANDBOX_ALLOWED=true', 'PAYMENTS_SANDBOX_ALLOWED_PUBLIC=true', 'PAYMENTS_SANDBOX_ALLOW_IN_PRODUCTION=yes'); expect = $names }
    )
    foreach ($case in $cases) {
        Write-Env $env1 $case.lines
        $got = @(Get-SandboxState $env1)
        Check "Get-SandboxState: $($case.what) -> [$($got -join ', ')]" ((@($got) -join ',') -eq (@($case.expect) -join ','))
    }
    Check 'Get-SandboxState: a missing .env is off' (@(Get-SandboxState (Join-Path $root 'missing.env')).Count -eq 0)

    Write-Env $env1 @('PAYMENTS_SANDBOX_ALLOWED=false')
    $env:PAYMENTS_SANDBOX_ALLOWED = 'true'
    Check 'Get-SandboxState: a process variable wins over .env (on)' ((@(Get-SandboxState $env1) -join ',') -eq 'PAYMENTS_SANDBOX_ALLOWED')
    Write-Env $env1 @('PAYMENTS_SANDBOX_ALLOWED=true')
    $env:PAYMENTS_SANDBOX_ALLOWED = 'false'
    Check 'Get-SandboxState: a process variable wins over .env (off)' (@(Get-SandboxState $env1).Count -eq 0)
    [Environment]::SetEnvironmentVariable('PAYMENTS_SANDBOX_ALLOWED', $null, 'Process')

    # go-public.ps1 start, from a copy whose repository root is the temporary folder
    $fake = Join-Path $root 'repo'
    New-Item -ItemType Directory -Path (Join-Path $fake 'scripts') | Out-Null
    Copy-Item (Join-Path $PSScriptRoot 'go-public.ps1'), (Join-Path $PSScriptRoot 'sandbox-state.ps1') (Join-Path $fake 'scripts')
    $goPublic = Join-Path $fake 'scripts\go-public.ps1'
    $secretValue = 'I-UNDERSTAND'

    function Start-Copy([string[]]$extra) {
        # git's "not a repository" on stderr is expected, not a failure of this test
        $ErrorActionPreference = 'Continue'
        $out =& powershell -NoProfile -ExecutionPolicy Bypass -File $goPublic start @extra 2>&1 | ForEach-Object { "$_" }
        return [pscustomobject]@{ code = $LASTEXITCODE; text = ($out -join "`n") }
    }

    Write-Env (Join-Path $fake '.env') @('PAYMENTS_SANDBOX_ALLOWED=true', "PAYMENTS_SANDBOX_ALLOW_IN_PRODUCTION=$secretValue")
    $run = Start-Copy @()
    Check 'start with the sandbox on: exit 1' ($run.code -eq 1)
    Check 'start with the sandbox on: refuses at the sandbox step' ($run.text -match 'NOT STARTED: the payment sandbox is on')
    Check 'start with the sandbox on: the red warning names the settings' ($run.text -match 'sandbox canlida aciq ola bilmez' -and $run.text -match 'PAYMENTS_SANDBOX_ALLOWED, PAYMENTS_SANDBOX_ALLOW_IN_PRODUCTION')
    Check 'start with the sandbox on: no value is printed' (-not ($run.text -match [regex]::Escape($secretValue)))
    Check 'start with the sandbox on: nothing after the sandbox step ran' (-not ($run.text -match 'not ignored by git|Frontend build|== Stack'))

    $run = Start-Copy @('-AllowSandbox')
    Check 'start -AllowSandbox: the warning stays' ($run.text -match 'sandbox canlida aciq ola bilmez' -and $run.text -match '-AllowSandbox given')
    Check 'start -AllowSandbox: goes on to the next preflight step' ($run.text -match 'NOT STARTED: \.env is not ignored by git')

    Write-Env (Join-Path $fake '.env') @('PAYMENTS_SANDBOX_ALLOWED=false')
    $run = Start-Copy @()
    Check 'start with the sandbox off: no warning' (-not ($run.text -match 'SANDBOX:'))
    Check 'start with the sandbox off: goes on to the next preflight step' ($run.text -match 'NOT STARTED: \.env is not ignored by git')
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $saved[$name], 'Process') }
    Remove-Item -LiteralPath $root -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host ''
if ($failed -gt 0) { Write-Host "$failed check(s) failed"; exit 1 }
Write-Host 'All checks passed'
exit 0
