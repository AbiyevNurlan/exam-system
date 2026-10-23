# Shared by the verify-*.ps1 scripts (dot-sourced right after $GW is set): . "$PSScriptRoot\_common.ps1"
# Not a check by itself.

# B13b item 19: the regression and performance scripts create accounts, groups, exams and orders. On 2026-10-19 a run
# of verify-b2..b12a (verify-b6-buckets twice) left 988 @x.test accounts in exam_auth, because the development stack
# and the public site use the same PostgreSQL (one volume). This guard runs before anything is created (every
# verify-*.ps1 calls Wait-Gateway first) and refuses:
#   1. while the public mode is up (container exam-cloudflared running): never overridable;
#   2. when the database holds real accounts (not @x.test, not ADMIN): a live database. Only an operator who knows the
#      database is a throwaway copy may go on, by setting REGRESSION_TARGET_IS_DISPOSABLE=yes for that run.
# It only reads (one count in a read-only transaction); nothing secret is printed.
function Assert-SafeTarget {
    $tunnel = docker ps --filter 'name=^exam-cloudflared$' --filter 'status=running' --format '{{.Names}}' 2>$null
    if ($tunnel) {
        throw 'REFUSED: the public site is open (exam-cloudflared is running). Regression scripts run only on a development stack.'
    }
    $postgres = docker ps --filter 'name=^exam-postgres$' --filter 'status=running' --format '{{.Names}}' 2>$null
    if (-not $postgres) { return }
    $real = docker exec exam-postgres psql -U postgres -d exam_auth -Atc "BEGIN TRANSACTION READ ONLY; SELECT count(*) FROM users WHERE lower(email) NOT LIKE '%@x.test' AND role <> 'ADMIN'; COMMIT;" 2>$null |
        Where-Object { $_ -match '^\d+$' } | Select-Object -First 1
    if ($real -and [int]$real -gt 0 -and $env:REGRESSION_TARGET_IS_DISPOSABLE -ne 'yes') {
        throw ("REFUSED: exam_auth holds $real real accounts (not @x.test): this looks like the live database. Run the " +
               'regression scripts on a separate development database; only for a throwaway copy set REGRESSION_TARGET_IS_DISPOSABLE=yes.')
    }
}

# Waits until the gateway answers its readiness probe (HTTP 200 and status UP), so that a stack that is still
# starting never shows up as "admin login answered 0" in the first request of a script. An answer of 0 (no
# connection) or 5xx is waited out; anything else (404, 401, ...) means the gateway is up but the probe is not
# what it expects, which is reported at once. Stops with a clear error when the time is over.
# B13b: checks first that the target is safe (Assert-SafeTarget).
function Wait-Gateway {
    param(
        [string]$Gateway = $(if ($script:GW) { $script:GW } else { 'http://127.0.0.1:8080' }),
        [int]$TimeoutSeconds = 120,
        [int]$IntervalSeconds = 3
    )
    Assert-SafeTarget
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

# B13: a teacher's new exam is for groups and only ADMIN may make an exam PUBLIC (403 PUBLIC_EXAM_ADMIN_ONLY for
# a teacher). Scripts of earlier phases whose participants start a teacher's exam let the administrator open it
# to everybody, as an administrator would. Uses Api of the calling script; returns the answer (200 when done).
function Open-ExamToEveryone {
    param($AdminToken, $ExamId)
    return Api 'PUT' "/api/exams/$ExamId/audience" $AdminToken @{ accessType = 'PUBLIC'; groupIds = @() }
}

# An ACTIVE exam whose window is already open, for scripts that need to start an attempt at once. POST /api/exams
# refuses a start time in the past (@Future), so the exam is created ahead, published, and only then moved to
# [now - 1 minute, now + 3 hours] with POST /reschedule (which accepts a start in the past and makes the exam
# ACTIVE). Uses Api and Utc of the calling script. Returns the answer of the create call (status, data.id) with
# 'published' and 'rescheduled' answers added; check them (status 200) before relying on the exam.
# B13: with -OpenWith <admin token> a teacher's exam is opened to everybody by the administrator right after the
# create (Open-ExamToEveryone); a failure there shows as a 'published' answer of that call.
function New-ActiveExam {
    param($Token, [scriptblock]$BodyFor, $OpenWith = $null)
    $now = [DateTime]::UtcNow
    $created = Api 'POST' '/api/exams' $Token (& $BodyFor $now.AddHours(1) $now.AddHours(3))
    $created['published'] = $null
    $created['rescheduled'] = $null
    if ($created.status -eq 201 -and $created.data.id -and $OpenWith) {
        $opened = Open-ExamToEveryone $OpenWith $created.data.id
        if ($opened.status -ne 200) { $created['published'] = $opened; return $created }
    }
    if ($created.status -eq 201 -and $created.data.id) {
        $created['published'] = Api 'POST' "/api/exams/$($created.data.id)/publish" $Token $null
        $created['rescheduled'] = Api 'POST' "/api/exams/$($created.data.id)/reschedule" $Token @{ startTime = (Utc $now.AddMinutes(-1)); endTime = (Utc $now.AddHours(3)) }
    }
    return $created
}
