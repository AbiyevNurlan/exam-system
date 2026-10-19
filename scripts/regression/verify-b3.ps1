# Live verification of feat/results-grading (B3) against the running stack (gateway 8080).
# Admin credentials are read from F:\exam-system\.env into variables only; nothing secret is printed.
# Works only with the "T-b3-*" exams and @x.test accounts it creates itself; deletes nothing.
$ErrorActionPreference = 'Continue'
Add-Type -AssemblyName System.Net.Http
$GW = 'http://127.0.0.1:8080'
. "$PSScriptRoot\_common.ps1"
Wait-Gateway $GW
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
    if ($null -ne $body) {
        $jsonBody = if ($body -is [string]) { $body } else { $body | ConvertTo-Json -Depth 10 }
        $params['Body'] = [Text.Encoding]::UTF8.GetBytes($jsonBody); $params['ContentType'] = 'application/json'
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
# A JSON array body, also for one element (ConvertTo-Json would unwrap it)
function Grades($items) { '[' + ((@($items) | ForEach-Object { $_ | ConvertTo-Json -Compress }) -join ',') + ']' }
function Has($object, $name) { $object -and ($object.PSObject.Properties.Name -contains $name) }

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
function Register($name, $first, $last) {
    $email = "be-b3-$name-$stamp@x.test"
    $r = Api 'POST' '/auth/register' $null @{ email = $email; password = $password; firstName = $first; lastName = $last }
    if ($r.status -ne 201) { throw "register $name answered $($r.status)" }
    $v = Api 'POST' '/auth/verify-otp' $null @{ email = $email; code = (MailedCode $email) }
    if ($v.status -ne 200) { throw "verify $name answered $($v.status)" }
    return @{ email = $email; id = $v.data.user.id; token = $v.data.accessToken; name = "$first $last"; first = $first; last = $last }
}
function Login($email, $pw) { (Api 'POST' '/auth/login' $null @{ email = $email; password = $pw }) }
function UtcText([DateTime]$utc) { $utc.ToString('yyyy-MM-ddTHH:mm:ss') }

function ParallelPut($path, $token, $bodies) {
    $client = New-Object System.Net.Http.HttpClient
    $client.DefaultRequestHeaders.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $token)
    $tasks = @($bodies | ForEach-Object { $client.PutAsync("$GW$path", (New-Object System.Net.Http.StringContent($_, [Text.Encoding]::UTF8, 'application/json'))) })
    try { [Threading.Tasks.Task]::WaitAll([Threading.Tasks.Task[]]$tasks) } catch { }
    $out = foreach ($task in $tasks) {
        if ($task.IsFaulted) { [pscustomobject]@{ status = 0; body = '' } }
        else { [pscustomobject]@{ status = [int]$task.Result.StatusCode; body = $task.Result.Content.ReadAsStringAsync().Result } }
    }
    $client.Dispose()
    return $out
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

# Participants share the first-name prefix "Nb<stamp>"; last names sort a, b, c, d
$pA = Register 'a' "Nb${stamp}a" "Aa$stamp"
$pB = Register 'b' "Nb${stamp}b" "Bb$stamp"
$pC = Register 'c' "Nb${stamp}c" "Cc$stamp"
$admTwo = Register 'y' "Yy${stamp}" "Dd$stamp"
$ins = Register 'z' "Zz${stamp}" "Owner$stamp"
$insTwo = Register 'w' "Ww${stamp}" "Other$stamp"
# Every role given below is taken back in the finally block before the summary
try {
# Roles cannot be assigned through the API: set them on these test accounts only, then log in again
Sql 'exam_auth' "update users set role='ADMIN' where email='$($admTwo.email)'" | Out-Null
Sql 'exam_auth' "update users set role='INSTRUCTOR' where email in ('$($ins.email)','$($insTwo.email)')" | Out-Null
$admTwo.token = (Login $admTwo.email $password).data.accessToken
$ins.token = (Login $ins.email $password).data.accessToken
$insTwo.token = (Login $insTwo.email $password).data.accessToken
Write-Output "accounts: a, b, c (students), y (ADMIN for the test), z, w (INSTRUCTOR for the test) -$stamp@x.test"

function Option($text, $correct, $order) { @{ optionText = $text; isCorrect = $correct; orderIndex = $order } }
function Mc($text, $points, $order, $mediaId) {
    $q = @{ questionText = $text; questionType = 'MULTIPLE_CHOICE'; points = $points; orderIndex = $order; allowMultipleAnswers = $false; explanation = 'T-b3 secret explanation'; options = @((Option 'wrong' $false 0), (Option 'right' $true 1)) }
    if ($mediaId) { $q['media'] = @(@{ mediaId = $mediaId; position = 0 }) }
    return $q
}
function Open($text, $points, $order) { @{ questionText = $text; questionType = 'OPEN_ENDED'; points = $points; orderIndex = $order } }
function ExamBody($title, [DateTime]$startUtc, [DateTime]$endUtc, $maxAttempts, $passing, $questions) {
    @{ title = $title; description = 'B3 verification'; durationMinutes = 30; startTime = (UtcText $startUtc); endTime = (UtcText $endUtc)
       shuffleQuestions = $false; shuffleOptions = $false; showResultsImmediately = $true; maxAttempts = $maxAttempts; passingScore = $passing; questions = $questions }
}
function Upload($owner, $alt) {
    $u = Api 'POST' '/api/media/upload-url' $owner.token @{ kind = 'IMAGE'; contentType = 'image/png'; sizeBytes = $PNG.Length; altText = $alt }
    if ($u.status -ne 200) { throw "upload-url answered $($u.status)" }
    Invoke-WebRequest -Uri $u.data.uploadUrl -Method Put -Body $PNG -ContentType 'image/png' -UseBasicParsing -TimeoutSec 30 | Out-Null
    $c = Api 'POST' "/api/media/$($u.data.mediaId)/confirm" $owner.token @{}
    if ($c.status -ne 200) { throw "confirm answered $($c.status)" }
    return $u.data.mediaId
}
# Answer the question whose text contains $needle: a choice by option text, or a text answer
function Answer($started, $token, $needle, $choice, $text) {
    $q = @($started.data.questions) | Where-Object { $_.questionText -like "*$needle*" }
    $body = @{ questionId = $q.id }
    if ($null -ne $choice) { $body['selectedOptionIds'] = @(($q.options | Where-Object { $_.optionText -eq $choice }).id) } else { $body['textAnswer'] = $text }
    return (Api 'POST' "/api/exams/session/$($started.data.sessionId)/answer" $token $body)
}
function Begin($examId, $token) { (Api 'POST' "/api/exams/$examId/start" $token @{}) }
function Finish($sessionId, $token) { (Api 'POST' "/api/exams/session/$sessionId/submit" $token $null) }
function Fraud($sessionId, $token, $type) { (Api 'POST' "/api/exams/session/$sessionId/fraud-event" $token @{ type = $type; details = 'T-b3 event' }).status }

$start = [DateTime]::UtcNow.AddSeconds(20)
$start = $start.AddTicks(-($start.Ticks % [TimeSpan]::TicksPerSecond))
$end = $start.AddHours(6)

$media = Upload $ins 'T-b3 question image'
$openExam = Api 'POST' '/api/exams' $ins.token (ExamBody "T-b3-$stamp-open" $start $end 3 50 @((Mc 'T-b3 choice' 2 0 $media), (Open 'T-b3 essay ten' 10 1), (Open 'T-b3 essay five' 5 2)))
Check 'exam with 1 choice (media), OPEN 10 and OPEN 5 is created' ($openExam.status -eq 201) "status $($openExam.status) $($openExam.code)"
$resExam = Api 'POST' '/api/exams' $ins.token (ExamBody "T-b3-$stamp-res" $start $end 2 20 @((Mc 'T-b3 q1' 1 0 $null), (Mc 'T-b3 q2' 1 1 $null), (Mc 'T-b3 q3' 1 2 $null), (Mc 'T-b3 q4' 1 3 $null), (Open 'T-b3 res essay' 5 4)))
$pubExam = Api 'POST' '/api/exams' $ins.token (ExamBody "T-b3-$stamp-pub" $start $end 1 50 @((Mc 'T-b3 pub' 1 0 $null)))
$doneExam = Api 'POST' '/api/exams' $ins.token (ExamBody "T-b3-$stamp-done" $start $end 1 50 @((Mc 'T-b3 done' 1 0 $null)))
$idOpen = $openExam.data.id; $idRes = $resExam.data.id; $idPub = $pubExam.data.id; $idDone = $doneExam.data.id
foreach ($examId in $idOpen, $idRes, $idDone) { if ((Api 'POST' "/api/exams/$examId/publish" $ins.token $null).status -ne 200) { throw "publish $examId failed" } }

# ---------- i) publish ----------
Sql 'exam_db' "update exams set start_time = LOCALTIMESTAMP - interval '2 hours', end_time = LOCALTIMESTAMP - interval '1 hour' where id = $idPub and title like 'T-b3-%'" | Out-Null
$pubPast = Api 'POST' "/api/exams/$idPub/publish" $ins.token $null
Check 'publish of a draft whose end time has passed: 400 EXAM_WINDOW_ENDED, still DRAFT' ($pubPast.status -eq 400 -and $pubPast.code -eq 'EXAM_WINDOW_ENDED' -and $pubPast.json.message -match 'reschedule' -and (Sql 'exam_db' "select status from exams where id=$idPub") -eq 'DRAFT') "status $($pubPast.status) $($pubPast.code): $($pubPast.json.message)"
Sql 'exam_db' "update exams set status = 'COMPLETED', start_time = LOCALTIMESTAMP - interval '2 hours', end_time = LOCALTIMESTAMP - interval '1 hour' where id = $idDone and title like 'T-b3-%'" | Out-Null
$donePast = Api 'POST' "/api/exams/$idDone/publish" $ins.token $null
Check 'publish of an ended exam: 400 EXAM_WINDOW_ENDED, still COMPLETED' ($donePast.status -eq 400 -and $donePast.code -eq 'EXAM_WINDOW_ENDED' -and (Sql 'exam_db' "select status from exams where id=$idDone") -eq 'COMPLETED') "status $($donePast.status) $($donePast.code)"
$resched = Api 'POST' "/api/exams/$idDone/reschedule" $ins.token @{ endTime = (UtcText $end) }
$doneAgain = Api 'POST' "/api/exams/$idDone/publish" $ins.token $null
Check 'after reschedule the publish works again' ($resched.status -eq 200 -and $doneAgain.status -eq 200 -and $doneAgain.data.status -eq 'ACTIVE') "reschedule $($resched.status), publish $($doneAgain.status) $($doneAgain.code)"

$wait = ($start - [DateTime]::UtcNow).TotalSeconds + 2
if ($wait -gt 0) { Start-Sleep -Seconds ([int][Math]::Ceiling($wait)) }

# ---------- b) open questions: submit ----------
$sA = Begin $idOpen $pA.token
Check 'regress: registered participant starts the exam' ($sA.status -eq 200) "status $($sA.status) $($sA.code)"
$ansMc = Answer $sA $pA.token 'choice' 'right' $null
$ansTen = Answer $sA $pA.token 'essay ten' $null 'T-b3 long answer of A'
$ansFive = Answer $sA $pA.token 'essay five' $null 'T-b3 short answer of A'
Check 'A answers the choice and both open questions' ($ansMc.status -eq 200 -and $ansTen.status -eq 200 -and $ansFive.status -eq 200) "$($ansMc.status), $($ansTen.status), $($ansFive.status)"
$fraudStatuses = @((Fraud $sA.data.sessionId $pA.token 'TAB_SWITCH'), (Fraud $sA.data.sessionId $pA.token 'COPY_ATTEMPT'), (Fraud $sA.data.sessionId $pA.token 'DEVTOOLS'))
$subA = Finish $sA.data.sessionId $pA.token
$sidA = $sA.data.sessionId
$pkA = Sql 'exam_db' "select id from exam_sessions where session_id='$sidA'"
Check 'submit: the session is PENDING in the database, provisional score 2 of 17' ((Sql 'exam_db' "select grading_status||'|'||score||'|'||total_points from exam_sessions where id=$pkA") -eq 'PENDING|2.00|17.00')
Check 'open answers with text: needs_manual_review, zero points, no verdict' ((Sql 'exam_db' "select count(*) from user_answers a join questions q on q.id=a.question_id where a.session_id=$pkA and q.question_type='OPEN_ENDED' and a.needs_manual_review and a.points_earned = 0 and a.is_correct is null") -eq '2')
$noScore = { param($o) -not (Has $o 'totalScore') -and -not (Has $o 'percentageScore') -and -not (Has $o 'passed') -and -not (Has $o 'maxScore') }
Check 'submit answer: resultsAvailable=false, gradingStatus=PENDING, no score, no resultsAvailableAt' ($subA.status -eq 200 -and $subA.data.resultsAvailable -eq $false -and $subA.data.gradingStatus -eq 'PENDING' -and (& $noScore $subA.data) -and -not (Has $subA.data 'resultsAvailableAt')) "status $($subA.status); fields $(($subA.data.PSObject.Properties.Name) -join ',')"
$resA = Api 'GET' "/api/exams/session/$sidA/result" $pA.token $null
Check 'result endpoint: the same, no score' ($resA.status -eq 200 -and $resA.data.resultsAvailable -eq $false -and $resA.data.gradingStatus -eq 'PENDING' -and (& $noScore $resA.data) -and -not (Has $resA.data 'resultsAvailableAt'))
$mineA = @((Api 'GET' '/api/exams/my-sessions' $pA.token $null).data.content) | Where-Object { $_.sessionId -eq $sidA }
Check 'my-sessions: the same, no score' ($mineA.resultsAvailable -eq $false -and $mineA.gradingStatus -eq 'PENDING' -and (& $noScore $mineA) -and -not (Has $mineA 'resultsAvailableAt'))

$sB = Begin $idOpen $pB.token
Answer $sB $pB.token 'choice' 'right' $null | Out-Null
$blank = Answer $sB $pB.token 'essay ten' $null '   '
$subB = Finish $sB.data.sessionId $pB.token
$sidB = $sB.data.sessionId
Check 'an empty open answer needs no grader: NOT_REQUIRED, score shown at once (2 of 17)' ($blank.status -eq 200 -and $subB.data.gradingStatus -eq 'NOT_REQUIRED' -and $subB.data.resultsAvailable -eq $true -and $subB.data.totalScore -eq 2 -and $subB.data.maxScore -eq 17 -and (Sql 'exam_db' "select count(*) from user_answers a join exam_sessions s on s.id=a.session_id where s.session_id='$sidB' and a.needs_manual_review") -eq '0') "gradingStatus $($subB.data.gradingStatus), score $($subB.data.totalScore)/$($subB.data.maxScore)"
$sC = Begin $idOpen $pC.token
Answer $sC $pC.token 'essay ten' $null 'T-b3 answer of C, still writing' | Out-Null
$sidC = $sC.data.sessionId

# ---------- queue ----------
$queue = Api 'GET' "/api/exams/$idOpen/grading" $ins.token $null
$qRow = @($queue.data.content)[0]
Check 'the owner sees A in the grading queue (default PENDING)' ($queue.status -eq 200 -and $queue.data.totalElements -eq 1 -and $qRow.sessionId -eq $sidA -and $qRow.userId -eq $pA.id -and $qRow.participantName -eq $pA.name -and $qRow.email -eq $pA.email -and $qRow.attemptNumber -eq 1 -and $qRow.openAnswerCount -eq 2 -and $qRow.pendingCount -eq 2 -and $qRow.gradedCount -eq 0 -and $qRow.gradingStatus -eq 'PENDING') "status $($queue.status) $($queue.code), total $($queue.data.totalElements); $($qRow.participantName), open $($qRow.openAnswerCount), pending $($qRow.pendingCount)"
$queueKeys = 'sessionId', 'userId', 'participantName', 'email', 'attemptNumber', 'submittedAt', 'openAnswerCount', 'pendingCount', 'gradedCount', 'gradingStatus'
Check 'a queue row has the ten listed fields' (-not ($queueKeys | Where-Object { $_ -notin $qRow.PSObject.Properties.Name })) (($qRow.PSObject.Properties.Name) -join ', ')
Check 'queue: status=COMPLETE empty, status=ALL has A, an unknown status 400' ((Api 'GET' "/api/exams/$idOpen/grading?status=COMPLETE" $ins.token $null).data.totalElements -eq 0 -and (Api 'GET' "/api/exams/$idOpen/grading?status=ALL" $ins.token $null).data.totalElements -eq 1 -and (Api 'GET' "/api/exams/$idOpen/grading?status=NOPE" $ins.token $null).status -eq 400)
Check 'queue: q by name finds A, q of B finds nothing, one character is 400' ((Api 'GET' "/api/exams/$idOpen/grading?q=$($pA.first)" $ins.token $null).data.totalElements -eq 1 -and (Api 'GET' "/api/exams/$idOpen/grading?q=$($pB.first)" $ins.token $null).data.totalElements -eq 0 -and (Api 'GET' "/api/exams/$idOpen/grading?q=N" $ins.token $null).status -eq 400)

$view = Api 'GET' "/api/exams/session/$sidA/grading" $ins.token $null
$ten = @($view.data.answers) | Where-Object { $_.maxPoints -eq 10 }
$five = @($view.data.answers) | Where-Object { $_.maxPoints -eq 5 }
$answerKeys = 'answerId', 'questionId', 'questionText', 'maxPoints', 'textAnswer', 'pointsEarned', 'feedback', 'gradedBy', 'gradedAt', 'gradeVersion', 'needsManualReview'
Check 'the open answers of the session: 2, with text, max points, version 0, waiting' ($view.status -eq 200 -and @($view.data.answers).Count -eq 2 -and $ten.textAnswer -eq 'T-b3 long answer of A' -and $five.textAnswer -eq 'T-b3 short answer of A' -and $ten.gradeVersion -eq 0 -and $ten.needsManualReview -eq $true -and $five.needsManualReview -eq $true -and $view.data.gradingStatus -eq 'PENDING' -and -not ($answerKeys | Where-Object { $_ -notin $ten.PSObject.Properties.Name })) "status $($view.status) $($view.code); $(@($view.data.answers).Count) answers"
$idTen = $ten.answerId; $idFive = $five.answerId
$idMcA = Sql 'exam_db' "select a.id from user_answers a join questions q on q.id=a.question_id where a.session_id=$pkA and q.question_type='MULTIPLE_CHOICE'"
$idTenB = Sql 'exam_db' "select a.id from user_answers a join questions q on q.id=a.question_id join exam_sessions s on s.id=a.session_id where s.session_id='$sidB' and q.question_type='OPEN_ENDED'"
$idTenC = Sql 'exam_db' "select a.id from user_answers a join exam_sessions s on s.id=a.session_id where s.session_id='$sidC'"
$stateOfA = { Sql 'exam_db' "select string_agg(a.id||':'||a.points_earned||':'||a.grade_version||':'||a.needs_manual_review||':'||coalesce(a.graded_at::text,'-'), ',' order by a.id) || '|' || (select score||'/'||grading_status from exam_sessions where id=$pkA) from user_answers a where a.session_id=$pkA" }
$untouched = & $stateOfA
$auditBefore = [int](Sql 'exam_db' 'select coalesce(max(id),0) from group_audit_log')

# ---------- c) refused gradings: nothing may be written ----------
function Grade($sessionId, $token, $items) { (Api 'PUT' "/api/exams/session/$sessionId/grades" $token (Grades $items)) }
$bad = [ordered]@{
    '11 of 10'                    = (Grade $sidA $admTwo.token @(@{ answerId = $idTen; points = 11; expectedVersion = 0 }))
    'negative points'             = (Grade $sidA $admTwo.token @(@{ answerId = $idTen; points = -1; expectedVersion = 0 }))
    'three decimals'              = (Grade $sidA $admTwo.token @(@{ answerId = $idTen; points = 7.125; expectedVersion = 0 }))
    'unknown answer id'           = (Grade $sidA $admTwo.token @(@{ answerId = 999999999; points = 1; expectedVersion = 0 }))
    "another session's answer"    = (Grade $sidA $admTwo.token @(@{ answerId = [long]$idTenB; points = 1; expectedVersion = 0 }))
    'a choice answer'             = (Grade $sidA $admTwo.token @(@{ answerId = [long]$idMcA; points = 1; expectedVersion = 0 }))
    'the same answer twice'       = (Grade $sidA $admTwo.token @(@{ answerId = $idTen; points = 1; expectedVersion = 0 }, @{ answerId = $idTen; points = 2; expectedVersion = 0 }))
    'missing expectedVersion'     = (Grade $sidA $admTwo.token @(@{ answerId = $idTen; points = 1 }))
    'feedback of 2001 characters' = (Grade $sidA $admTwo.token @(@{ answerId = $idTen; points = 1; expectedVersion = 0; feedback = ('x' * 2001) }))
    'an empty list'               = (Api 'PUT' "/api/exams/session/$sidA/grades" $admTwo.token '[]')
    '51 items'                    = (Grade $sidA $admTwo.token @(1..51 | ForEach-Object { @{ answerId = 900000000 + $_; points = 1; expectedVersion = 0 } }))
}
foreach ($case in $bad.Keys) { Check "refused with 400: $case" ($bad[$case].status -eq 400) "status $($bad[$case].status) $($bad[$case].code)" }
# d) all or nothing
$mixed = Grade $sidA $admTwo.token @(@{ answerId = $idTen; points = 7; expectedVersion = 0 }, @{ answerId = $idFive; points = 5; expectedVersion = 0 }, @{ answerId = [long]$idMcA; points = 1; expectedVersion = 0 })
Check 'three grades, one invalid: 400 GRADE_INVALID' ($mixed.status -eq 400 -and $mixed.code -eq 'GRADE_INVALID') "status $($mixed.status) $($mixed.code)"
$overMixed = Grade $sidA $admTwo.token @(@{ answerId = $idTen; points = 7; expectedVersion = 0 }, @{ answerId = $idFive; points = 5.5; expectedVersion = 0 })
Check 'two grades, the second over its maximum: 400' ($overMixed.status -eq 400 -and $overMixed.code -eq 'GRADE_INVALID') "status $($overMixed.status) $($overMixed.code)"
$stale = Grade $sidA $admTwo.token @(@{ answerId = $idTen; points = 7; expectedVersion = 0 }, @{ answerId = $idFive; points = 5; expectedVersion = 5 })
Check 'a wrong expectedVersion: 409 GRADE_CONFLICT with the stored version' ($stale.status -eq 409 -and $stale.code -eq 'GRADE_CONFLICT' -and $stale.json.error.details.answerId -eq $idFive -and $stale.json.error.details.currentVersion -eq 0) "status $($stale.status) $($stale.code)"
$running = Grade $sidC $admTwo.token @(@{ answerId = [long]$idTenC; points = 1; expectedVersion = 0 })
Check 'a session in progress cannot be graded: 409 SESSION_NOT_COMPLETED' ($running.status -eq 409 -and $running.code -eq 'SESSION_NOT_COMPLETED') "status $($running.status) $($running.code)"
# e) roles
$roleOther = @((Api 'GET' "/api/exams/$idOpen/grading" $insTwo.token $null).status, (Api 'GET' "/api/exams/session/$sidA/grading" $insTwo.token $null).status, (Grade $sidA $insTwo.token @(@{ answerId = $idTen; points = 1; expectedVersion = 0 })).status, (Api 'GET' "/api/exams/session/$sidA/review" $insTwo.token $null).status, (Api 'GET' "/api/exams/$idOpen/results/summary" $insTwo.token $null).status, (Api 'GET' "/api/exams/$idOpen/results?sort=score" $insTwo.token $null).status)
Check "another INSTRUCTOR: 403 on queue, session grading, grades, review, summary and results" (-not ($roleOther | Where-Object { $_ -ne 403 })) ($roleOther -join ', ')
$roleStudent = @((Api 'GET' "/api/exams/$idOpen/grading" $pA.token $null).status, (Api 'GET' "/api/exams/session/$sidA/grading" $pA.token $null).status, (Grade $sidA $pA.token @(@{ answerId = $idTen; points = 10; expectedVersion = 0 })).status, (Api 'GET' "/api/exams/session/$sidA/review" $pA.token $null).status, (Api 'GET' "/api/exams/$idOpen/results/summary" $pA.token $null).status, (Api 'GET' "/api/exams/$idOpen/results" $pA.token $null).status)
Check 'a STUDENT (also the owner of the session): 403 on all of them' (-not ($roleStudent | Where-Object { $_ -ne 403 })) ($roleStudent -join ', ')
Check 'an unknown session: 404; no token: 401' ((Api 'GET' "/api/exams/session/$([Guid]::NewGuid())/grading" $admTwo.token $null).status -eq 404 -and (Api 'GET' "/api/exams/session/$([Guid]::NewGuid())/review" $admTwo.token $null).status -eq 404 -and (Api 'GET' "/api/exams/$idOpen/grading" $null $null).status -eq 401)
Check 'after every refused request nothing was written and nothing audited' ((& $stateOfA) -eq $untouched -and [int](Sql 'exam_db' 'select coalesce(max(id),0) from group_audit_log') -eq $auditBefore) $untouched

# ---------- c) grading ----------
$graded = Grade $sidA $admTwo.token @(@{ answerId = $idTen; points = 7; expectedVersion = 0; feedback = 'T-b3 good, but too short' }, @{ answerId = $idFive; points = 5; expectedVersion = 0; feedback = 'T-b3 complete' })
Check 'ADMIN grades 7/10 and 5/5: 200, COMPLETE, score 14 of 17, 82.35 %, passed' ($graded.status -eq 200 -and $graded.data.gradingStatus -eq 'COMPLETE' -and $graded.data.score -eq 14 -and $graded.data.maxScore -eq 17 -and $graded.data.percentage -eq 82.35 -and $graded.data.passed -eq $true) "status $($graded.status) $($graded.code); $($graded.data.score)/$($graded.data.maxScore) $($graded.data.percentage)% $($graded.data.gradingStatus)"
Check 'the session row: score 14.00, 82.35, passed, COMPLETE; maximum unchanged' ((Sql 'exam_db' "select score||'|'||total_points||'|'||percentage||'|'||passed||'|'||grading_status from exam_sessions where id=$pkA") -eq '14.00|17.00|82.35|true|COMPLETE')
$gTen = @($graded.data.answers) | Where-Object { $_.answerId -eq $idTen }
Check 'the answer: points, feedback, grader name, gradedAt with Z, version 1, no longer waiting' ($gTen.pointsEarned -eq 7 -and $gTen.feedback -eq 'T-b3 good, but too short' -and $gTen.gradedBy -eq $admTwo.name -and $gTen.gradedAt -match 'Z$' -and $gTen.gradeVersion -eq 1 -and $gTen.needsManualReview -eq $false) "gradedBy '$($gTen.gradedBy)', version $($gTen.gradeVersion)"
$resA2 = Api 'GET' "/api/exams/session/$sidA/result" $pA.token $null
$mineA2 = @((Api 'GET' '/api/exams/my-sessions' $pA.token $null).data.content) | Where-Object { $_.sessionId -eq $sidA }
Check 'A now sees the score: result and my-sessions say 14 of 17, 82.35 %, COMPLETE' ($resA2.data.resultsAvailable -eq $true -and $resA2.data.gradingStatus -eq 'COMPLETE' -and $resA2.data.totalScore -eq 14 -and $resA2.data.maxScore -eq 17 -and $resA2.data.percentageScore -eq 82.35 -and $resA2.data.passed -eq $true -and $mineA2.resultsAvailable -eq $true -and $mineA2.totalScore -eq 14 -and $mineA2.percentageScore -eq 82.35 -and $mineA2.gradingStatus -eq 'COMPLETE') "result $($resA2.data.totalScore)/$($resA2.data.maxScore) $($resA2.data.percentageScore)%; list $($mineA2.totalScore)"
Check 'the result still carries no answer key and no per-question verdict' ($resA2.raw -notmatch 'isCorrect|explanation|T-b3 secret|feedback')
$audit1 = @(Sql 'exam_db' "select actor_id||'|'||action||'|'||target_user_id||'|'||details from group_audit_log where id > $auditBefore order by id")
Check 'two audit rows: who, session, answer, old and new points' ($audit1.Count -eq 2 -and $audit1[0] -like "$($admTwo.id)|ANSWER_GRADED|$($pA.id)|examId=$idOpen sessionId=$sidA answerId=$idTen questionId=* points=0.00 -> 7 max=10.00 version=0 -> 1" -and $audit1[1] -like "*answerId=$idFive * points=0.00 -> 5 max=5.00 version=0 -> 1") $(if ($audit1.Count) { $audit1[0].Substring($audit1[0].IndexOf('examId')) })

$regrade = Grade $sidA $ins.token @(@{ answerId = $idTen; points = 8; expectedVersion = 1 })
Check 'regrading 7 -> 8 by the owner: 200, score 15, 88.24 %; feedback replaced by none' ($regrade.status -eq 200 -and $regrade.data.score -eq 15 -and $regrade.data.percentage -eq 88.24 -and (@($regrade.data.answers) | Where-Object { $_.answerId -eq $idTen }).gradeVersion -eq 2 -and (@($regrade.data.answers) | Where-Object { $_.answerId -eq $idTen }).gradedBy -eq $ins.name) "status $($regrade.status) $($regrade.code); $($regrade.data.score) $($regrade.data.percentage)%"
$audit2 = @(Sql 'exam_db' "select action||'|'||details from group_audit_log where id > $auditBefore order by id")
Check 'the regrading is audited with the old and the new points' ($audit2.Count -eq 3 -and $audit2[2] -like "ANSWER_REGRADED|*answerId=$idTen * points=7.00 -> 8 max=10.00 version=1 -> 2") $(if ($audit2.Count -ge 3) { $audit2[2] })
$staleAgain = Grade $sidA $admTwo.token @(@{ answerId = $idTen; points = 3; expectedVersion = 1 })
Check 'grading with the version from before the regrading: 409 GRADE_CONFLICT, 8 points stay' ($staleAgain.status -eq 409 -and $staleAgain.code -eq 'GRADE_CONFLICT' -and $staleAgain.json.error.details.currentVersion -eq 2 -and (Sql 'exam_db' "select points_earned||'/'||grade_version from user_answers where id=$idTen") -eq '8.00/2') "status $($staleAgain.status) $($staleAgain.code)"

$race = @(ParallelPut "/api/exams/session/$sidA/grades" $admTwo.token @((Grades @(@{ answerId = $idTen; points = 9; expectedVersion = 2; feedback = 'T-b3 race nine' })), (Grades @(@{ answerId = $idTen; points = 6; expectedVersion = 2; feedback = 'T-b3 race six' }))))
$afterRace = Sql 'exam_db' "select points_earned||'|'||grade_version||'|'||feedback from user_answers where id=$idTen"
$winner = if ($race[0].status -eq 200) { '9.00|3|T-b3 race nine' } else { '6.00|3|T-b3 race six' }
Check 'two gradings of the same answer at once: one 200, one 409 GRADE_CONFLICT' ((@($race | Where-Object { $_.status -eq 200 }).Count -eq 1) -and (@($race | Where-Object { $_.status -eq 409 -and $_.body -match 'GRADE_CONFLICT' }).Count -eq 1)) "statuses $(($race | ForEach-Object { $_.status }) -join ', ')"
Check 'the winner is stored once, the loser wrote nothing; one more audit row' ($afterRace -eq $winner -and (Sql 'exam_db' "select count(*) from group_audit_log where id > $auditBefore") -eq '4') "stored $afterRace"
$expectedScore = if ($race[0].status -eq 200) { '16.00|94.12' } else { '13.00|76.47' }
Check 'the session score follows the stored grade' ((Sql 'exam_db' "select score||'|'||percentage from exam_sessions where id=$pkA") -eq $expectedScore) (Sql 'exam_db' "select score||'|'||percentage from exam_sessions where id=$pkA")
$queueDone = Api 'GET' "/api/exams/$idOpen/grading?status=COMPLETE" $ins.token $null
Check 'queue: A moved from PENDING to COMPLETE (2 graded, 0 pending)' ((Api 'GET' "/api/exams/$idOpen/grading" $ins.token $null).data.totalElements -eq 0 -and $queueDone.data.totalElements -eq 1 -and @($queueDone.data.content)[0].gradedCount -eq 2 -and @($queueDone.data.content)[0].pendingCount -eq 0)

# Partial grading: B's second attempt has two open answers, only one gets graded
$sB2 = Begin $idOpen $pB.token
Answer $sB2 $pB.token 'essay ten' $null 'T-b3 second try of B, ten' | Out-Null
Answer $sB2 $pB.token 'essay five' $null 'T-b3 second try of B, five' | Out-Null
Finish $sB2.data.sessionId $pB.token | Out-Null
$viewB2 = Api 'GET' "/api/exams/session/$($sB2.data.sessionId)/grading" $ins.token $null
$tenB2 = @($viewB2.data.answers) | Where-Object { $_.maxPoints -eq 10 }
$partial = Grade $sB2.data.sessionId $ins.token @(@{ answerId = $tenB2.answerId; points = 3.75; expectedVersion = 0 })
$queueB = @((Api 'GET' "/api/exams/$idOpen/grading" $ins.token $null).data.content) | Where-Object { $_.sessionId -eq $sB2.data.sessionId }
$resB2 = Api 'GET' "/api/exams/session/$($sB2.data.sessionId)/result" $pB.token $null
Check 'one of two open answers graded (3.75): still PENDING, 1 waiting, attempt 2, B sees no score yet' ($partial.status -eq 200 -and $partial.data.gradingStatus -eq 'PENDING' -and $partial.data.score -eq 3.75 -and $queueB.pendingCount -eq 1 -and $queueB.gradedCount -eq 1 -and $queueB.attemptNumber -eq 2 -and $resB2.data.resultsAvailable -eq $false -and $resB2.data.gradingStatus -eq 'PENDING' -and (& $noScore $resB2.data)) "status $($partial.status) $($partial.code); $($partial.data.gradingStatus), score $($partial.data.score), pending $($queueB.pendingCount)"
Check "grading touched no other session: B's first attempt is as it was" ((Sql 'exam_db' "select score||'|'||grading_status from exam_sessions where session_id='$sidB'") -eq '2.00|NOT_REQUIRED')

# ---------- g) review ----------
$review = Api 'GET' "/api/exams/session/$sidA/review" $ins.token $null
$rq = @($review.data.questions)
$rMc = $rq | Where-Object { $_.questionType -eq 'MULTIPLE_CHOICE' }
$rTen = $rq | Where-Object { $_.maxPoints -eq 10 }
Check 'review: participant, attempt, times, score and grading status' ($review.status -eq 200 -and $review.data.participant.id -eq $pA.id -and $review.data.participant.name -eq $pA.name -and $review.data.participant.email -eq $pA.email -and $review.data.attemptNumber -eq 1 -and $review.data.status -eq 'COMPLETED' -and $review.data.gradingStatus -eq 'COMPLETE' -and $review.data.maxScore -eq 17 -and $review.data.startedAt -match 'Z$' -and $review.data.submittedAt -match 'Z$' -and $review.data.durationSeconds -ge 0 -and $review.data.examTitle -eq "T-b3-$stamp-open") "status $($review.status) $($review.code)"
Check 'review: the choice question with the key, what was selected, points and verdict' ($rq.Count -eq 3 -and $rMc.maxPoints -eq 2 -and $rMc.pointsEarned -eq 2 -and $rMc.correct -eq $true -and $rMc.answered -eq $true -and $rMc.explanation -eq 'T-b3 secret explanation' -and @($rMc.options).Count -eq 2 -and (@($rMc.options) | Where-Object { $_.optionText -eq 'right' }).correct -eq $true -and (@($rMc.options) | Where-Object { $_.optionText -eq 'right' }).selected -eq $true -and (@($rMc.options) | Where-Object { $_.optionText -eq 'wrong' }).selected -eq $false -and (@($rMc.options) | Where-Object { $_.optionText -eq 'wrong' }).correct -eq $false)
Check 'review: the open answer with text, points, feedback, grader and time' ($rTen.questionType -eq 'OPEN_ENDED' -and $rTen.textAnswer -eq 'T-b3 long answer of A' -and "$($rTen.pointsEarned)|$($rTen.gradeVersion)|$($rTen.feedback)" -eq $(if ($race[0].status -eq 200) { '9.00|3|T-b3 race nine' } else { '6.00|3|T-b3 race six' }) -and $rTen.gradedBy -eq $admTwo.name -and $rTen.gradedAt -match 'Z$' -and $rTen.needsManualReview -eq $false) "$($rTen.pointsEarned)|$($rTen.gradeVersion)|$($rTen.feedback)|$($rTen.gradedBy)"
Check 'review: the media of the question with a previewUrl' (@($rMc.media).Count -eq 1 -and @($rMc.media)[0].id -eq $media -and @($rMc.media)[0].previewUrl -like 'http*')
$events = @($review.data.violations.events)
Check 'review: violations by type and in order, risk from the policy (1 + 2 + 5 = 8, MEDIUM)' (-not ($fraudStatuses | Where-Object { $_ -ne 202 }) -and $review.data.violations.total -eq 3 -and $review.data.violations.countByType.TAB_SWITCH -eq 1 -and $review.data.violations.countByType.COPY_ATTEMPT -eq 1 -and $review.data.violations.countByType.DEVTOOLS -eq 1 -and $review.data.violations.riskScore -eq 8 -and $review.data.violations.riskLevel -eq 'MEDIUM' -and (($events | ForEach-Object { $_.type }) -join ',') -eq 'TAB_SWITCH,COPY_ATTEMPT,DEVTOOLS' -and $events[0].occurredAt -match 'Z$' -and $events[0].details -eq 'T-b3 event') "total $($review.data.violations.total), risk $($review.data.violations.riskScore) $($review.data.violations.riskLevel), order $(($events | ForEach-Object { $_.type }) -join ',')"
Check 'review: ADMIN 200; the participant 403; a session in progress can be reviewed too' ((Api 'GET' "/api/exams/session/$sidA/review" $admTwo.token $null).status -eq 200 -and (Api 'GET' "/api/exams/session/$sidA/review" $pA.token $null).status -eq 403 -and (Api 'GET' "/api/exams/session/$sidC/review" $ins.token $null).data.status -eq 'IN_PROGRESS')
$reviewB = Api 'GET' "/api/exams/session/$sidB/review" $ins.token $null
Check 'review of B: an unanswered question is listed as not answered, an empty one with its empty text' ((@($reviewB.data.questions) | Where-Object { $_.maxPoints -eq 5 }).answered -eq $false -and (@($reviewB.data.questions) | Where-Object { $_.maxPoints -eq 10 }).answered -eq $true -and $reviewB.data.violations.total -eq 0 -and $reviewB.data.violations.riskLevel -eq 'LOW')

# ---------- f) results: three participants with different scores and violations ----------
# A: first attempt 1 of 9 (one violation), second attempt 4 of 9 (clean). B: 2 of 9, 3 events. C: 1 of 9, 4 events.
# The test admin answers only the open question: that session waits for a grader.
function Take($who, $rightCount, $fraudTypes, $essay) {
    $s = Begin $idRes $who.token
    if ($s.status -ne 200) { throw "start res answered $($s.status) $($s.code)" }
    for ($n = 1; $n -le 4; $n++) { Answer $s $who.token "q$n" $(if ($n -le $rightCount) { 'right' } else { 'wrong' }) $null | Out-Null }
    if ($essay) { Answer $s $who.token 'res essay' $null $essay | Out-Null }
    foreach ($type in $fraudTypes) { Fraud $s.data.sessionId $who.token $type | Out-Null }
    Finish $s.data.sessionId $who.token | Out-Null
    return $s.data.sessionId
}
$resA1 = Take $pA 1 @('TAB_SWITCH') $null
Start-Sleep -Milliseconds 1100
$resA2 = Take $pA 4 @() $null
$resB1 = Take $pB 2 @('TAB_SWITCH', 'TAB_SWITCH', 'DEVTOOLS') $null
$resC1 = Take $pC 1 @('DEVTOOLS', 'DEVTOOLS', 'DEVTOOLS', 'TAB_SWITCH') $null
$resD1 = Take $admTwo 0 @() 'T-b3 essay that waits for a grader'
$resBrun = (Begin $idRes $pB.token).data.sessionId

function Results($query, $token) { (Api 'GET' "/api/exams/$idRes/results$query" $token $null) }
function Ids($response) { @($response.data.content | ForEach-Object { $_.sessionId }) }
$legacy = Results '' $ins.token
$legacyRow = @($legacy.data.content) | Where-Object { $_.sessionId -eq $resB1 }
Check 'the old call (no new parameter) still lists every session, also the one in progress' ($legacy.status -eq 200 -and $legacy.data.totalElements -eq 6 -and (Ids $legacy) -contains $resBrun -and $legacy.data.size -eq 25) "status $($legacy.status) $($legacy.code), total $($legacy.data.totalElements)"
Check 'the old fields are still there with the same meaning' ($legacyRow.userId -eq $pB.id -and $legacyRow.status -eq 'COMPLETED' -and $legacyRow.score -eq 2 -and $legacyRow.percentage -eq 22.22 -and $legacyRow.passed -eq $true -and $legacyRow.submittedAt -match 'Z$')
Check 'the old status filter still works' ((Results '?status=IN_PROGRESS' $ins.token).data.totalElements -eq 1 -and (Results '?status=COMPLETED' $ins.token).data.totalElements -eq 5)
$rowKeys = 'sessionId', 'userId', 'participantName', 'email', 'attemptNumber', 'status', 'startedAt', 'submittedAt', 'durationSeconds', 'score', 'maxScore', 'percentage', 'passed', 'gradingStatus', 'violationCount', 'riskLevel'
Check 'a row has the sixteen listed fields' (-not ($rowKeys | Where-Object { $_ -notin $legacyRow.PSObject.Properties.Name })) (($legacyRow.PSObject.Properties.Name) -join ', ')

$best = Results '?sort=score' $ins.token
$bestRows = @($best.data.content)
Check 'bestOnly (default): one row per participant, the best completed attempt, highest first' ($best.data.totalElements -eq 4 -and ((Ids $best) -join ',') -eq "$resA2,$resB1,$resC1,$resD1") "total $($best.data.totalElements)"
$rowA = $bestRows | Where-Object { $_.userId -eq $pA.id }; $rowB = $bestRows | Where-Object { $_.userId -eq $pB.id }; $rowC = $bestRows | Where-Object { $_.userId -eq $pC.id }; $rowD = $bestRows | Where-Object { $_.userId -eq $admTwo.id }
Check "A's row is the second attempt: 4 of 9, 44.44 %, passed, clean, LOW, named" ($rowA.attemptNumber -eq 2 -and $rowA.score -eq 4 -and $rowA.maxScore -eq 9 -and $rowA.percentage -eq 44.44 -and $rowA.passed -eq $true -and $rowA.violationCount -eq 0 -and $rowA.riskLevel -eq 'LOW' -and $rowA.participantName -eq $pA.name -and $rowA.email -eq $pA.email -and $rowA.gradingStatus -eq 'NOT_REQUIRED' -and $rowA.durationSeconds -ge 0 -and $rowA.startedAt -match 'Z$') "attempt $($rowA.attemptNumber), $($rowA.score)/$($rowA.maxScore) $($rowA.percentage)%"
Check 'B: 22.22 %, 3 violations, risk 7 = MEDIUM; C: 11.11 %, failed, 4 violations, risk 16 = HIGH' ($rowB.percentage -eq 22.22 -and $rowB.violationCount -eq 3 -and $rowB.riskLevel -eq 'MEDIUM' -and $rowC.percentage -eq 11.11 -and $rowC.passed -eq $false -and $rowC.violationCount -eq 4 -and $rowC.riskLevel -eq 'HIGH') "B $($rowB.percentage)% $($rowB.violationCount) $($rowB.riskLevel); C $($rowC.percentage)% $($rowC.violationCount) $($rowC.riskLevel)"
Check 'the session that waits for a grader is listed as PENDING' ($rowD.gradingStatus -eq 'PENDING' -and $rowD.score -eq 0)
$all = Results '?bestOnly=false&sort=score' $ins.token
Check 'bestOnly=false: all 6 sessions, the one in progress last' ($all.data.totalElements -eq 6 -and (Ids $all)[0] -eq $resA2 -and (Ids $all)[5] -eq $resBrun -and (Ids $all) -contains $resA1) "total $($all.data.totalElements)"
Check 'sort=score&order=asc: lowest first' (((Ids (Results '?sort=score&order=asc' $ins.token)) -join ',') -eq "$resD1,$resC1,$resB1,$resA2")
Check 'q by first-name prefix (any case): the three participants' (((Ids (Results "?q=NB$stamp&sort=score" $ins.token)) -join ',') -eq "$resA2,$resB1,$resC1")
Check 'q by full first name: one; by last name: one; by "first last": one' (((Ids (Results "?q=$($pB.first)" $ins.token)) -join ',') -eq $resB1 -and ((Ids (Results "?q=$($pC.last)" $ins.token)) -join ',') -eq $resC1 -and ((Ids (Results "?q=$([uri]::EscapeDataString($pA.name))" $ins.token)) -join ',') -eq $resA2)
Check 'q by email prefix: one' (((Ids (Results "?q=be-b3-c-$stamp" $ins.token)) -join ',') -eq $resC1)
Check 'q by full user id: one' (((Ids (Results "?q=$($pA.id)" $ins.token)) -join ',') -eq $resA2)
$wild = Results '?q=%25%25' $ins.token
Check 'q "%%" is text, not a wildcard: nothing; q of one character: 400; q without a match: empty' ($wild.status -eq 200 -and $wild.data.totalElements -eq 0 -and (Results '?q=N' $ins.token).status -eq 400 -and (Results '?q=zzqqxx' $ins.token).data.totalElements -eq 0) "'%%' -> $($wild.status) $($wild.data.totalElements)"
Check 'a user who matches q but did not take this exam is not listed' ((Results "?q=$($insTwo.first)" $ins.token).data.totalElements -eq 0)
Check 'passed=true: A and B; passed=false: C and the pending one' (((Ids (Results '?passed=true&sort=score' $ins.token)) -join ',') -eq "$resA2,$resB1" -and ((Ids (Results '?passed=false&sort=score' $ins.token)) -join ',') -eq "$resC1,$resD1")
Check 'gradingStatus=PENDING: only the waiting session' (((Ids (Results '?gradingStatus=PENDING' $ins.token)) -join ',') -eq $resD1 -and (Results '?gradingStatus=NOT_REQUIRED' $ins.token).data.totalElements -eq 3)
Check 'hasViolations=true: B and C; false: A and the pending one' (((Ids (Results '?hasViolations=true&sort=score' $ins.token)) -join ',') -eq "$resB1,$resC1" -and ((Ids (Results '?hasViolations=false&sort=score' $ins.token)) -join ',') -eq "$resA2,$resD1")
Check "hasViolations=true with bestOnly=false also finds A's first attempt" ((Ids (Results '?hasViolations=true&bestOnly=false' $ins.token)) -contains $resA1 -and (Results '?hasViolations=true&bestOnly=false' $ins.token).data.totalElements -eq 3)
Check 'status=IN_PROGRESS with bestOnly=false: the running session' (((Ids (Results '?status=IN_PROGRESS&bestOnly=false' $ins.token)) -join ',') -eq $resBrun)
Check 'sort=violations: most first' (((Ids (Results '?sort=violations' $ins.token)) | Select-Object -First 2) -join ',' -eq "$resC1,$resB1")
Check 'sort=name: by last name ascending, order=desc reversed' (((Ids (Results '?sort=name' $ins.token)) -join ',') -eq "$resA2,$resB1,$resC1,$resD1" -and ((Ids (Results '?sort=name&order=desc' $ins.token)) -join ',') -eq "$resD1,$resC1,$resB1,$resA2")
$byTime = Ids (Results '?sort=submittedAt&order=asc&bestOnly=false&status=COMPLETED' $ins.token)
Check 'sort=submittedAt&order=asc: in the order they were submitted' (($byTime -join ',') -eq "$resA1,$resA2,$resB1,$resC1,$resD1")
$pageOne = Results '?sort=score&size=2&page=0' $ins.token; $pageTwo = Results '?sort=score&size=2&page=1' $ins.token
Check 'paging: 2 + 2 of 4, hasNext on the first page' (((Ids $pageOne) -join ',') -eq "$resA2,$resB1" -and ((Ids $pageTwo) -join ',') -eq "$resC1,$resD1" -and $pageOne.data.hasNext -eq $true -and $pageOne.data.totalPages -eq 2)
Check 'sort=name pages the same way' (((Ids (Results '?sort=name&size=3&page=1' $ins.token)) -join ',') -eq $resD1 -and (Results '?sort=name&size=3&page=1' $ins.token).data.totalElements -eq 4)
Check 'size=500 is clamped to 50; an unknown sort or order is 400' ((Results '?sort=score&size=500' $ins.token).data.size -eq 50 -and (Results '?sort=height' $ins.token).status -eq 400 -and (Results '?sort=score&order=up' $ins.token).status -eq 400)
Check 'ADMIN sees the results of an exam of someone else' ((Results '?sort=score' $admTwo.token).data.totalElements -eq 4)

# ---------- summary ----------
$summary = Api 'GET' "/api/exams/$idRes/results/summary" $ins.token $null
$sm = $summary.data
Check 'summary counts: 4 participants, 6 sessions, 5 completed, 1 in progress, 1 waiting for a grader' ($summary.status -eq 200 -and $sm.participantCount -eq 4 -and $sm.sessionCount -eq 6 -and $sm.completedCount -eq 5 -and $sm.inProgressCount -eq 1 -and $sm.pendingGradingCount -eq 1) "status $($summary.status) $($summary.code); $($sm.participantCount)/$($sm.sessionCount)/$($sm.completedCount)/$($sm.inProgressCount)/$($sm.pendingGradingCount)"
# By hand: best attempts 44.44, 22.22 and 11.11 -> average 25.92; 2 of 3 passed -> 66.67
Check 'the pending session is not in the score figures: 3 ranked, average 25.92, pass rate 66.67' ($sm.rankedParticipantCount -eq 3 -and $sm.averagePercentage -eq 25.92 -and $sm.passRate -eq 66.67) "ranked $($sm.rankedParticipantCount), average $($sm.averagePercentage), pass rate $($sm.passRate)"
Check 'topScorers: A, B, C with name, percentage, score and session' (((@($sm.topScorers) | ForEach-Object { $_.sessionId }) -join ',') -eq "$resA2,$resB1,$resC1" -and @($sm.topScorers)[0].participantName -eq $pA.name -and @($sm.topScorers)[0].percentage -eq 44.44 -and @($sm.topScorers)[0].score -eq 4 -and @($sm.topScorers)[0].userId -eq $pA.id)
Check 'lowestScorers: C, B, A' (((@($sm.lowestScorers) | ForEach-Object { $_.sessionId }) -join ',') -eq "$resC1,$resB1,$resA2")
Check 'mostViolations: C (4), B (3); nobody without a violation' (((@($sm.mostViolations) | ForEach-Object { "$($_.sessionId):$($_.violationCount)" }) -join ',') -eq "${resC1}:4,${resB1}:3")
Check 'fewestViolations: A (0), B (3), C (4)' (((@($sm.fewestViolations) | ForEach-Object { "$($_.sessionId):$($_.violationCount)" }) -join ',') -eq "${resA2}:0,${resB1}:3,${resC1}:4")
Check 'cleanParticipantCount: 1 (the best attempt of A; the violation of the first attempt does not count)' ($sm.cleanParticipantCount -eq 1) "clean $($sm.cleanParticipantCount)"
$emptySummary = Api 'GET' "/api/exams/$idPub/results/summary" $ins.token $null
Check 'summary of an exam nobody took: zeros, no average, empty lists' ($emptySummary.status -eq 200 -and $emptySummary.data.sessionCount -eq 0 -and $emptySummary.data.rankedParticipantCount -eq 0 -and $null -eq $emptySummary.data.averagePercentage -and @($emptySummary.data.topScorers).Count -eq 0) "status $($emptySummary.status) $($emptySummary.code)"
# Grade the waiting session: it joins the figures
$viewD = Api 'GET' "/api/exams/session/$resD1/grading" $ins.token $null
$gradeD = Grade $resD1 $ins.token @(@{ answerId = @($viewD.data.answers)[0].answerId; points = 5; expectedVersion = 0 })
$summaryAfter = (Api 'GET' "/api/exams/$idRes/results/summary" $ins.token $null).data
# By hand: 44.44, 22.22, 11.11 and 55.56 -> 133.33 / 4 = 33.33; 3 of 4 passed -> 75
Check 'once graded (5 of 9 = 55.56 %) it counts: 4 ranked, average 33.33, pass rate 75, new top scorer' ($gradeD.status -eq 200 -and $gradeD.data.percentage -eq 55.56 -and $summaryAfter.pendingGradingCount -eq 0 -and $summaryAfter.rankedParticipantCount -eq 4 -and $summaryAfter.averagePercentage -eq 33.33 -and $summaryAfter.passRate -eq 75 -and @($summaryAfter.topScorers)[0].sessionId -eq $resD1 -and $summaryAfter.cleanParticipantCount -eq 2) "average $($summaryAfter.averagePercentage), pass rate $($summaryAfter.passRate), ranked $($summaryAfter.rankedParticipantCount)"

# ---------- N+1: statements per request ----------
function Statements($action) {
    Start-Sleep -Seconds 2
    $since = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ')
    & $action | Out-Null
    Start-Sleep -Seconds 2
    $exam = @(docker logs --since $since exam-exam-service 2>&1 | ForEach-Object { "$_" } | Where-Object { $_ -match 'Executing SQL statement' })
    $auth = @(docker logs --since $since exam-auth-service 2>&1 | ForEach-Object { "$_" } | Where-Object { $_ -match 'Executing SQL statement' })
    # The scheduler's own statements (every minute) are not part of the request
    $exam = @($exam | Where-Object { $_ -notmatch "FROM exams WHERE status = '|status = 'IN_PROGRESS' AND end_time <|media_assets" })
    return @{ exam = $exam.Count; auth = $auth.Count }
}
$nList = Statements { Results '?sort=score' $ins.token }
Check 'results page (4 rows): 3 statements in exam-service (exam, count, page), 1 in auth-service (names)' ($nList.exam -eq 3 -and $nList.auth -eq 1) "exam-service $($nList.exam), auth-service $($nList.auth)"
$nSummary = Statements { Api 'GET' "/api/exams/$idRes/results/summary" $ins.token $null }
Check 'summary: 3 statements in exam-service (exam, counts, rankings), 1 in auth-service' ($nSummary.exam -eq 3 -and $nSummary.auth -eq 1) "exam-service $($nSummary.exam), auth-service $($nSummary.auth)"
$nSearch = Statements { Results "?q=nb$stamp&sort=score" $ins.token }
Check 'results with q: 3 statements in exam-service, 1 in auth-service (the search also names the rows)' ($nSearch.exam -eq 3 -and $nSearch.auth -eq 1) "exam-service $($nSearch.exam), auth-service $($nSearch.auth)"

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
Write-Output "exam ids: open=$idOpen res=$idRes pub=$idPub done=$idDone; stamp=$stamp"
