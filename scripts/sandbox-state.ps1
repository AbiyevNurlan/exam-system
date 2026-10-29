<#
.SYNOPSIS
    Whether the payment sandbox could be on in the public mode: dot-sourced by go-public.ps1 (start refuses, status
    shows "Sandbox: ON" as FAIL) and by test-sandbox-state.ps1.

.DESCRIPTION
    Get-SandboxState <path of .env> returns the names of the settings that turn the sandbox on, empty when it is off:
      PAYMENTS_SANDBOX_ALLOWED=true              (the development switch; docker-compose.public.yml no longer passes
                                                  it on, but it must not wait in .env for the next start)
      PAYMENTS_SANDBOX_ALLOWED_PUBLIC=true       (the switch of the public mode)
      PAYMENTS_SANDBOX_ALLOW_IN_PRODUCTION=...   (any value: the override of exam-service's production guard)
    A variable of the current process wins over .env, as docker compose reads them. Values are only compared, never
    returned or printed. Read only: it changes nothing.

    Get-PaymentsState <path of .env> says which payments the public mode runs (OFF, DEMO, SANDBOX-BLOCKED, EXTERNAL) and
    whether the DEMO mode is asked for and complete. In a complete DEMO the two sandbox switches are meant to be on:
    go-public.ps1 start goes on with a yellow warning instead of refusing.
#>

function Read-SandboxSetting([string]$envFile, [string]$name) {
    $fromProcess = [Environment]::GetEnvironmentVariable($name, 'Process')
    if ($null -ne $fromProcess) { return $fromProcess.Trim() }
    $value = $null
    if (Test-Path -LiteralPath $envFile) {
        foreach ($line in Get-Content -LiteralPath $envFile) {
            if ($line -match "^\s*(?:export\s+)?$name\s*=(.*)$") {
                # As docker compose reads it: quotes removed, or " #..." cut from an unquoted value
                $value = $Matches[1].Trim()
                if ($value -match '^"(.*)"$' -or $value -match "^'(.*)'$") { $value = $Matches[1] } else { $value = ($value -replace '\s+#.*$', '') }
            }
        }
    }
    return $value
}

function Test-SettingTrue([string]$envFile, [string]$name) {
    return "$(Read-SandboxSetting $envFile $name)".Trim() -eq 'true'
}

# The payments of the public mode (docs\DEMO_PAYMENTS.md), names and states only, never a value of a secret:
#   Mode           OFF (PAID_ACCESS_ENABLED not true), DEMO, SANDBOX-BLOCKED (PAYMENTS_PROVIDER=SANDBOX without the
#                  demo: exam-service keeps the sandbox closed), EXTERNAL, or the provider's name
#   DemoRequested  PAYMENTS_DEMO_MODE=true
#   DemoComplete   ... with PAYMENTS_PROVIDER=SANDBOX (also its default) and PAYMENTS_SANDBOX_ALLOWED_PUBLIC=true (the
#                  sandbox switch of the public mode, docker-compose.public.yml)
#   Missing        what the demo still needs
function Get-PaymentsState([string]$envFile) {
    $paid = Test-SettingTrue $envFile 'PAID_ACCESS_ENABLED'
    $demo = Test-SettingTrue $envFile 'PAYMENTS_DEMO_MODE'
    $provider = "$(Read-SandboxSetting $envFile 'PAYMENTS_PROVIDER')".Trim().ToUpperInvariant()
    if ($provider -eq '') { $provider = 'SANDBOX' }
    $missing = New-Object System.Collections.Generic.List[string]
    if ($demo) {
        if ($provider -ne 'SANDBOX') { $missing.Add('PAYMENTS_PROVIDER=SANDBOX') }
        if (-not (Test-SettingTrue $envFile 'PAYMENTS_SANDBOX_ALLOWED_PUBLIC')) { $missing.Add('PAYMENTS_SANDBOX_ALLOWED_PUBLIC=true') }
    }
    $complete = $demo -and $missing.Count -eq 0
    $mode = if (-not $paid) { 'OFF' } elseif ($complete) { 'DEMO' } elseif ($provider -eq 'SANDBOX') { 'SANDBOX-BLOCKED' } else { $provider }
    return [pscustomobject]@{ Mode = $mode; DemoRequested = $demo; DemoComplete = $complete; Missing = $missing.ToArray() }
}

function Get-SandboxState([string]$envFile) {
    $on = New-Object System.Collections.Generic.List[string]
    foreach ($name in 'PAYMENTS_SANDBOX_ALLOWED', 'PAYMENTS_SANDBOX_ALLOWED_PUBLIC') {
        if ("$(Read-SandboxSetting $envFile $name)".Trim() -eq 'true') { $on.Add($name) }
    }
    if ("$(Read-SandboxSetting $envFile 'PAYMENTS_SANDBOX_ALLOW_IN_PRODUCTION')".Trim() -ne '') { $on.Add('PAYMENTS_SANDBOX_ALLOW_IN_PRODUCTION') }
    # Callers wrap the call in @(): an empty list comes back as nothing
    return $on.ToArray()
}
