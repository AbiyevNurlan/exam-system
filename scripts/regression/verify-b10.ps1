# Live verification of feat/b10-participant-code-stats (B10): the 7-digit participant code (new and existing
# accounts, uniqueness, /auth/me, search by code), GET /api/me/stats against attempts with known scores,
# PATCH /api/exams/{id}/meta on an exam with attempts, and the E.164 profile phone.
# Development stack only (Mailpit for the codes); never against the public mode. Creates T-b10-<stamp>-*
# exams and be-b10-*-<stamp>@x.test accounts; one of them is INSTRUCTOR for the run and STUDENT again in the
# finally block. Admin credentials are read from F:\exam-system\.env into variables only; nothing secret is printed.
$ErrorActionPreference = 'Continue'
[Threading.Thread]::CurrentThread.CurrentCulture = [Globalization.CultureInfo]::InvariantCulture
Set-Location F:\exam-system
$GW = 'http://127.0.0.1:8080'
. "$PSScriptRoot\_common.ps1"
Wait-Gateway $GW
$MAIL = 'http://127.0.0.1:8025'
$stamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString('x')
$T = "T-b10-$stamp"
$script:results = @()

function Check($name, $ok, $detail = '') {
    $script:results += [pscustomobject]@{ name = $name; ok = [bool]$ok }
    $line = $(if ($ok) { 'PASS' } else { 'FAIL' }) + "  $name"
    if ($detail -ne '') { $line += "  ($detail)" }
    Write-Output $line
}
function Api($method, $path, $token, $body) {
    $headers = @{}
    if ($token) { $headers['Authorization'] = "Bearer $token" }
    $params = @{ Uri = "$GW$path"; Method = $method; Headers = $headers; UseBasicParsing = $true; TimeoutSec = 60 }
    if ($null -ne $body) {
        $json = if ($body -is [string]) { $body } else { $body | ConvertTo-Json -Depth 10 }
        $params['Body'] = [Text.Encoding]::UTF8.GetBytes($json); $params['ContentType'] = 'application/json'
    }
    try {
        $r = Invoke-WebRequest @params
        # Always UTF-8 from the raw bytes: without a charset in the content type PowerShell 5.1 decodes as ISO-8859-1
        $raw = [Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
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
    $data = $json; $code = $null
    if ($json -and ($json.PSObject.Properties.Name -contains 'data')) { $data = $json.data }
    if ($json -and $json.error) { $code = $json.error.code }
    return @{ status = $status; raw = $raw; json = $json; data = $data; code = $code }
}
function Sql($db, $query) { docker exec exam-postgres psql -U postgres -d $db -Atc $query }
function Answer($r) { "$($r.status) $($r.code)" }
function MailedCode($email) {
    for ($i = 0; $i -lt 40; $i++) {
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
function AuthCall($path, $body) {
    for ($try = 0; $try -lt 10; $try++) {
        $r = Api 'POST' $path $null $body
        if ($r.status -ne 429) { return $r }
        Start-Sleep -Seconds 6
    }
    return $r
}
function Register($name) {
    $email = "be-b10-$name-$stamp@x.test"
    $r = AuthCall '/auth/register' @{ email = $email; password = $password; firstName = "Nb10${stamp}$name"; lastName = "L$name" }
    if ($r.status -ne 201) { throw "register $name answered $($r.status)" }
    $v = AuthCall '/auth/verify-otp' @{ email = $email; code = (MailedCode $email) }
    if ($v.status -ne 200) { throw "verify $name answered $($v.status)" }
    return @{ email = $email; name = $name; id = $v.data.user.id; token = $v.data.accessToken }
}
function Login($email, $pw) { (AuthCall '/auth/login' @{ email = $email; password = $pw }) }
function Utc([DateTime]$t) { $t.ToString('yyyy-MM-ddTHH:mm:ss') }
function Mc($text) {
    @{ questionText = $text; questionType = 'MULTIPLE_CHOICE'; points = 1; orderIndex = 0; allowMultipleAnswers = $false
       options = @(@{ optionText = 'right'; isCorrect = $true; orderIndex = 0 }, @{ optionText = 'wrong'; isCorrect = $false; orderIndex = 1 }) }
}
function Open($text) { @{ questionText = $text; questionType = 'OPEN_ENDED'; points = 2; orderIndex = 0; allowMultipleAnswers = $false } }
function ExamBody($title, [DateTime]$start, [DateTime]$end, [bool]$immediate, $questions) {
    @{ title = $title; description = 'B10 verification'; durationMinutes = 30; startTime = (Utc $start); endTime = (Utc $end)
       shuffleQuestions = $false; shuffleOptions = $false; showResultsImmediately = $immediate; maxAttempts = 3; passingScore = 50
       questions = $questions }
}
# One attempt: the first $correct multiple-choice questions answered right, the rest wrong; open questions get a text
function Attempt($examId, $who, [int]$correct) {
    $start = Api 'POST' "/api/exams/$examId/start" $who.token @{}
    $sid = $start.data.sessionId
    $n = 0
    foreach ($q in @($start.data.questions)) {
        if ($q.questionType -eq 'OPEN_ENDED') {
            $null = Api 'POST' "/api/exams/session/$sid/answer" $who.token @{ questionId = $q.id; textAnswer = 'an answer for a grader' }
            continue
        }
        $right = Sql 'exam_db' "select id from question_options where question_id=$($q.id) and is_correct"
        $wrong = Sql 'exam_db' "select id from question_options where question_id=$($q.id) and not is_correct limit 1"
        $pick = if ($n -lt $correct) { $right } else { $wrong }
        $null = Api 'POST' "/api/exams/session/$sid/answer" $who.token @{ questionId = $q.id; selectedOptionIds = @([long]$pick) }
        $n++
    }
    $submit = Api 'POST' "/api/exams/session/$sid/submit" $who.token @{}
    return @{ start = $start; submit = $submit; sid = $sid }
}

# ---------- setup ----------
$adminEmail = $null; $adminPassword = $null
foreach ($line in Get-Content F:\exam-system\.env) {
    if ($line -match '^ADMIN_EMAIL=(.*)$') { $adminEmail = $Matches[1].Trim() }
    if ($line -match '^ADMIN_PASSWORD=(.*)$') { $adminPassword = $Matches[1].Trim() }
}
$mainLogin = Login $adminEmail $adminPassword
if ($mainLogin.status -ne 200) { throw "admin login answered $($mainLogin.status)" }
$adm = @{ id = $mainLogin.data.user.id; token = $mainLogin.data.accessToken }

$ins = Register 'ins'
$other = Register 'other'
$p1 = Register 'p1'
$p2 = Register 'p2'
# Every role given below is taken back in the finally block before the summary
try {
Sql 'exam_auth' "update users set role='INSTRUCTOR' where email in ('$($ins.email)','$($other.email)')" | Out-Null
$ins.token = (Login $ins.email $password).data.accessToken
$other.token = (Login $other.email $password).data.accessToken
Write-Output "run $stamp; instructors ins, other; participants p1, p2"

# ---------- 1. participant code ----------
$me1 = Api 'GET' '/auth/me' $p1.token $null
$me2 = Api 'GET' '/auth/me' $p2.token $null
$code1 = $me1.data.participantCode
Check 'a new account has a 7-digit participant code in GET /auth/me' ($me1.status -eq 200 -and "$code1" -match '^[1-9]\d{6}$' -and "$($me2.data.participantCode)" -match '^[1-9]\d{6}$' -and $code1 -ne $me2.data.participantCode) "p1 $code1, p2 $($me2.data.participantCode)"
Check 'the code in the database is the one shown' ((Sql 'exam_auth' "select participant_code from users where id='$($p1.id)'") -eq "$code1")
$codes = Sql 'exam_auth' "select count(*) || '|' || count(participant_code) || '|' || count(distinct participant_code) || '|' || count(*) filter (where participant_code not between 1000000 and 9999999) from users"
$parts = $codes -split '\|'
Check 'every account (every role, also the ones from before B10) has a code, all different, all 7 digits' ($parts[0] -eq $parts[1] -and $parts[1] -eq $parts[2] -and $parts[3] -eq '0') "accounts $($parts[0]), with code $($parts[1]), distinct $($parts[2])"
Check 'admins and instructors have codes too' ((Sql 'exam_auth' "select count(*) from users where role <> 'STUDENT' and participant_code is null") -eq '0')
Check 'the column is NOT NULL and UNIQUE' ((Sql 'exam_auth' "select is_nullable from information_schema.columns where table_name='users' and column_name='participant_code'") -eq 'NO' -and (Sql 'exam_auth' "select count(*) from pg_constraint where conname='uq_users_participant_code'") -eq '1')
$found = Api 'GET' "/api/admin/participants?q=$code1" $adm.token $null
Check 'the admin participant list finds p1 by the exact code, and shows it' ($found.status -eq 200 -and $found.data.totalElements -eq 1 -and @($found.data.content)[0].participantCode -eq $code1 -and @($found.data.content)[0].email -eq $p1.email) "total $($found.data.totalElements)"
$partial = Api 'GET' "/api/admin/participants?q=$("$code1".Substring(0, 6))" $adm.token $null
Check 'six digits of the code are not a code search' (-not (@($partial.data.content) | Where-Object { $_.participantCode -eq $code1 -and "$($_.email)" -notlike "*$("$code1".Substring(0, 6))*" })) "total $($partial.data.totalElements)"

# ---------- 2. statistics ----------
Check 'GET /api/me/stats without a token: 401' ((Api 'GET' '/api/me/stats' $null $null).status -eq 401)
$zero = Api 'GET' '/api/me/stats' $p2.token $null
Check 'stats with no attempt: zeros and nulls' ($zero.status -eq 200 -and $zero.data.examsTaken -eq 0 -and $zero.data.examsGraded -eq 0 -and $null -eq $zero.data.averagePercent -and $null -eq $zero.data.bestPercent -and $null -eq $zero.data.lastResult -and $zero.data.pendingGradingCount -eq 0) $zero.raw

$now = [DateTime]::UtcNow
# POST /api/exams refuses a start in the past (@Future): New-ActiveExam creates ahead, publishes and reschedules to an open window
$shown = New-ActiveExam $ins.token { param($s, $e) ExamBody "$T-shown" $s $e $true @((Mc "$T s1"), (Mc "$T s2"), (Mc "$T s3"), (Mc "$T s4")) }
$hidden = New-ActiveExam $ins.token { param($s, $e) ExamBody "$T-hidden" $s $e $false @((Mc "$T h1"), (Mc "$T h2")) }
$essay = New-ActiveExam $ins.token { param($s, $e) ExamBody "$T-essay" $s $e $true @((Open "$T e1")) }
foreach ($e in $shown, $hidden, $essay) {
    if ($e.status -ne 201 -or $e.published.status -ne 200 -or $e.rescheduled.status -ne 200) { throw "setup: an active exam was not made (create $(Answer $e), publish $(Answer $e.published), open window $(Answer $e.rescheduled))" }
}
$a1 = Attempt $shown.data.id $p1 1      # 1 of 4 right: 25 %
$a2 = Attempt $shown.data.id $p1 3      # 3 of 4 right: 75 %, the best of this exam
$a3 = Attempt $hidden.data.id $p1 2     # 100 %, but withheld until the exam ends
$a4 = Attempt $essay.data.id $p1 0      # waits for a grader
Check 'setup: four submitted attempts' ($a1.submit.status -eq 200 -and $a2.submit.status -eq 200 -and $a3.submit.status -eq 200 -and $a4.submit.status -eq 200) "$(Answer $a1.submit) $(Answer $a2.submit) $(Answer $a3.submit) $(Answer $a4.submit)"
$stats = Api 'GET' '/api/me/stats' $p1.token $null
$expected = Sql 'exam_db' "select max(percentage) from exam_sessions where exam_id=$($shown.data.id) and user_id='$($p1.id)'"
Check 'stats: 3 exams taken, 1 graded (the withheld and the waiting ones are not), best attempt 75 %' ($stats.status -eq 200 -and $stats.data.examsTaken -eq 3 -and $stats.data.examsGraded -eq 1 -and [decimal]$stats.data.bestPercent -eq 75 -and [decimal]$stats.data.averagePercent -eq 75 -and [decimal]$expected -eq 75) $stats.raw
Check 'stats: the last visible result is the shown exam, 75 %, with its submit time in UTC' ($stats.data.lastResult.examId -eq $shown.data.id -and [decimal]$stats.data.lastResult.percentage -eq 75 -and "$($stats.data.lastResult.submittedAt)" -match 'Z$') "$($stats.data.lastResult.examTitle) $($stats.data.lastResult.submittedAt)"
Check 'stats: one attempt waits for a grader' ($stats.data.pendingGradingCount -eq 1)
Check 'stats of p2 are still empty (never the figures of someone else)' ((Api 'GET' '/api/me/stats' $p2.token $null).data.examsTaken -eq 0)

# ---------- 3. title and description of an exam with attempts ----------
$shapeBefore = Sql 'exam_db' "select e.duration_minutes || '|' || (select string_agg(q.question_text || ':' || q.points, ';' order by q.order_index) from questions q where q.exam_id = e.id) || '|' || (select string_agg(s.percentage::text, ',' order by s.id) from exam_sessions s where s.exam_id = e.id) from exams e where e.id = $($shown.data.id)"
$meta = Api 'PATCH' "/api/exams/$($shown.data.id)/meta" $ins.token @{ title = "$T-shown renamed"; description = 'new text' }
Check 'PATCH meta on an exam with attempts: 200, title and description changed' ($meta.status -eq 200 -and (Sql 'exam_db' "select title || '|' || description from exams where id=$($shown.data.id)") -eq "$T-shown renamed|new text") (Answer $meta)
$metaMore = Api 'PATCH' "/api/exams/$($shown.data.id)/meta" $ins.token @{ title = "$T-x"; durationMinutes = 90 }
Check 'PATCH meta with another field (durationMinutes): 400, nothing changed' ($metaMore.status -eq 400 -and (Sql 'exam_db' "select title from exams where id=$($shown.data.id)") -eq "$T-shown renamed") (Answer $metaMore)
$metaOther = Api 'PATCH' "/api/exams/$($shown.data.id)/meta" $other.token @{ title = "$T-hijack" }
Check 'PATCH meta by another instructor: 404 EXAM_NOT_FOUND (B13: the same as a missing exam)' ($metaOther.status -eq 404 -and $metaOther.code -eq 'EXAM_NOT_FOUND') (Answer $metaOther)
Check 'PATCH meta by a participant: 403' ((Api 'PATCH' "/api/exams/$($shown.data.id)/meta" $p1.token @{ title = "$T-p" }).status -eq 403)
$shapeAfter = Sql 'exam_db' "select e.duration_minutes || '|' || (select string_agg(q.question_text || ':' || q.points, ';' order by q.order_index) from questions q where q.exam_id = e.id) || '|' || (select string_agg(s.percentage::text, ',' order by s.id) from exam_sessions s where s.exam_id = e.id) from exams e where e.id = $($shown.data.id)"
Check 'questions, points, duration and results are as before' ($shapeAfter -eq $shapeBefore) $shapeAfter
Check 'one EXAM_META_UPDATED audit row' ((Sql 'exam_db' "select count(*) from group_audit_log where action='EXAM_META_UPDATED' and details like 'examId=$($shown.data.id) %'") -eq '1')
Check 'the stats name the renamed exam' ((Api 'GET' '/api/me/stats' $p1.token $null).data.lastResult.examTitle -eq "$T-shown renamed")

# ---------- 4. phone ----------
$phone = Api 'PUT' '/auth/profile' $p2.token @{ firstName = 'Leyla'; lastName = 'Mammadova'; phone = '050 123 45 67' }
Check 'profile phone is stored in E.164' ($phone.status -eq 200 -and $phone.data.phoneNumber -eq '+994501234567' -and (Sql 'exam_auth' "select phone_number from users where id='$($p2.id)'") -eq '+994501234567') (Answer $phone)
$foreign = Api 'PUT' '/auth/profile' $p2.token @{ firstName = 'Leyla'; lastName = 'Mammadova'; phone = '+7 912 345 67 89' }
Check 'a phone of another country: 400, the stored one stays' ($foreign.status -eq 400 -and (Sql 'exam_auth' "select phone_number from users where id='$($p2.id)'") -eq '+994501234567') (Answer $foreign)
} finally {
    # The test accounts get the participant role back, whatever happened above: no test account stays INSTRUCTOR
    $given = @($ins.email, $other.email) | Where-Object { $_ -like '*@x.test' }
    if ($given) {
        $back = Sql 'exam_auth' "update users set role='STUDENT' where role <> 'STUDENT' and email in ('$($given -join "','")')"
        Write-Output "roles given back: $back"
    }
}

$failed = @($script:results | Where-Object { -not $_.ok })
Write-Output ''
Write-Output "TOTAL $($script:results.Count)  PASS $($script:results.Count - $failed.Count)  FAIL $($failed.Count)"
Write-Output "stamp=$stamp; exams $T-*; accounts be-b10-*-$stamp@x.test"
Write-Output 'cleanup (dry run, nothing is deleted): powershell -NoProfile -ExecutionPolicy Bypass -File scripts\cleanup-test-data.ps1'
