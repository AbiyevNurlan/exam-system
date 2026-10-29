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

function Get-SandboxState([string]$envFile) {
    $on = New-Object System.Collections.Generic.List[string]
    foreach ($name in 'PAYMENTS_SANDBOX_ALLOWED', 'PAYMENTS_SANDBOX_ALLOWED_PUBLIC') {
        if ("$(Read-SandboxSetting $envFile $name)".Trim() -eq 'true') { $on.Add($name) }
    }
    if ("$(Read-SandboxSetting $envFile 'PAYMENTS_SANDBOX_ALLOW_IN_PRODUCTION')".Trim() -ne '') { $on.Add('PAYMENTS_SANDBOX_ALLOW_IN_PRODUCTION') }
    # Callers wrap the call in @(): an empty list comes back as nothing
    return $on.ToArray()
}
