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
            # PowerShell 5.1 returns byte[] for the actuator's vendor JSON content type
            $body = if ($r.Content -is [byte[]]) { [Text.Encoding]::UTF8.GetString($r.Content) } else { [string]$r.Content }
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

# An ACTIVE exam whose window is already open, for scripts that need to start an attempt at once. POST /api/exams
# refuses a start time in the past (@Future), so the exam is created ahead, published, and only then moved to
# [now - 1 minute, now + 3 hours] with POST /reschedule (which accepts a start in the past and makes the exam
# ACTIVE). Uses Api and Utc of the calling script. Returns the answer of the create call (status, data.id) with
# 'published' and 'rescheduled' answers added; check them (status 200) before relying on the exam.
function New-ActiveExam {
    param($Token, [scriptblock]$BodyFor)
    $now = [DateTime]::UtcNow
    $created = Api 'POST' '/api/exams' $Token (& $BodyFor $now.AddHours(1) $now.AddHours(3))
    $created['published'] = $null
    $created['rescheduled'] = $null
    if ($created.status -eq 201 -and $created.data.id) {
        $created['published'] = Api 'POST' "/api/exams/$($created.data.id)/publish" $Token $null
        $created['rescheduled'] = Api 'POST' "/api/exams/$($created.data.id)/reschedule" $Token @{ startTime = (Utc $now.AddMinutes(-1)); endTime = (Utc $now.AddHours(3)) }
    }
    return $created
}
