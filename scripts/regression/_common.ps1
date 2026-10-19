# Shared by the verify-*.ps1 scripts (dot-sourced right after $GW is set): . "$PSScriptRoot\_common.ps1"
# Not a check by itself.

# Waits until the gateway answers its readiness probe (HTTP 200 and status UP), so that a stack that is still
# starting never shows up as "admin login answered 0" in the first request of a script. An answer of 0 (no
# connection) or 5xx is waited out; anything else (404, 401, ...) means the gateway is up but the probe is not
# what it expects, which is reported at once. Stops with a clear error when the time is over.
function Wait-Gateway {
    param(
        [string]$Gateway = $(if ($script:GW) { $script:GW } else { 'http://127.0.0.1:8080' }),
        [int]$TimeoutSeconds = 120,
        [int]$IntervalSeconds = 3
    )
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $last = 'no answer'
    while ($true) {
        $status = 0
        $body = ''
        try {
            $r = Invoke-WebRequest -Uri "$Gateway/actuator/health" -UseBasicParsing -TimeoutSec 5
            $status = [int]$r.StatusCode
            $body = [string]$r.Content
        } catch {
            $resp = $_.Exception.Response
            if ($resp) { $status = [int]$resp.StatusCode }
        }
        if ($status -eq 200 -and $body -match '"status"\s*:\s*"UP"') { return }
        if ($status -ne 0 -and $status -lt 500) {
            throw "gateway $Gateway answered $status to /actuator/health (expected 200 UP): is this the right stack?"
        }
        $last = if ($status -eq 0) { 'no answer' } else { "HTTP $status" }
        if ([DateTime]::UtcNow -ge $deadline) {
            throw "gateway $Gateway not ready after $TimeoutSeconds seconds (last: $last): start the development stack first"
        }
        Start-Sleep -Seconds $IntervalSeconds
    }
}
