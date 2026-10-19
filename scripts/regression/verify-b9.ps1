# Live verification of fix/b9-integrity-profile (B9): an exam with attempts keeps its questions, answer key,
# points and duration (409 EXAM_HAS_SESSIONS), the reschedule rule for finished exams with attempts, and the
# own account (GET /auth/me, PUT /auth/profile, POST /auth/change-password with its rate limit).
# Development stack only (Mailpit for the codes); never against the public mode. Creates T-b9-<stamp>-*
# exams and be-b9-*-<stamp>@x.test accounts; one of them is INSTRUCTOR for the run and STUDENT again in the
# finally block. Admin credentials are read from F:\exam-system\.env into variables only; nothing secret is printed.
# Edit and start at the same moment cannot be driven through the API (an edit needs DRAFT or SCHEDULED, a
# start ACTIVE): that is covered by ExamEditLockConcurrencyTest on a real PostgreSQL in mvn clean package.
$ErrorActionPreference = 'Continue'
[Threading.Thread]::CurrentThread.CurrentCulture = [Globalization.CultureInfo]::InvariantCulture
Set-Location F:\exam-system
$GW = 'http://127.0.0.1:8080'
. "$PSScriptRoot\_common.ps1"
Wait-Gateway $GW
$MAIL = 'http://127.0.0.1:8025'
$stamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString('x')
$T = "T-b9-$stamp"
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
    $email = "be-b9-$name-$stamp@x.test"
    $r = AuthCall '/auth/register' @{ email = $email; password = $password; firstName = "Nb9${stamp}$name"; lastName = "L$name" }
    if ($r.status -ne 201) { throw "register $name answered $($r.status)" }
    $v = AuthCall '/auth/verify-otp' @{ email = $email; code = (MailedCode $email) }
    if ($v.status -ne 200) { throw "verify $name answered $($v.status)" }
    return @{ email = $email; name = $name; id = $v.data.user.id; token = $v.data.accessToken; refresh = $v.data.refreshToken }
}
function Login($email, $pw) { (AuthCall '/auth/login' @{ email = $email; password = $pw }) }
function Utc([DateTime]$t) { $t.ToString('yyyy-MM-ddTHH:mm:ss') }
function Question($text, $points) {
    @{ questionText = $text; questionType = 'MULTIPLE_CHOICE'; points = $points; orderIndex = 0; allowMultipleAnswers = $false
       options = @(@{ optionText = 'right'; isCorrect = $true; orderIndex = 0 }, @{ optionText = 'wrong'; isCorrect = $false; orderIndex = 1 }) }
}
function ExamBody($title, [DateTime]$start, [DateTime]$end, $duration, $questions) {
    @{ title = $title; description = 'B9 verification'; durationMinutes = $duration; startTime = (Utc $start); endTime = (Utc $end)
       shuffleQuestions = $false; shuffleOptions = $false; showResultsImmediately = $true; maxAttempts = 3; passingScore = 50
       questions = $questions }
}
function ExamShape($examId) {
    Sql 'exam_db' "select e.duration_minutes||'|'||e.passing_score||'|'||(select string_agg(q.question_text||':'||q.points||':'||(select string_agg(o.option_text||'='||o.is_correct, ',' order by o.order_index) from question_options o where o.question_id=q.id), ';' order by q.order_index) from questions q where q.exam_id=e.id) from exams e where e.id=$examId"
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
$p1 = Register 'p1'
$p2 = Register 'p2'
# Every role given below is taken back in the finally block before the summary
try {
Sql 'exam_auth' "update users set role='INSTRUCTOR' where email='$($ins.email)'" | Out-Null
$ins.token = (Login $ins.email $password).data.accessToken
Write-Output "run $stamp; instructor be-b9-ins-$stamp@x.test, participants p1, p2"

# ---------- 1. an exam without attempts is edited as before ----------
$now = [DateTime]::UtcNow
$draft = Api 'POST' '/api/exams' $ins.token (ExamBody "$T-draft" $now.AddDays(1) $now.AddDays(2) 30 @((Question "$T q1" 2)))
$draftId = $draft.data.id
$edited = Api 'PUT' "/api/exams/$draftId" $ins.token (ExamBody "$T-draft" $now.AddDays(1) $now.AddDays(2) 45 @((Question "$T q1 edited" 3), (Question "$T q2" 1)))
Check 'an exam without attempts: PUT 200, questions, points and duration replaced' ($draft.status -eq 201 -and $edited.status -eq 200 -and (ExamShape $draftId) -like "45|*$T q1 edited:3.00:*;$T q2:1.00:*") "create $(Answer $draft), put $(Answer $edited)"

# ---------- 2. an exam with attempts keeps what decided their results ----------
# POST /api/exams refuses a start in the past (@Future): New-ActiveExam creates ahead, publishes and reschedules to an open window
$live = New-ActiveExam $ins.token { param($s, $e) ExamBody "$T-live" $s $e 30 @((Question "$T a1" 2), (Question "$T a2" 3)) }
$liveId = $live.data.id
$published = $live.published
$started = if ($liveId) { Api 'POST' "/api/exams/$liveId/start" $p1.token @{} } else { @{ status = 0; code = 'no exam'; data = $null } }
Check 'setup: a published exam with one attempt' ($live.status -eq 201 -and $published.status -eq 200 -and $live.rescheduled.status -eq 200 -and $started.status -eq 200 -and $started.data.sessionId) "create $(Answer $live), publish $(Answer $published), open window $(Answer $live.rescheduled), start $(Answer $started)"
if (-not $started.data.sessionId) { throw "setup failed, the checks below need the exam with an attempt" }
$shapeBefore = ExamShape $liveId
# Back to SCHEDULED (an exam with attempts may be postponed): the status would allow editing, the attempts do not
$postponed = Api 'POST' "/api/exams/$liveId/reschedule" $ins.token @{ startTime = (Utc $now.AddDays(1)); endTime = (Utc $now.AddDays(2)) }
Check 'postponing the running exam with attempts: 200 SCHEDULED (unchanged rule)' ($postponed.status -eq 200 -and $postponed.data.status -eq 'SCHEDULED') (Answer $postponed)
$changes = [ordered]@{
    'question text'   = @((Question "$T a1 CHANGED" 2), (Question "$T a2" 3))
    'points'          = @((Question "$T a1" 5), (Question "$T a2" 3))
    'answer key'      = @(@{ questionText = "$T a1"; questionType = 'MULTIPLE_CHOICE'; points = 2; orderIndex = 0; allowMultipleAnswers = $false
                             options = @(@{ optionText = 'right'; isCorrect = $false; orderIndex = 0 }, @{ optionText = 'wrong'; isCorrect = $true; orderIndex = 1 }) },
                          (Question "$T a2" 3))
    'question order'  = @((Question "$T a2" 3), (Question "$T a1" 2))
    'a question removed' = @((Question "$T a1" 2))
    'a question added'   = @((Question "$T a1" 2), (Question "$T a2" 3), (Question "$T a3" 1))
}
foreach ($kind in $changes.Keys) {
    $r = Api 'PUT' "/api/exams/$liveId" $ins.token (ExamBody "$T-live" $now.AddDays(1) $now.AddDays(2) 30 $changes[$kind])
    Check "an exam with attempts, changed ${kind}: 409 EXAM_HAS_SESSIONS" ($r.status -eq 409 -and $r.code -eq 'EXAM_HAS_SESSIONS') (Answer $r)
}
$durationOnly = Api 'PUT' "/api/exams/$liveId" $ins.token (ExamBody "$T-live" $now.AddDays(1) $now.AddDays(2) 90 @((Question "$T a1" 2), (Question "$T a2" 3)))
Check 'an exam with attempts, only the duration changed: 409 EXAM_HAS_SESSIONS' ($durationOnly.status -eq 409 -and $durationOnly.code -eq 'EXAM_HAS_SESSIONS') (Answer $durationOnly)
$adminTry = Api 'PUT' "/api/exams/$liveId" $adm.token (ExamBody "$T-live" $now.AddDays(1) $now.AddDays(2) 30 @((Question "$T a1" 9)))
Check 'an ADMIN gets the same 409' ($adminTry.status -eq 409 -and $adminTry.code -eq 'EXAM_HAS_SESSIONS') (Answer $adminTry)
Check 'after all of that the questions, points, answer key and duration are exactly as before' ((ExamShape $liveId) -eq $shapeBefore) $shapeBefore
$deleted = Api 'DELETE' "/api/exams/$liveId" $ins.token $null
Check 'delete of the exam with attempts: 409 EXAM_CANNOT_DELETE (unchanged)' ($deleted.status -eq 409 -and $deleted.code -eq 'EXAM_CANNOT_DELETE') (Answer $deleted)

# ---------- 3. reschedule of a finished or archived exam with attempts ----------
$reopenNow = Api 'POST' "/api/exams/$liveId/reschedule" $ins.token @{ startTime = (Utc $now.AddMinutes(-1)); endTime = (Utc $now.AddHours(3)) }
$finish = Api 'POST' "/api/exams/session/$($started.data.sessionId)/submit" $p1.token @{}
$archived = Api 'POST' "/api/exams/$liveId/archive" $ins.token $null
$later = Api 'POST' "/api/exams/$liveId/reschedule" $ins.token @{ startTime = (Utc $now.AddDays(1)); endTime = (Utc $now.AddDays(2)) }
Check 'an archived exam with attempts to a later start: 409 EXAM_HAS_SESSIONS, still ARCHIVED' ($reopenNow.status -eq 200 -and $archived.status -eq 200 -and $later.status -eq 409 -and $later.code -eq 'EXAM_HAS_SESSIONS' -and (Sql 'exam_db' "select status from exams where id=$liveId") -eq 'ARCHIVED') "reopen $(Answer $reopenNow), submit $(Answer $finish), archive $(Answer $archived), later $(Answer $later)"
Sql 'exam_db' "update exams set status='COMPLETED' where id=$liveId and title like 'T-b9-%'" | Out-Null
$laterCompleted = Api 'POST' "/api/exams/$liveId/reschedule" $ins.token @{ startTime = (Utc $now.AddDays(1)); endTime = (Utc $now.AddDays(2)) }
Check 'a completed exam with attempts to a later start: 409 EXAM_HAS_SESSIONS' ($laterCompleted.status -eq 409 -and $laterCompleted.code -eq 'EXAM_HAS_SESSIONS') (Answer $laterCompleted)
$atOnce = Api 'POST' "/api/exams/$liveId/reschedule" $ins.token @{ startTime = (Utc $now.AddMinutes(-1)); endTime = (Utc $now.AddDays(1)) }
Check 'reopening it at once: 200 ACTIVE, the attempt kept' ($atOnce.status -eq 200 -and $atOnce.data.status -eq 'ACTIVE' -and (Sql 'exam_db' "select count(*) from exam_sessions where exam_id=$liveId") -eq '1') (Answer $atOnce)
$empty = New-ActiveExam $ins.token { param($s, $e) ExamBody "$T-empty" $s $e 30 @((Question "$T e1" 1)) }
$null = Api 'POST' "/api/exams/$($empty.data.id)/archive" $ins.token $null
$emptyLater = Api 'POST' "/api/exams/$($empty.data.id)/reschedule" $ins.token @{ startTime = (Utc $now.AddDays(1)); endTime = (Utc $now.AddDays(2)) }
Check 'an archived exam without attempts to a later start: 200 SCHEDULED (unchanged)' ($emptyLater.status -eq 200 -and $emptyLater.data.status -eq 'SCHEDULED') (Answer $emptyLater)

# ---------- 4. the own account ----------
Check 'GET /auth/me without a token: 401' ((Api 'GET' '/auth/me' $null $null).status -eq 401)
$me = Api 'GET' '/auth/me' $p2.token $null
Check 'GET /auth/me: the user of the token and nothing secret' ($me.status -eq 200 -and $me.data.id -eq $p2.id -and $me.data.email -eq $p2.email -and $me.data.role -eq 'STUDENT' -and $me.data.emailVerified -eq $true -and $me.raw -notmatch 'password') "status $($me.status), $($me.data.email)"
$prof = Api 'PUT' '/auth/profile' $p2.token @{ firstName = ' Leyla '; lastName = 'Mammadova'; phone = '+994 50 123 45 67' }
Check 'PUT /auth/profile: names trimmed, phone stored, audit row' ($prof.status -eq 200 -and $prof.data.firstName -eq 'Leyla' -and $prof.data.phoneNumber -eq '+994501234567' -and (Sql 'exam_auth' "select phone_number from users where id='$($p2.id)'") -eq '+994501234567' -and (Sql 'exam_auth' "select count(*) from admin_audit_log where action='PROFILE_UPDATED' and target_user_id='$($p2.id)'") -eq '1') (Answer $prof)
$alias = Api 'PUT' '/auth/profile' $p2.token @{ firstName = 'Leyla'; lastName = 'Mammadova'; phoneNumber = '' }
Check 'PUT /auth/profile: phoneNumber is accepted too, empty removes the phone' ($alias.status -eq 200 -and -not $alias.data.phoneNumber) (Answer $alias)
$badProfile = Api 'PUT' '/auth/profile' $p2.token @{ firstName = 'L'; lastName = 'M'; phone = 'abc' }
Check 'PUT /auth/profile with a one-letter name and a bad phone: 400, nothing changed' ($badProfile.status -eq 400 -and (Api 'GET' '/auth/me' $p2.token $null).data.firstName -eq 'Leyla') (Answer $badProfile)
Check 'PUT /auth/profile cannot change the email or the role' ((Api 'PUT' '/auth/profile' $p2.token @{ firstName = 'Leyla'; lastName = 'Mammadova'; email = 'other@x.test'; role = 'ADMIN' }).status -eq 200 -and (Sql 'exam_auth' "select email||'|'||role from users where id='$($p2.id)'") -eq "$($p2.email)|STUDENT")

$wrong = Api 'POST' '/auth/change-password' $p2.token @{ currentPassword = 'Wrong1pass'; newPassword = 'Brand2Newpass' }
Check 'change-password with a wrong current password: 400 PASSWORD_CHANGE_REJECTED' ($wrong.status -eq 400 -and $wrong.code -eq 'PASSWORD_CHANGE_REJECTED') (Answer $wrong)
$weak = Api 'POST' '/auth/change-password' $p2.token @{ currentPassword = $password; newPassword = 'weak' }
Check 'change-password with a weak new password: 400 VALIDATION_ERROR' ($weak.status -eq 400 -and $weak.code -eq 'VALIDATION_ERROR') (Answer $weak)
$same = Api 'POST' '/auth/change-password' $p2.token @{ currentPassword = $password; newPassword = $password }
Check 'change-password to the same password: 400 PASSWORD_UNCHANGED' ($same.status -eq 400 -and $same.code -eq 'PASSWORD_UNCHANGED') (Answer $same)
$newPassword = 'Nn8!' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
Start-Sleep -Seconds 16   # the password bucket refills (one request every 5 seconds)
$changed = Api 'POST' '/auth/change-password' $p2.token @{ currentPassword = $password; newPassword = $newPassword }
$oldRefresh = Api 'POST' '/auth/refresh' $null @{ refreshToken = $p2.refresh }
Check 'change-password: 200 with a new token pair; the old refresh token is revoked; audit row' ($changed.status -eq 200 -and $changed.data.accessToken -and $changed.data.refreshToken -and $oldRefresh.status -eq 401 -and (Sql 'exam_auth' "select count(*) from admin_audit_log where action='PASSWORD_CHANGED' and target_user_id='$($p2.id)'") -eq '1') "change $(Answer $changed), old refresh $(Answer $oldRefresh)"
Check 'the new password signs in, the old one does not' ((Login $p2.email $newPassword).status -eq 200 -and (Login $p2.email $password).status -eq 401)
Check 'no password reached the logs of auth-service' (-not (docker logs --since 10m exam-auth-service 2>&1 | Select-String -SimpleMatch $newPassword))

# The bucket of the route: 5 at once, then one every 5 seconds per address
$burst = @(1..8 | ForEach-Object { (Api 'POST' '/auth/change-password' $p1.token @{ currentPassword = 'Wrong1pass'; newPassword = 'Brand2Newpass' }).status })
Check 'change-password has its own rate limit: 429 after a few quick tries, never a 5xx' (($burst -contains 429) -and -not ($burst | Where-Object { $_ -ge 500 }) -and (Api 'GET' '/auth/me' $p1.token $null).status -eq 200) "statuses: $(($burst | Group-Object | ForEach-Object { "$($_.Name)x$($_.Count)" }) -join ' ')"
} finally {
    # The test account gets the participant role back, whatever happened above: no test account stays INSTRUCTOR
    if ($ins.email -like '*@x.test') {
        $back = Sql 'exam_auth' "update users set role='STUDENT' where role <> 'STUDENT' and email='$($ins.email)'"
        Write-Output "roles given back: $back"
    }
}

$failed = @($script:results | Where-Object { -not $_.ok })
Write-Output ''
Write-Output "TOTAL $($script:results.Count)  PASS $($script:results.Count - $failed.Count)  FAIL $($failed.Count)"
Write-Output "stamp=$stamp; exams $T-*; accounts be-b9-*-$stamp@x.test"
