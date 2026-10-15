# Live verification of feat/exam-lifecycle (B2) against the running stack (gateway 8080).
# Admin credentials are read from F:\exam-system\.env into variables only; nothing secret is printed.
# Works only with the "T-b2-*" exams and @x.test accounts it creates itself.
$ErrorActionPreference = 'Continue'
Add-Type -AssemblyName System.Net.Http
$GW = 'http://127.0.0.1:8080'
$MAIL = 'http://127.0.0.1:8025'
$stamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString('x')
$script:results = @()
$PNG = [Convert]::FromBase64String('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==')

function Check($name, $ok, $detail = '') {
    $script:results += [pscustomobject]@{ name = $name; ok = [bool]$ok }
    $line = $(if ($ok) { 'PASS' } else { 'FAIL' }) + "  $name"
    if ($detail -ne '') { $line += "  ($detail)" }
    Write-Output $line
}

function Api($method, $path, $token, $body) {
    $headers = @{}
    if ($token) { $headers['Authorization'] = "Bearer $token" }
    $params = @{ Uri = "$GW$path"; Method = $method; Headers = $headers; UseBasicParsing = $true; TimeoutSec = 30 }
    if ($null -ne $body) { $params['Body'] = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 10)); $params['ContentType'] = 'application/json' }
    try {
        $r = Invoke-WebRequest @params
        $raw = $r.Content
        if ($raw -is [byte[]]) { $raw = [Text.Encoding]::UTF8.GetString($raw) }
        $status = [int]$r.StatusCode
    } catch {
        $resp = $_.Exception.Response
        if (-not $resp) { return @{ status = 0; raw = $_.Exception.Message; json = $null; data = $null; code = $null } }
        $status = [int]$resp.StatusCode
        $stream = $resp.GetResponseStream()
        if ($stream.CanSeek) { $stream.Position = 0 }
        $raw = (New-Object IO.StreamReader($stream)).ReadToEnd()
    }
    $json = $null
    if ($raw) { try { $json = $raw | ConvertFrom-Json } catch { $json = $null } }
    $data = $json
    $code = $null
    if ($json -and ($json.PSObject.Properties.Name -contains 'data')) { $data = $json.data }
    if ($json -and $json.error) { $code = $json.error.code }
    return @{ status = $status; raw = $raw; json = $json; data = $data; code = $code }
}

function Sql($db, $query) { docker exec exam-postgres psql -U postgres -d $db -Atc $query }

function MailedCode($email) {
    for ($i = 0; $i -lt 30; $i++) {
        $found = Invoke-RestMethod "$MAIL/api/v1/search?query=to:$email"
        if ($found.messages_count -gt 0) {
            $message = Invoke-RestMethod "$MAIL/api/v1/message/$($found.messages[0].ID)"
            if ($message.Text -match '(?<!\d)(\d{6})(?!\d)') { return $Matches[1] }
        }
        Start-Sleep -Milliseconds 500
    }
    throw "no mail for $email"
}

$password = 'Tt9!' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
function Register($name) {
    $email = "be-b2-$name-$stamp@x.test"
    $r = Api 'POST' '/auth/register' $null @{ email = $email; password = $password; firstName = 'Sinaq'; lastName = "Bii$name" }
    if ($r.status -ne 201) { throw "register $name answered $($r.status)" }
    $v = Api 'POST' '/auth/verify-otp' $null @{ email = $email; code = (MailedCode $email) }
    if ($v.status -ne 200) { throw "verify $name answered $($v.status)" }
    return @{ email = $email; id = $v.data.user.id; token = $v.data.accessToken }
}
function Login($email, $pw) { (Api 'POST' '/auth/login' $null @{ email = $email; password = $pw }) }
function UtcText([DateTime]$utc) { $utc.ToString('yyyy-MM-ddTHH:mm:ss') }

$DATE_PATTERN = '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$'
function DateFields($raw) {
    [regex]::Matches($raw, '"(\w*(?:Time|At|Until)|timestamp)":"([^"]*)"') | ForEach-Object { [pscustomobject]@{ field = $_.Groups[1].Value; value = $_.Groups[2].Value } }
}
function AllDatesZ($name, $raw) {
    $fields = @(DateFields $raw)
    $bad = @($fields | Where-Object { $_.value -notmatch $DATE_PATTERN })
    $names = @($fields | ForEach-Object { $_.field } | Sort-Object -Unique) -join ','
    Check "dates end with Z: $name" ($fields.Count -gt 0 -and $bad.Count -eq 0) "$($fields.Count) date values [$names]$(if ($bad.Count) { '; bad: ' + (($bad | Select-Object -First 3 | ForEach-Object { $_.field + '=' + $_.value }) -join ', ') })"
}

function ParallelStart($examId, $token, $n) {
    $client = New-Object System.Net.Http.HttpClient
    $client.DefaultRequestHeaders.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $token)
    $tasks = @(1..$n | ForEach-Object { $client.PostAsync("$GW/api/exams/$examId/start", (New-Object System.Net.Http.StringContent('{}', [Text.Encoding]::UTF8, 'application/json'))) })
    try { [Threading.Tasks.Task]::WaitAll([Threading.Tasks.Task[]]$tasks) } catch { }
    $out = foreach ($task in $tasks) {
        if ($task.IsFaulted) { [pscustomobject]@{ status = 0; body = '' } }
        else { [pscustomobject]@{ status = [int]$task.Result.StatusCode; body = $task.Result.Content.ReadAsStringAsync().Result } }
    }
    $client.Dispose()
    return $out
}

function Finish($sessionId, $token) { (Api 'POST' "/api/exams/session/$sessionId/submit" $token $null) }

# ---------- setup ----------
$adminEmail = $null; $adminPassword = $null
foreach ($line in Get-Content F:\exam-system\.env) {
    if ($line -match '^ADMIN_EMAIL=(.*)$') { $adminEmail = $Matches[1].Trim() }
    if ($line -match '^ADMIN_PASSWORD=(.*)$') { $adminPassword = $Matches[1].Trim() }
}
$mainLogin = Login $adminEmail $adminPassword
if ($mainLogin.status -ne 200) { throw "admin login answered $($mainLogin.status)" }
$adm = @{ id = $mainLogin.data.user.id; token = $mainLogin.data.accessToken }

