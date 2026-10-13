<#
.SYNOPSIS
    Sends one real email through the running stack: registers -To at the gateway, which makes
    auth-service email a verification code to that address.

.DESCRIPTION
    Uses the real registration flow (POST /auth/register through the gateway), so the email is exactly
    what a new user gets and takes the configured path: Mailpit by default, the provider of MAIL_HOST
    when one is set in .env. Then reads the outcome from the auth-service log (masked address, attempt,
    error type). No code, password or SMTP setting is printed; the password of the test account is
    random and thrown away.

    Use a throwaway address that you can read. The registration leaves an unverified STUDENT account
    for it, which cannot log in and is deactivated by the cleanup job after 14 days.

    An address that is already verified cannot be registered again (409): use -PasswordReset, which
    sends the password reset email to it instead (the same template and the same sending path).
    Per address there are 60 seconds between two codes and 5 codes per hour; inside those limits no
    email is sent although the answer is the same.

.EXAMPLE
    .\scripts\send-test-mail.ps1 -To someone@example.com
    .\scripts\send-test-mail.ps1 -To someone@example.com -PasswordReset
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$To,
    [string]$Gateway = 'http://localhost:8080',
    [string]$AuthContainer = 'exam-auth-service',
    [switch]$PasswordReset,
    [int]$WaitSeconds = 30   # how long to wait for the outcome in the log (3 attempts take up to about 25 s)
)

$ErrorActionPreference = 'Stop'

# The same masking as the services use in their logs (LogMaskUtils)
function Get-MaskedEmail {
    param([string]$Email)
    $at = $Email.IndexOf('@')
    if ($at -lt 1) { return '***' }
    return $Email.Substring(0, 1) + '***' + $Email.Substring($at)
}

$To = $To.Trim()
$masked = Get-MaskedEmail $To
$exitCode = 0

try {
    if ($To -notmatch '^[^@\s]+@[^@\s]+\.[^@\s]+$') { throw "'$To' is not an email address" }

    if ($PasswordReset) {
        $path = '/auth/forgot-password'
        $body = @{ email = $To }
    }
    else {
        $path = '/auth/register'
        # Random, satisfies the password rules, never printed or stored
        $password = 'Aa1' + [guid]::NewGuid().ToString('N')
        $body = @{ email = $To; password = $password; firstName = 'Mail'; lastName = 'Test' }
    }

    $since = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    $status = 0
    try {
        $response = Invoke-WebRequest -UseBasicParsing -Method Post -Uri "$Gateway$path" `
            -ContentType 'application/json' -Body ($body | ConvertTo-Json) -TimeoutSec 20
        $status = [int]$response.StatusCode
    }
    catch {
        if (-not $_.Exception.Response) { throw "The gateway at $Gateway did not answer: $($_.Exception.Message)" }
        $status = [int]$_.Exception.Response.StatusCode
    }

    Write-Host "POST $path for $masked -> HTTP $status"
    switch ($status) {
        { $_ -in 200, 201 } { break }
        409 { throw 'This address is already registered and verified. Use another address, or add -PasswordReset.' }
        429 { throw 'Rate limited by the gateway. Wait a minute and try again.' }
        default { throw "Unexpected answer (HTTP $status); see: docker logs $AuthContainer" }
    }

    # The email is sent in the background; its outcome is only in the auth-service log
    Write-Host "Waiting for the outcome in the $AuthContainer log (up to $WaitSeconds s)..."
    $pattern = [regex]::Escape("email to $masked")
    $outcome = $null
    $deadline = (Get-Date).AddSeconds($WaitSeconds)
    do {
        Start-Sleep -Seconds 2
        $previous = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'   # docker writes the container's stderr to stderr
        $lines = @(& docker logs --since $since $AuthContainer 2>&1 | ForEach-Object { "$_" } | Where-Object { $_ -match $pattern })
        $ErrorActionPreference = $previous
        $outcome = $lines | Where-Object { $_ -match 'succeeded|was not sent' } | Select-Object -Last 1
    } while (-not $outcome -and (Get-Date) -lt $deadline)

    foreach ($line in $lines) { Write-Host "  $line" }
    if (-not $outcome) {
        Write-Host 'No send was logged. Either the container name is different, or no email was due:' -ForegroundColor Yellow
        Write-Host '  a code was sent to this address less than 60 seconds ago, 5 codes were sent within the hour,' -ForegroundColor Yellow
        Write-Host '  or (with -PasswordReset) the address has no verified account.' -ForegroundColor Yellow
        $exitCode = 2
    }
    elseif ($outcome -match 'succeeded') {
        Write-Host 'The mail server accepted the email.' -ForegroundColor Green
        Write-Host 'Mailpit (default): open http://localhost:8025. Real provider: check the inbox AND the spam folder.'
    }
    else {
        throw 'The email was not sent (all attempts failed). The error type is in the lines above; check MAIL_* in .env.'
    }
}
catch {
    Write-Host "SEND TEST FAILED: $($_.Exception.Message)" -ForegroundColor Red
    $exitCode = 1
}

exit $exitCode