$stuA = Register 'a'; $stuB = Register 'b'; $admTwo = Register 'y'; $ins = Register 'z'; $insTwo = Register 'w'
# Every role given below is taken back in the finally block before the summary
try {
# Roles cannot be assigned through the API: set them on these test accounts only, then log in again
Sql 'exam_auth' "update users set role='ADMIN' where email='$($admTwo.email)'" | Out-Null
Sql 'exam_auth' "update users set role='INSTRUCTOR' where email in ('$($ins.email)','$($insTwo.email)')" | Out-Null
$admTwo.token = (Login $admTwo.email $password).data.accessToken
$ins.token = (Login $ins.email $password).data.accessToken
$insTwo.token = (Login $insTwo.email $password).data.accessToken
Write-Output "accounts: a, b (students), y (ADMIN for the test), z, w (INSTRUCTOR for the test) -$stamp@x.test"

# ---------- j) auth-service dates ----------
AllDatesZ 'login response' $mainLogin.raw
$loginKeys = @($mainLogin.data.PSObject.Properties.Name)
Check 'login response keeps accessToken, refreshToken, tokenType, expiresIn, user' (-not ('accessToken', 'refreshToken', 'tokenType', 'expiresIn', 'user' | Where-Object { $_ -notin $loginKeys })) ($loginKeys -join ', ')
$userList = Api 'GET' '/auth/admin/users?size=50' $adm.token $null
Check 'admin user list answers 200' ($userList.status -eq 200) "status $($userList.status)"
AllDatesZ 'admin user list' $userList.raw
Check 'admin user list has createdAt on its rows' ($userList.raw -match '"createdAt":"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z"')
$oneUser = Api 'GET' "/auth/admin/users?id=$($stuA.id)" $adm.token $null
$sampleCreated = ([regex]::Match($oneUser.raw, '"createdAt":"([^"]+)"')).Groups[1].Value
$dbCreated = Sql 'exam_auth' "select to_char(created_at, 'YYYY-MM-DD') || 'T' || to_char(created_at, 'HH24:MI:SS.MS') || 'Z' from users where id='$($stuA.id)'"
Check 'createdAt of a user is the stored UTC value with Z' ($oneUser.status -eq 200 -and $sampleCreated -eq $dbCreated -and $sampleCreated -match $DATE_PATTERN) "api $sampleCreated, database $dbCreated"
$userKeys = @(@($oneUser.data.content)[0].PSObject.Properties.Name)
Check 'user row keeps its field names' (-not ('id', 'email', 'firstName', 'lastName', 'role', 'locked', 'failedAttempts', 'emailVerified', 'createdAt' | Where-Object { $_ -notin $userKeys })) ($userKeys -join ', ')
$lockedList = Api 'GET' '/auth/admin/users?locked=true' $adm.token $null
$lockedValues = @([regex]::Matches($lockedList.raw, '"lockedUntil":"([^"]*)"') | ForEach-Object { $_.Groups[1].Value })
Check 'lockedUntil, where present, ends with Z' ($lockedList.status -eq 200 -and -not ($lockedValues | Where-Object { $_ -notmatch $DATE_PATTERN })) "$($lockedValues.Count) locked rows with lockedUntil"

# ---------- exams ----------
function Option($text, $correct, $order) { @{ optionText = $text; isCorrect = $correct; orderIndex = $order } }
function Questions($mediaId) {
    $first = @{ questionText = 'T-b2 single: 2 + 2 = ?'; questionType = 'MULTIPLE_CHOICE'; points = 2; orderIndex = 0; allowMultipleAnswers = $false; explanation = 'T-b2 secret explanation'; options = @((Option '3' $false 0), (Option '4' $true 1)) }
    if ($mediaId) { $first['media'] = @(@{ mediaId = $mediaId; position = 0 }) }
    @($first, @{ questionText = 'T-b2 second: 3 + 3 = ?'; questionType = 'MULTIPLE_CHOICE'; points = 3; orderIndex = 1; allowMultipleAnswers = $false; options = @((Option '6' $true 0), (Option '7' $false 1)) })
}
function ExamBody($title, [DateTime]$startUtc, [DateTime]$endUtc, $maxAttempts, $perDay, $mediaId) {
    $body = @{ title = $title; description = 'B2 verification'; durationMinutes = 30; startTime = (UtcText $startUtc); endTime = (UtcText $endUtc)
               shuffleQuestions = $false; shuffleOptions = $false; showResultsImmediately = $true; maxAttempts = $maxAttempts; passingScore = 50; questions = (Questions $mediaId) }
    if ($null -ne $perDay) { $body['maxAttemptsPerDay'] = $perDay }
    return $body
}
function Upload($owner, $alt) {
    $u = Api 'POST' '/api/media/upload-url' $owner.token @{ kind = 'IMAGE'; contentType = 'image/png'; sizeBytes = $PNG.Length; altText = $alt }
    if ($u.status -ne 200) { throw "upload-url answered $($u.status)" }
    Invoke-WebRequest -Uri $u.data.uploadUrl -Method Put -Body $PNG -ContentType 'image/png' -UseBasicParsing -TimeoutSec 30 | Out-Null
    $c = Api 'POST' "/api/media/$($u.data.mediaId)/confirm" $owner.token @{}
    if ($c.status -ne 200) { throw "confirm answered $($c.status)" }
    return $u.data.mediaId
}

$start = [DateTime]::UtcNow.AddSeconds(15)
$start = $start.AddTicks(-($start.Ticks % [TimeSpan]::TicksPerSecond))
$weekEnd = $start.AddDays(7)

# a) step 1: fields and limits
$daily = Api 'POST' '/api/exams' $ins.token (ExamBody "T-b2-$stamp-daily" $start $weekEnd 5 2 $null)
Check 'create with maxAttemptsPerDay=2 answers it back' ($daily.status -eq 201 -and $daily.data.maxAttemptsPerDay -eq 2 -and $daily.data.maxAttempts -eq 5) "status $($daily.status) $($daily.code)"
$par = Api 'POST' '/api/exams' $ins.token (ExamBody "T-b2-$stamp-parallel" $start $weekEnd 5 2 $null)
$resch = Api 'POST' '/api/exams' $ins.token (ExamBody "T-b2-$stamp-resched" $start $start.AddHours(3) 3 $null $null)
Check 'create without maxAttemptsPerDay: field absent (no daily limit)' ($resch.status -eq 201 -and -not ($resch.data.PSObject.Properties.Name -contains 'maxAttemptsPerDay')) "status $($resch.status)"
$once = Api 'POST' '/api/exams' $admTwo.token (ExamBody "T-b2-$stamp-once" $start $weekEnd 1 $null $null)
$insMedia = Upload $ins 'T-b2 instructor image'
$draft = Api 'POST' '/api/exams' $ins.token (ExamBody "T-b2-$stamp-draft" $start $weekEnd 100 100 $insMedia)
Check 'maxAttempts=100 and maxAttemptsPerDay=100 are accepted (with media)' ($draft.status -eq 201 -and $draft.data.maxAttempts -eq 100 -and $draft.data.maxAttemptsPerDay -eq 100) "status $($draft.status) $($draft.code)"
Check 'maxAttempts=101 is 400' ((Api 'POST' '/api/exams' $ins.token (ExamBody "T-b2-$stamp-bad1" $start $weekEnd 101 $null $null)).status -eq 400)
Check 'maxAttemptsPerDay=0 is 400' ((Api 'POST' '/api/exams' $ins.token (ExamBody "T-b2-$stamp-bad2" $start $weekEnd 5 0 $null)).status -eq 400)
Check 'maxAttemptsPerDay=101 is 400' ((Api 'POST' '/api/exams' $ins.token (ExamBody "T-b2-$stamp-bad3" $start $weekEnd 5 101 $null)).status -eq 400)
$badRange = Api 'POST' '/api/exams' $ins.token (ExamBody "T-b2-$stamp-bad4" $start.AddHours(2) $start.AddHours(1) 1 $null $null)
Check 'endTime before startTime is still 400 EXAM_INVALID_TIME_RANGE' ($badRange.status -eq 400 -and $badRange.code -eq 'EXAM_INVALID_TIME_RANGE') "status $($badRange.status) $($badRange.code)"
$mediaExam = Api 'POST' '/api/exams' $ins.token (ExamBody "T-b2-$stamp-media" $start $weekEnd 2 3 $null)
$idDaily = $daily.data.id; $idPar = $par.data.id; $idResch = $resch.data.id; $idOnce = $once.data.id; $idDraft = $draft.data.id; $idMedia = $mediaExam.data.id
Check 'no T-b2 exam with a refused limit was stored' ((Sql 'exam_db' "select count(*) from exams where title like 'T-b2-$stamp-bad%'") -eq '0')
$oldExam = Api 'GET' '/api/exams/1' $adm.token $null
Check 'an exam from before the migration: no daily limit, unchanged maxAttempts' ($oldExam.status -eq 200 -and -not ($oldExam.data.PSObject.Properties.Name -contains 'maxAttemptsPerDay') -and (Sql 'exam_db' 'select count(*) from exams where id <= 90 and max_attempts_per_day is not null') -eq '0')

foreach ($examId in $idDaily, $idPar, $idResch) { if ((Api 'POST' "/api/exams/$examId/publish" $ins.token $null).status -ne 200) { throw "publish $examId failed" } }
if ((Api 'POST' "/api/exams/$idOnce/publish" $admTwo.token $null).status -ne 200) { throw 'publish once failed' }
$wait = ($start - [DateTime]::UtcNow).TotalSeconds + 2
if ($wait -gt 0) { Start-Sleep -Seconds ([int][Math]::Ceiling($wait)) }

# ---------- b) daily limit ----------
# Start of the next day in Asia/Baku (UTC+4, no DST), as a UTC instant
$bakuNow = [DateTime]::UtcNow.AddHours(4)
$bakuMidnightUtc = $bakuNow.Date.AddHours(-4)
$nextDayUtc = $bakuMidnightUtc.AddDays(1)
$nextDayText = $nextDayUtc.ToString('yyyy-MM-ddTHH:mm:ss') + '.000Z'

function AvailableRow($token, $examId) {
    $list = Api 'GET' '/api/exams/available?size=50' $token $null
    return @{ row = (@($list.data.content) | Where-Object { $_.id -eq $examId }); raw = $list.raw; status = $list.status }
}
$before = AvailableRow $stuA.token $idDaily
Check '/available before any start: remainingAttemptsToday=2, remainingAttempts=2, canStart, no nextAttemptAt' ($before.row.remainingAttemptsToday -eq 2 -and $before.row.remainingAttempts -eq 2 -and $before.row.canStart -eq $true -and -not ($before.row.PSObject.Properties.Name -contains 'nextAttemptAt')) "today $($before.row.remainingAttemptsToday), remaining $($before.row.remainingAttempts), canStart $($before.row.canStart)"
$onceRow = AvailableRow $stuA.token $idOnce
Check '/available for an exam without daily limit: remainingAttemptsToday absent' ($onceRow.row -and -not ($onceRow.row.PSObject.Properties.Name -contains 'remainingAttemptsToday') -and $onceRow.row.remainingAttempts -eq 1)

$d1 = Api 'POST' "/api/exams/$idDaily/start" $stuA.token @{}
$d1again = Api 'POST' "/api/exams/$idDaily/start" $stuA.token @{}
Check 'first start today; starting again resumes the same session' ($d1.status -eq 200 -and $d1again.status -eq 200 -and $d1again.data.sessionId -eq $d1.data.sessionId) "status $($d1.status)/$($d1again.status)"
Check 'the resume was not counted: one session in the database' ((Sql 'exam_db' "select count(*) from exam_sessions where exam_id=$idDaily and user_id='$($stuA.id)'") -eq '1')
$midRow = AvailableRow $stuA.token $idDaily
Check '/available with a running session: remainingAttemptsToday=1, canStart true' ($midRow.row.remainingAttemptsToday -eq 1 -and $midRow.row.canStart -eq $true)
$q1 = @($d1.data.questions)[0]
$ans = Api 'POST' "/api/exams/session/$($d1.data.sessionId)/answer" $stuA.token @{ questionId = $q1.id; selectedOptionIds = @(($q1.options | Where-Object { $_.optionText -eq '4' -or $_.optionText -eq '6' }).id) }
$fin1 = Finish $d1.data.sessionId $stuA.token
$res1 = Api 'GET' "/api/exams/session/$($d1.data.sessionId)/result" $stuA.token $null
Check 'regress: answer, submit and result of the first session' ($ans.status -eq 200 -and $fin1.status -eq 200 -and $res1.status -eq 200 -and $res1.data.resultsAvailable -eq $true -and $res1.data.totalScore -gt 0) "answer $($ans.status), submit $($fin1.status), result $($res1.status), score $($res1.data.totalScore)/$($res1.data.maxScore)"
$d2 = Api 'POST' "/api/exams/$idDaily/start" $stuA.token @{}
$fin2 = Finish $d2.data.sessionId $stuA.token
Check 'second start today is a new session' ($d2.status -eq 200 -and $d2.data.sessionId -ne $d1.data.sessionId -and $fin2.status -eq 200) "status $($d2.status)"
$d3 = Api 'POST' "/api/exams/$idDaily/start" $stuA.token @{}
Check 'third start today: 409 DAILY_ATTEMPTS_REACHED' ($d3.status -eq 409 -and $d3.code -eq 'DAILY_ATTEMPTS_REACHED') "status $($d3.status) $($d3.code)"
Check 'the refusal carries nextAvailableAt = start of the next day in Baku, as UTC with Z' ($d3.json.error.details.nextAvailableAt -eq $nextDayText) "got $($d3.json.error.details.nextAvailableAt), expected $nextDayText"
$full = AvailableRow $stuA.token $idDaily
Check '/available when today is used up: remainingAttemptsToday=0, remainingAttempts=0, canStart false, nextAttemptAt' ($full.row.remainingAttemptsToday -eq 0 -and $full.row.remainingAttempts -eq 0 -and $full.row.canStart -eq $false -and $full.row.nextAttemptAt -eq $nextDayText) "today $($full.row.remainingAttemptsToday), remaining $($full.row.remainingAttempts), canStart $($full.row.canStart), nextAttemptAt $($full.row.nextAttemptAt)"
Check 'old /available fields are still there' (-not ('id', 'title', 'durationMinutes', 'startTime', 'endTime', 'status', 'maxAttempts', 'remainingAttempts', 'canStart' | Where-Object { $_ -notin $full.row.PSObject.Properties.Name }))

# Day boundary: move ONLY the first test session to yesterday 23:30 Baku time
$yesterday = $bakuMidnightUtc.AddMinutes(-30).ToString('yyyy-MM-dd HH:mm:ss')
$moved = Sql 'exam_db' "update exam_sessions set created_at = timestamp '$yesterday' where session_id = '$($d1.data.sessionId)' and exam_id = $idDaily returning 1"
Check 'exactly one test session was moved to yesterday 23:30 Baku' (@($moved | Where-Object { $_ -eq '1' }).Count -eq 1) "created_at = $yesterday UTC"
$newDay = AvailableRow $stuA.token $idDaily
Check '/available after the day boundary: remainingAttemptsToday=1 again, canStart true' ($newDay.row.remainingAttemptsToday -eq 1 -and $newDay.row.canStart -eq $true -and -not ($newDay.row.PSObject.Properties.Name -contains 'nextAttemptAt')) "today $($newDay.row.remainingAttemptsToday)"
$d4 = Api 'POST' "/api/exams/$idDaily/start" $stuA.token @{}
$fin4 = Finish $d4.data.sessionId $stuA.token
Check 'a session of yesterday does not count today: the start works' ($d4.status -eq 200 -and $fin4.status -eq 200) "status $($d4.status) $($d4.code)"
$d5 = Api 'POST' "/api/exams/$idDaily/start" $stuA.token @{}
Check 'and today is full again after it (3 of 5 in total)' ($d5.status -eq 409 -and $d5.code -eq 'DAILY_ATTEMPTS_REACHED' -and (Sql 'exam_db' "select count(*) from exam_sessions where exam_id=$idDaily and user_id='$($stuA.id)'") -eq '3') "status $($d5.status) $($d5.code)"

# Total limit stays its own answer
$o1 = Api 'POST' "/api/exams/$idOnce/start" $stuB.token @{}
$oFin = Finish $o1.data.sessionId $stuB.token
$o2 = Api 'POST' "/api/exams/$idOnce/start" $stuB.token @{}
Check 'total limit still answers 409 MAX_ATTEMPTS_REACHED' ($o1.status -eq 200 -and $o2.status -eq 409 -and $o2.code -eq 'MAX_ATTEMPTS_REACHED') "status $($o2.status) $($o2.code)"

# 10 parallel starts
$burst1 = @(ParallelStart $idPar $stuB.token 10)
$ids1 = @($burst1 | Where-Object { $_.status -eq 200 } | ForEach-Object { ($_.body | ConvertFrom-Json).data.sessionId } | Sort-Object -Unique)
$count1 = Sql 'exam_db' "select count(*) from exam_sessions where exam_id=$idPar and user_id='$($stuB.id)'"
Check '10 parallel starts open ONE session and all answer with it' ($count1 -eq '1' -and $ids1.Count -eq 1 -and @($burst1 | Where-Object { $_.status -eq 200 }).Count -eq 10) "sessions $count1, statuses $(($burst1 | Group-Object status | ForEach-Object { "$($_.Name)x$($_.Count)" }) -join ' ')"
Finish $ids1[0] $stuB.token | Out-Null
$burst2 = @(ParallelStart $idPar $stuB.token 10)
$ids2 = @($burst2 | Where-Object { $_.status -eq 200 } | ForEach-Object { ($_.body | ConvertFrom-Json).data.sessionId } | Sort-Object -Unique)
$count2 = Sql 'exam_db' "select count(*) from exam_sessions where exam_id=$idPar and user_id='$($stuB.id)'"
Check '10 more parallel starts: exactly 2 sessions today' ($count2 -eq '2' -and $ids2.Count -eq 1) "sessions $count2, statuses $(($burst2 | Group-Object status | ForEach-Object { "$($_.Name)x$($_.Count)" }) -join ' ')"
Finish $ids2[0] $stuB.token | Out-Null
$burst3 = @(ParallelStart $idPar $stuB.token 10)
$count3 = Sql 'exam_db' "select count(*) from exam_sessions where exam_id=$idPar and user_id='$($stuB.id)'"
Check '10 parallel starts over the daily limit: all 409 DAILY_ATTEMPTS_REACHED, still 2 sessions' ($count3 -eq '2' -and @($burst3 | Where-Object { $_.status -eq 409 -and $_.body -match 'DAILY_ATTEMPTS_REACHED' }).Count -eq 10) "sessions $count3, statuses $(($burst3 | Group-Object status | ForEach-Object { "$($_.Name)x$($_.Count)" }) -join ' ')"

# ---------- c) reschedule ----------
$r1 = Api 'POST' "/api/exams/$idResch/start" $stuA.token @{}
$rq = @($r1.data.questions)[0]
Api 'POST' "/api/exams/session/$($r1.data.sessionId)/answer" $stuA.token @{ questionId = $rq.id; selectedOptionIds = @(($rq.options | Where-Object { $_.optionText -eq '4' -or $_.optionText -eq '6' }).id) } | Out-Null
$rFin = Finish $r1.data.sessionId $stuA.token
$resultBefore = (Api 'GET' "/api/exams/session/$($r1.data.sessionId)/result" $stuA.token $null).raw -replace '"timestamp":"[^"]*"', ''
# Let the exam end: only this test exam gets an end time in the past, the scheduler completes it
Sql 'exam_db' "update exams set end_time = LOCALTIMESTAMP - interval '1 minute' where id = $idResch and title like 'T-b2-%'" | Out-Null
$completed = $false
for ($i = 0; $i -lt 16; $i++) { if ((Sql 'exam_db' "select status from exams where id=$idResch") -eq 'COMPLETED') { $completed = $true; break }; Start-Sleep -Seconds 5 }
Check 'the scheduler completed the ended test exam (conditional update)' $completed
$closedStart = Api 'POST' "/api/exams/$idResch/start" $stuA.token @{}
Check 'a completed exam cannot be started' ($closedStart.status -eq 400 -and $closedStart.code -eq 'EXAM_NOT_ACTIVE') "status $($closedStart.status) $($closedStart.code)"
$sessionsBefore = Sql 'exam_db' "select md5(string_agg(s::text, ';' order by id)) from exam_sessions s where exam_id=$idResch"
$answersBefore = Sql 'exam_db' "select md5(string_agg(a::text, ';' order by a.id)) from user_answers a join exam_sessions s on s.id=a.session_id where s.exam_id=$idResch"
$questionsBefore = Sql 'exam_db' "select md5(string_agg(q::text, ';' order by id)) from questions q where exam_id=$idResch"
$auditMax = [int](Sql 'exam_db' 'select coalesce(max(id),0) from group_audit_log')

$newEnd = [DateTime]::UtcNow.AddHours(2); $newEnd = $newEnd.AddTicks(-($newEnd.Ticks % [TimeSpan]::TicksPerSecond))
$rs1 = Api 'POST' "/api/exams/$idResch/reschedule" $ins.token @{ endTime = (UtcText $newEnd) }
Check 'reschedule of a COMPLETED exam by its owner: 200, ACTIVE, new endTime' ($rs1.status -eq 200 -and $rs1.data.status -eq 'ACTIVE' -and $rs1.data.endTime -eq ($newEnd.ToString('yyyy-MM-ddTHH:mm:ss') + '.000Z') -and $rs1.data.canStart -eq $true) "status $($rs1.status) $($rs1.code), exam $($rs1.data.status), endTime $($rs1.data.endTime)"
Check 'the answer is the exam without questions and without the key' ($rs1.raw -notmatch '"questions"|isCorrect|explanation|T-b2 secret')
AllDatesZ 'reschedule response' $rs1.raw
Check 'sessions, answers and questions are untouched' ((Sql 'exam_db' "select md5(string_agg(s::text, ';' order by id)) from exam_sessions s where exam_id=$idResch") -eq $sessionsBefore -and (Sql 'exam_db' "select md5(string_agg(a::text, ';' order by a.id)) from user_answers a join exam_sessions s on s.id=a.session_id where s.exam_id=$idResch") -eq $answersBefore -and (Sql 'exam_db' "select md5(string_agg(q::text, ';' order by id)) from questions q where exam_id=$idResch") -eq $questionsBefore)
$resultAfter = (Api 'GET' "/api/exams/session/$($r1.data.sessionId)/result" $stuA.token $null).raw -replace '"timestamp":"[^"]*"', ''
Check 'the earlier result reads exactly as before' ($resultAfter -eq $resultBefore -and $resultAfter.Length -gt 50)
$rescheduledAt = Get-Date
$r2 = Api 'POST' "/api/exams/$idResch/start" $stuA.token @{}
Check 'the participant can start the reopened exam (2nd of 3 attempts)' ($r2.status -eq 200 -and $r2.data.sessionId -ne $r1.data.sessionId) "status $($r2.status) $($r2.code)"
$runningEnd = Sql 'exam_db' "select end_time from exam_sessions where session_id='$($r2.data.sessionId)'"
$rs2 = Api 'POST' "/api/exams/$idResch/reschedule" $ins.token @{ endTime = (UtcText $newEnd.AddHours(1)); maxAttempts = 4; maxAttemptsPerDay = 3 }
Check 'reschedule twice in a row: 200, limits updated' ($rs2.status -eq 200 -and $rs2.data.status -eq 'ACTIVE' -and $rs2.data.maxAttempts -eq 4 -and $rs2.data.maxAttemptsPerDay -eq 3) "status $($rs2.status) $($rs2.code), maxAttempts $($rs2.data.maxAttempts), perDay $($rs2.data.maxAttemptsPerDay)"
Check 'a session in progress keeps its own end time' ((Sql 'exam_db' "select end_time from exam_sessions where session_id='$($r2.data.sessionId)'") -eq $runningEnd -and (Sql 'exam_db' "select status from exam_sessions where session_id='$($r2.data.sessionId)'") -eq 'IN_PROGRESS') "end $runningEnd"
$rsOther = Api 'POST' "/api/exams/$idResch/reschedule" $insTwo.token @{ endTime = (UtcText $newEnd.AddHours(5)) }
Check "another INSTRUCTOR: 403 EXAM_NOT_OWNER, nothing changed" ($rsOther.status -eq 403 -and $rsOther.code -eq 'EXAM_NOT_OWNER' -and (Sql 'exam_db' "select to_char(end_time,'YYYY-MM-DD HH24:MI:SS') from exams where id=$idResch") -eq $newEnd.AddHours(1).ToString('yyyy-MM-dd HH:mm:ss')) "status $($rsOther.status) $($rsOther.code)"
$rsStudent = Api 'POST' "/api/exams/$idResch/reschedule" $stuA.token @{ endTime = (UtcText $newEnd.AddHours(5)) }
Check 'a STUDENT: 403' ($rsStudent.status -eq 403) "status $($rsStudent.status) $($rsStudent.code)"
$rsAdmin = Api 'POST' "/api/exams/$idResch/reschedule" $admTwo.token @{ endTime = (UtcText $newEnd.AddHours(2)); maxAttemptsPerDay = $null }
Check "ADMIN on someone else's exam: 200; explicit null removes the daily limit" ($rsAdmin.status -eq 200 -and -not ($rsAdmin.data.PSObject.Properties.Name -contains 'maxAttemptsPerDay') -and $rsAdmin.data.maxAttempts -eq 4 -and (Sql 'exam_db' "select max_attempts_per_day is null from exams where id=$idResch") -eq 't') "status $($rsAdmin.status) $($rsAdmin.code)"
$rsPast = Api 'POST' "/api/exams/$idResch/reschedule" $ins.token @{ endTime = (UtcText ([DateTime]::UtcNow.AddMinutes(-5))) }
Check 'endTime in the past: 400' ($rsPast.status -eq 400) "status $($rsPast.status) $($rsPast.code)"
$rsNoEnd = Api 'POST' "/api/exams/$idResch/reschedule" $ins.token @{ maxAttempts = 2 }
Check 'missing endTime: 400' ($rsNoEnd.status -eq 400) "status $($rsNoEnd.status) $($rsNoEnd.code)"
$rsRange = Api 'POST' "/api/exams/$idResch/reschedule" $ins.token @{ startTime = (UtcText ([DateTime]::UtcNow.AddHours(5))); endTime = (UtcText ([DateTime]::UtcNow.AddHours(4))) }
Check 'endTime before the new startTime: 400 EXAM_INVALID_TIME_RANGE' ($rsRange.status -eq 400 -and $rsRange.code -eq 'EXAM_INVALID_TIME_RANGE') "status $($rsRange.status) $($rsRange.code)"
$rsBadLimit = Api 'POST' "/api/exams/$idResch/reschedule" $ins.token @{ endTime = (UtcText $newEnd); maxAttempts = 101 }
Check 'maxAttempts=101 on reschedule: 400' ($rsBadLimit.status -eq 400) "status $($rsBadLimit.status)"
$rsDraft = Api 'POST' "/api/exams/$idDraft/reschedule" $ins.token @{ endTime = (UtcText $newEnd) }
Check 'a DRAFT: 400 EXAM_CANNOT_RESCHEDULE, still DRAFT' ($rsDraft.status -eq 400 -and $rsDraft.code -eq 'EXAM_CANNOT_RESCHEDULE' -and (Sql 'exam_db' "select status from exams where id=$idDraft") -eq 'DRAFT') "status $($rsDraft.status) $($rsDraft.code)"
Check 'unknown exam: 404' ((Api 'POST' '/api/exams/99999999/reschedule' $adm.token @{ endTime = (UtcText $newEnd) }).status -eq 404)
$auditRows = @(Sql 'exam_db' "select actor_id || '|' || action || '|' || coalesce(target_user_id::text,'') || '|' || details from group_audit_log where id > $auditMax and action = 'EXAM_RESCHEDULED' and details like 'examId=$idResch %' order by id")
Check 'one audit row per successful reschedule (3), none for the refused ones' ($auditRows.Count -eq 3) "$($auditRows.Count) rows"
Check 'the audit row names actor, exam, old and new window, status and limits' ($auditRows[0] -like "$($ins.id)|EXAM_RESCHEDULED|$($ins.id)|examId=$idResch status=COMPLETED -> ACTIVE window=*..* -> *..$($newEnd.ToString('yyyy-MM-ddTHH:mm:ss')).000Z maxAttempts=3 -> 3 maxAttemptsPerDay=null -> null" -and $auditRows[2] -like "$($admTwo.id)|EXAM_RESCHEDULED|$($ins.id)|*maxAttempts=4 -> 4 maxAttemptsPerDay=3 -> null") $auditRows[0].Substring($auditRows[0].IndexOf('examId'))
Finish $r2.data.sessionId $stuA.token | Out-Null

# ---------- d) archive, delete ----------
$pRun = Api 'POST' "/api/exams/$idPar/start" $stuA.token @{}
$arch = Api 'POST' "/api/exams/$idPar/archive" $ins.token $null
Check 'archive: 200 ARCHIVED' ($arch.status -eq 200 -and $arch.data.status -eq 'ARCHIVED') "status $($arch.status)"
$manageDefault = Api 'GET' "/api/exams/manage?search=T-b2-$stamp&size=50" $ins.token $null
$manageArchived = Api 'GET' "/api/exams/manage?search=T-b2-$stamp&status=ARCHIVED" $ins.token $null
Check 'an archived exam is not in the default management list' ($manageDefault.status -eq 200 -and -not (@($manageDefault.data.content) | Where-Object { $_.id -eq $idPar })) "status $($manageDefault.status) $($manageDefault.code)"
Check 'and is listed with status=ARCHIVED' (@($manageArchived.data.content).Count -eq 1 -and @($manageArchived.data.content)[0].id -eq $idPar) "$(@($manageArchived.data.content).Count) rows"
Check 'an archived exam is not in /available' (-not (AvailableRow $stuB.token $idPar).row -and -not (AvailableRow $stuA.token $idPar).row)
$archStart = Api 'POST' "/api/exams/$idPar/start" $stuB.token @{}
Check 'and cannot be started' ($archStart.status -eq 400 -and $archStart.code -eq 'EXAM_NOT_ACTIVE') "status $($archStart.status) $($archStart.code)"
$pq = @($pRun.data.questions)[0]
$pAns = Api 'POST' "/api/exams/session/$($pRun.data.sessionId)/answer" $stuA.token @{ questionId = $pq.id; selectedOptionIds = @(@($pq.options)[0].id) }
$pFin = Finish $pRun.data.sessionId $stuA.token
Check 'a session that was in progress when the exam was archived can be answered and submitted' ($pRun.status -eq 200 -and $pAns.status -eq 200 -and $pFin.status -eq 200) "start $($pRun.status), answer $($pAns.status), submit $($pFin.status)"
$sessionsArchived = Sql 'exam_db' "select count(*) from exam_sessions where exam_id=$idPar"
$restore = Api 'POST' "/api/exams/$idPar/reschedule" $ins.token @{ endTime = (UtcText $weekEnd) }
Check 'reschedule brings the archived exam back: ACTIVE, all sessions kept, listed again' ($restore.status -eq 200 -and $restore.data.status -eq 'ACTIVE' -and (Sql 'exam_db' "select count(*) from exam_sessions where exam_id=$idPar") -eq $sessionsArchived -and $sessionsArchived -eq '3' -and (AvailableRow $stuA.token $idPar).row) "status $($restore.status), sessions $sessionsArchived"

$countsBefore = Sql 'exam_db' "select (select count(*) from exams)||'/'||(select count(*) from questions)||'/'||(select count(*) from question_options)||'/'||(select count(*) from exam_sessions)||'/'||(select count(*) from user_answers)||'/'||(select count(*) from exam_audience)"
$delBusy = Api 'DELETE' "/api/exams/$idResch" $ins.token $null
$countsAfter = Sql 'exam_db' "select (select count(*) from exams)||'/'||(select count(*) from questions)||'/'||(select count(*) from question_options)||'/'||(select count(*) from exam_sessions)||'/'||(select count(*) from user_answers)||'/'||(select count(*) from exam_audience)"
Check 'delete of an exam with attempts: 409 EXAM_CANNOT_DELETE, message says to archive' ($delBusy.status -eq 409 -and $delBusy.code -eq 'EXAM_CANNOT_DELETE' -and $delBusy.json.message -match 'archive') "status $($delBusy.status) $($delBusy.code): $($delBusy.json.message)"
Check 'and nothing was removed' ($countsBefore -eq $countsAfter -and (Sql 'exam_db' "select status||active::text from exams where id=$idResch") -eq 'ACTIVEtrue') "exams/questions/options/sessions/answers/audience $countsAfter"
$delOther = Api 'DELETE' "/api/exams/$idDraft" $insTwo.token $null
Check 'another INSTRUCTOR cannot delete it' ($delOther.status -in 403, 404 -and (Sql 'exam_db' "select count(*) from exams where id=$idDraft") -eq '1') "status $($delOther.status) $($delOther.code)"
$draftQuestions = Sql 'exam_db' "select count(*) from questions where exam_id=$idDraft"
$draftLinks = Sql 'exam_db' "select count(*) from question_media qm join questions q on q.id=qm.question_id where q.exam_id=$idDraft"
$auditMax2 = [int](Sql 'exam_db' 'select coalesce(max(id),0) from group_audit_log')
$auditCountBefore = [int](Sql 'exam_db' 'select count(*) from group_audit_log')
$del = Api 'DELETE' "/api/exams/$idDraft" $ins.token $null
$left = Sql 'exam_db' "select (select count(*) from exams where id=$idDraft)||'/'||(select count(*) from questions where exam_id=$idDraft)||'/'||(select count(*) from question_options o where not exists (select 1 from questions q where q.id=o.question_id))||'/'||(select count(*) from question_media where media_id='$insMedia')"
Check 'delete of an exam without attempts: 204, exam, questions, options and media links are gone' ($del.status -eq 204 -and $left -eq '0/0/0/0' -and $draftQuestions -eq '2' -and $draftLinks -eq '1') "status $($del.status) $($del.code); left exam/questions/orphan options/media links = $left"
Check 'the media file itself is untouched (still ACTIVE, left to the orphan cleanup)' ((Sql 'exam_db' "select status from media_assets where id='$insMedia'") -eq 'ACTIVE')
Check 'the delete left an audit row and removed none' ((Sql 'exam_db' "select count(*) from group_audit_log where id > $auditMax2 and action='EXAM_DELETED' and details like 'examId=$idDraft %'") -eq '1' -and [int](Sql 'exam_db' 'select count(*) from group_audit_log') -eq $auditCountBefore + 1)
Check 'the deleted exam is 404 afterwards' ((Api 'GET' "/api/exams/$idDraft" $ins.token $null).status -eq 404 -and (Api 'DELETE' "/api/exams/$idDraft" $ins.token $null).status -eq 404)

# ---------- i) media attach permission ----------
$admMedia = Upload $admTwo 'T-b2 admin image'
$admMediaTwo = Upload $admTwo 'T-b2 admin image two'
$otherMedia = Upload $insTwo 'T-b2 other instructor image'
function UpdateBody($mediaId, $withPerDay, $perDay) {
    $body = ExamBody "T-b2-$stamp-media" $start $weekEnd 2 $null $mediaId
    $body['id'] = $idMedia
    if ($withPerDay) { $body['maxAttemptsPerDay'] = $perDay }
    return $body
}
$putAdmin = Api 'PUT' "/api/exams/$idMedia" $admTwo.token (UpdateBody $admMedia $false $null)
Check "ADMIN attaches the own media to someone else's exam: 200" ($putAdmin.status -eq 200 -and $putAdmin.raw -match $admMedia) "status $($putAdmin.status) $($putAdmin.code)"
Check 'update without maxAttemptsPerDay keeps the daily limit (3)' ($putAdmin.data.maxAttemptsPerDay -eq 3) "perDay $($putAdmin.data.maxAttemptsPerDay)"
$putOwner = Api 'PUT' "/api/exams/$idMedia" $ins.token (UpdateBody $admMedia $false $null)
Check 'the owner saves the exam with the media the admin attached: 200' ($putOwner.status -eq 200 -and $putOwner.raw -match $admMedia -and (Sql 'exam_db' "select count(*) from question_media qm join questions q on q.id=qm.question_id where q.exam_id=$idMedia and qm.media_id='$admMedia'") -eq '1') "status $($putOwner.status) $($putOwner.code)"
$putForeign = Api 'PUT' "/api/exams/$idMedia" $ins.token (UpdateBody $admMediaTwo $false $null)
Check "the owner cannot attach someone else's media that is not part of the exam: 400 MEDIA_NOT_ATTACHABLE" ($putForeign.status -eq 400 -and $putForeign.code -eq 'MEDIA_NOT_ATTACHABLE') "status $($putForeign.status) $($putForeign.code)"
Check 'and the refused save changed nothing' ((Sql 'exam_db' "select string_agg(qm.media_id::text, ',') from question_media qm join questions q on q.id=qm.question_id where q.exam_id=$idMedia") -eq $admMedia)
$createForeign = Api 'POST' '/api/exams' $ins.token (ExamBody "T-b2-$stamp-bad5" ([DateTime]::UtcNow.AddMinutes(10)) $weekEnd 1 $null $admMediaTwo)
Check "a new exam cannot carry someone else's media either: 400" ($createForeign.status -eq 400 -and $createForeign.code -eq 'MEDIA_NOT_ATTACHABLE') "status $($createForeign.status) $($createForeign.code)"
$putAny = Api 'PUT' "/api/exams/$idMedia" $admTwo.token (UpdateBody $otherMedia $false $null)
Check "ADMIN attaches any user's media: 200" ($putAny.status -eq 200 -and $putAny.raw -match $otherMedia) "status $($putAny.status) $($putAny.code)"
$putDrop = Api 'PUT' "/api/exams/$idMedia" $ins.token (UpdateBody $null $true $null)
Check 'the owner can drop that media; explicit null removes the daily limit' ($putDrop.status -eq 200 -and $putDrop.raw -notmatch $otherMedia -and -not ($putDrop.data.PSObject.Properties.Name -contains 'maxAttemptsPerDay') -and (Sql 'exam_db' "select count(*) from question_media qm join questions q on q.id=qm.question_id where q.exam_id=$idMedia") -eq '0') "status $($putDrop.status) $($putDrop.code)"
$putOwn = Api 'PUT' "/api/exams/$idMedia" $ins.token (UpdateBody $insMedia $true 2)
Check 'regress: the owner attaches the own media: 200' ($putOwn.status -eq 200 -and $putOwn.raw -match $insMedia -and $putOwn.data.maxAttemptsPerDay -eq 2) "status $($putOwn.status) $($putOwn.code)"

# ---------- e) management list ----------
$adminOnce = Api 'POST' '/api/exams' $adm.token (ExamBody "T-b2-$stamp-adminmain" ([DateTime]::UtcNow.AddMinutes(5)) $weekEnd 1 $null $null)
$manageKeys = 'id', 'title', 'status', 'accessType', 'startTime', 'endTime', 'durationMinutes', 'maxAttempts', 'maxAttemptsPerDay', 'totalQuestions', 'totalPoints', 'sessionCount', 'participantCount', 'createdBy', 'createdAt', 'audienceGroupCount', 'groupIds'
$all = Api 'GET' '/api/exams/manage?size=50' $admTwo.token $null
$expectedAll = Sql 'exam_db' "select count(*) from exams where active = true and status <> 'ARCHIVED'"
Check 'ADMIN: every exam that is not archived' ($all.status -eq 200 -and "$($all.data.totalElements)" -eq $expectedAll -and @($all.data.content).Count -eq [Math]::Min(50, [int]$expectedAll)) "total $($all.data.totalElements), database $expectedAll"
$rowKeys = @(@($all.data.content)[0].PSObject.Properties.Name)
Check 'a row has exactly the 17 listed fields' (-not ($manageKeys | Where-Object { $_ -notin $rowKeys }) -and -not ($rowKeys | Where-Object { $_ -notin $manageKeys })) ($rowKeys -join ', ')
Check 'the list carries no questions and no key' ($all.raw -notmatch '"questions"|isCorrect|explanation')
AllDatesZ 'management list' $all.raw
$mine = Api 'GET' "/api/exams/manage?search=T-b2-$stamp&size=50" $admTwo.token $null
$owners = @(@($mine.data.content) | ForEach-Object { $_.createdBy } | Sort-Object -Unique)
Check "ADMIN sees the exams of the instructor, of the other admin and the own one" ($owners -contains $ins.id -and $owners -contains $adm.id -and $owners -contains $admTwo.id) "$($owners.Count) owners in the T-b2 rows"
$insAll = Api 'GET' '/api/exams/manage?size=50' $ins.token $null
$expectedIns = Sql 'exam_db' "select count(*) from exams where active = true and status <> 'ARCHIVED' and created_by='$($ins.id)'"
Check 'INSTRUCTOR: only the own exams' ("$($insAll.data.totalElements)" -eq $expectedIns -and -not (@($insAll.data.content) | Where-Object { $_.createdBy -ne $ins.id }) -and [int]$expectedIns -ge 4) "total $($insAll.data.totalElements), database $expectedIns"
$insTwoAll = Api 'GET' '/api/exams/manage' $insTwo.token $null
Check 'an INSTRUCTOR without exams gets an empty page; default size 20' ($insTwoAll.status -eq 200 -and $insTwoAll.data.totalElements -eq 0 -and $insTwoAll.data.size -eq 20)
Check 'a STUDENT: 403' ((Api 'GET' '/api/exams/manage' $stuA.token $null).status -eq 403)
$expectedSearch = Sql 'exam_db' "select count(*) from exams where active = true and status <> 'ARCHIVED' and title ilike '%t-B2-$stamp%'"
$searchCase = Api 'GET' "/api/exams/manage?search=t-B2-$stamp&size=50" $admTwo.token $null
Check 'search in the title, ignoring case' ("$($searchCase.data.totalElements)" -eq $expectedSearch -and [int]$expectedSearch -ge 6 -and -not (@($searchCase.data.content) | Where-Object { $_.title -notlike "T-b2-$stamp*" })) "found $($searchCase.data.totalElements), database $expectedSearch"
$pct = Api 'GET' '/api/exams/manage?search=%25%25' $admTwo.token $null
$und = Api 'GET' "/api/exams/manage?search=T_b2_$stamp" $admTwo.token $null
Check '% and _ are searched as characters, not wildcards' ($pct.status -eq 200 -and "$($pct.data.totalElements)" -eq (Sql 'exam_db' "select count(*) from exams where active = true and status <> 'ARCHIVED' and position('%%' in title) > 0") -and $und.status -eq 200 -and $und.data.totalElements -eq 0) "'%%' finds $($pct.data.totalElements), 'T_b2_...' finds $($und.data.totalElements)"
$short = Api 'GET' '/api/exams/manage?search=T' $admTwo.token $null
Check 'a one-character search: 400' ($short.status -eq 400) "status $($short.status) $($short.code)"
$drafts = Api 'GET' '/api/exams/manage?status=DRAFT&size=50' $admTwo.token $null
Check 'status=DRAFT lists only drafts' ("$($drafts.data.totalElements)" -eq (Sql 'exam_db' "select count(*) from exams where active = true and status='DRAFT'") -and -not (@($drafts.data.content) | Where-Object { $_.status -ne 'DRAFT' })) "total $($drafts.data.totalElements)"
$archivedAll = Api 'GET' '/api/exams/manage?status=ARCHIVED&size=50' $admTwo.token $null
# The number of archived exams depends on the data (cleanup removes test exams): compared with the database only
$expectedArchived = Sql 'exam_db' "select count(*) from exams where active = true and status='ARCHIVED'"
Check 'status=ARCHIVED lists the archived exams' ($archivedAll.status -eq 200 -and "$($archivedAll.data.totalElements)" -eq $expectedArchived -and @($archivedAll.data.content).Count -eq [Math]::Min(50, [int]$expectedArchived) -and -not (@($archivedAll.data.content) | Where-Object { $_.status -ne 'ARCHIVED' })) "total $($archivedAll.data.totalElements), database $expectedArchived"
Check 'an unknown status: 400' ((Api 'GET' '/api/exams/manage?status=NOPE' $admTwo.token $null).status -eq 400)
$big = Api 'GET' '/api/exams/manage?size=500' $admTwo.token $null
Check 'size=500 is clamped to 50' ($big.data.size -eq 50 -and @($big.data.content).Count -le 50) "size $($big.data.size)"
$pageOne = Api 'GET' "/api/exams/manage?search=T-b2-$stamp&size=2&page=0" $admTwo.token $null
$pageTwo = Api 'GET' "/api/exams/manage?search=T-b2-$stamp&size=2&page=1" $admTwo.token $null
$firstIds = @($pageOne.data.content | ForEach-Object { $_.id }); $secondIds = @($pageTwo.data.content | ForEach-Object { $_.id })
Check 'paging: two different pages of 2, newest first' ($firstIds.Count -eq 2 -and $secondIds.Count -eq 2 -and -not ($firstIds | Where-Object { $_ -in $secondIds }) -and $firstIds[0] -gt $firstIds[1] -and $firstIds[1] -gt $secondIds[0] -and $pageOne.data.hasNext -eq $true) "page 0: $($firstIds -join ','), page 1: $($secondIds -join ',')"
$figures = @($mine.data.content)
$rowDaily = $figures | Where-Object { $_.id -eq $idDaily }; $rowPar = $figures | Where-Object { $_.id -eq $idPar }; $rowMedia = $figures | Where-Object { $_.id -eq $idMedia }
$dbDaily = Sql 'exam_db' "select count(*)||'/'||count(distinct user_id) from exam_sessions where exam_id=$idDaily"
$dbPar = Sql 'exam_db' "select count(*)||'/'||count(distinct user_id) from exam_sessions where exam_id=$idPar"
Check 'sessionCount and participantCount match the database' ("$($rowDaily.sessionCount)/$($rowDaily.participantCount)" -eq $dbDaily -and $dbDaily -eq '3/1' -and "$($rowPar.sessionCount)/$($rowPar.participantCount)" -eq $dbPar -and $dbPar -eq '3/2' -and $rowMedia.sessionCount -eq 0 -and $rowMedia.participantCount -eq 0) "daily $dbDaily, parallel $dbPar, media exam $($rowMedia.sessionCount)/$($rowMedia.participantCount)"
Check 'totalPoints, totalQuestions and the limits are on the row' ($rowDaily.totalPoints -eq 5 -and $rowDaily.totalQuestions -eq 2 -and $rowDaily.maxAttempts -eq 5 -and $rowDaily.maxAttemptsPerDay -eq 2 -and $rowDaily.accessType -eq 'PUBLIC' -and $rowDaily.status -eq 'ACTIVE') "points $($rowDaily.totalPoints), questions $($rowDaily.totalQuestions)"

# N+1: statements the service ran for one page of 50 rows
Start-Sleep -Seconds 2
$since = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
$nPlus = Api 'GET' '/api/exams/manage?size=50' $admTwo.token $null
Start-Sleep -Seconds 2
# One log line per statement (a multi-line statement starts on that line); the scheduler's own are left out
$statements = @(docker logs --since $since exam-exam-service 2>&1 | ForEach-Object { "$_" } | Where-Object { $_ -match 'Executing SQL statement' -and $_ -notmatch "FROM exams WHERE status = '|status = 'IN_PROGRESS' AND end_time <|media_assets" })
Check "a page of $(@($nPlus.data.content).Count) rows runs 2 statements (count + page), none per row" ($statements.Count -eq 2 -and @($nPlus.data.content).Count -ge 20) "statements in the window: $($statements.Count)"
$myOld = Api 'GET' '/api/exams/my' $ins.token $null
Check 'GET /api/exams/my still answers the plain list with questions' ($myOld.status -eq 200 -and $myOld.raw.TrimStart().StartsWith('[') -and $myOld.raw -match '"questions"') "status $($myOld.status)"

# ---------- scheduler: 70 seconds after the reschedule ----------
$waitScheduler = 75 - ((Get-Date) - $rescheduledAt).TotalSeconds
if ($waitScheduler -gt 0) { Start-Sleep -Seconds ([int][Math]::Ceiling($waitScheduler)) }
Check "more than 70 s after the reschedule the scheduler has not completed the exam again" ((Sql 'exam_db' "select status from exams where id=$idResch") -eq 'ACTIVE' -and (Sql 'exam_db' "select status from exams where id=$idPar") -eq 'ACTIVE') "waited $([int]((Get-Date) - $rescheduledAt).TotalSeconds) s; resched=$(Sql 'exam_db' "select status from exams where id=$idResch"), parallel=$(Sql 'exam_db' "select status from exams where id=$idPar")"
$future = [DateTime]::UtcNow.AddHours(1); $future = $future.AddTicks(-($future.Ticks % [TimeSpan]::TicksPerSecond))
$rsFuture = Api 'POST' "/api/exams/$idResch/reschedule" $ins.token @{ startTime = (UtcText $future); endTime = (UtcText $future.AddHours(2)) }
Check 'reschedule with a start in the future: SCHEDULED, canStart false' ($rsFuture.status -eq 200 -and $rsFuture.data.status -eq 'SCHEDULED' -and $rsFuture.data.canStart -eq $false -and $rsFuture.data.startTime -eq ($future.ToString('yyyy-MM-ddTHH:mm:ss') + '.000Z')) "status $($rsFuture.status), exam $($rsFuture.data.status)"

} finally {
    # The test accounts get the participant role back, whatever happened above: no test account stays
    # ADMIN or INSTRUCTOR (cleanup-test-data.ps1 keeps every ADMIN, so a leftover one would never go)
    $given = @($admTwo.email, $ins.email, $insTwo.email) | Where-Object { $_ -like '*@x.test' }
    if ($given) {
        $back = Sql 'exam_auth' "update users set role='STUDENT' where role <> 'STUDENT' and email in ('$($given -join "','")')"
        Write-Output "roles given back: $back"
    }
}

# ---------- summary ----------
$failed = @($script:results | Where-Object { -not $_.ok })
Write-Output ''
Write-Output "TOTAL $($script:results.Count)  PASS $($script:results.Count - $failed.Count)  FAIL $($failed.Count)"
Write-Output "exam ids: daily=$idDaily parallel=$idPar resched=$idResch once=$idOnce media=$idMedia draft(deleted)=$idDraft adminmain=$($adminOnce.data.id); stamp=$stamp"
Write-Output "since=$since"
