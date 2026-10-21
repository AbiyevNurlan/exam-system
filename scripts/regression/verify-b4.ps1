# Live verification of feat/monitoring-analytics (B4) against the running stack.
# Admin credentials are read from F:\exam-system\.env into variables only; nothing secret is printed.
# Works only with the "T-b4-*" exams and @x.test accounts it creates itself; deletes nothing.
# Redis and auth-service are stopped and started again once each (docker-compose stop / start only).
$ErrorActionPreference = 'Continue'
# Numbers are formatted with a dot whatever the machine's language is
[Threading.Thread]::CurrentThread.CurrentCulture = [Globalization.CultureInfo]::InvariantCulture
Set-Location F:\exam-system
$GW = 'http://127.0.0.1:8080'
. "$PSScriptRoot\_common.ps1"
Wait-Gateway $GW
$EXAM = 'http://127.0.0.1:8082'
$MAIL = 'http://127.0.0.1:8025'
$stamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString('x')
$script:results = @()

function Check($name, $ok, $detail = '') {
    $script:results += [pscustomobject]@{ name = $name; ok = [bool]$ok }
    $line = $(if ($ok) { 'PASS' } else { 'FAIL' }) + "  $name"
    if ($detail -ne '') { $line += "  ($detail)" }
    Write-Output $line
}

function Call($base, $method, $path, $token, $body) {
    $headers = @{}
    if ($token) { $headers['Authorization'] = "Bearer $token" }
    $params = @{ Uri = "$base$path"; Method = $method; Headers = $headers; UseBasicParsing = $true; TimeoutSec = 30 }
    if ($null -ne $body) {
        $jsonBody = if ($body -is [string]) { $body } else { $body | ConvertTo-Json -Depth 10 }
        $params['Body'] = [Text.Encoding]::UTF8.GetBytes($jsonBody); $params['ContentType'] = 'application/json'
    }
    $watch = [Diagnostics.Stopwatch]::StartNew()
    try {
        $r = Invoke-WebRequest @params
        # Always UTF-8 from the raw bytes: without a charset in the content type PowerShell 5.1 decodes as ISO-8859-1
        $raw = [Text.Encoding]::UTF8.GetString($r.RawContentStream.ToArray())
        $status = [int]$r.StatusCode
    } catch {
        $resp = $_.Exception.Response
        if (-not $resp) { return @{ status = 0; raw = $_.Exception.Message; json = $null; data = $null; code = $null; ms = $watch.ElapsedMilliseconds } }
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
    return @{ status = $status; raw = $raw; json = $json; data = $data; code = $code; ms = $watch.ElapsedMilliseconds }
}
function Api($method, $path, $token, $body) { Call $GW $method $path $token $body }
function Sql($db, $query) { docker exec exam-postgres psql -U postgres -d $db -Atc $query }
function Has($object, $name) { $object -and ($object.PSObject.Properties.Name -contains $name) }
function Grades($items) { '[' + ((@($items) | ForEach-Object { $_ | ConvertTo-Json -Compress }) -join ',') + ']' }

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
    $email = "be-b4-$name-$stamp@x.test"
    # The gateway's rate limiter keeps one bucket per client IP for both /auth routes, so a drained
    # verification budget can also refuse a registration for a moment
    for ($try = 0; $try -lt 8; $try++) {
        $r = Api 'POST' '/auth/register' $null @{ email = $email; password = $password; firstName = $first; lastName = $last }
        if ($r.status -ne 429) { break }
        Start-Sleep -Seconds 6
    }
    if ($r.status -ne 201) { throw "register $name answered $($r.status)" }
    $code = MailedCode $email
    # The gateway allows ten verifications at once, then one every five seconds
    for ($try = 0; $try -lt 6; $try++) {
        $v = Api 'POST' '/auth/verify-otp' $null @{ email = $email; code = $code }
        if ($v.status -ne 429) { break }
        Start-Sleep -Seconds 6
    }
    if ($v.status -ne 200) { throw "verify $name answered $($v.status)" }
    return @{ email = $email; id = $v.data.user.id; token = $v.data.accessToken; name = "$first $last"; first = $first; last = $last }
}
function Login($email, $pw) { (Api 'POST' '/auth/login' $null @{ email = $email; password = $pw }) }
function UtcText([DateTime]$utc) { $utc.ToString('yyyy-MM-ddTHH:mm:ss') }

# ---------- setup ----------
$adminEmail = $null; $adminPassword = $null
foreach ($line in Get-Content F:\exam-system\.env) {
    if ($line -match '^ADMIN_EMAIL=(.*)$') { $adminEmail = $Matches[1].Trim() }
    if ($line -match '^ADMIN_PASSWORD=(.*)$') { $adminPassword = $Matches[1].Trim() }
}
$mainLogin = Login $adminEmail $adminPassword
if ($mainLogin.status -ne 200) { throw "admin login answered $($mainLogin.status)" }
$adm = @{ id = $mainLogin.data.user.id; token = $mainLogin.data.accessToken }

# people[1..12]: first names share the prefix "Mb<stamp>", last names sort by number
$people = @($null)
foreach ($n in 1..12) { $people += (Register ("p{0:00}" -f $n) ("Mb${stamp}x{0:00}" -f $n) ("L{0:00}$stamp" -f $n)) }
$admTwo = Register 'y' "Yy$stamp" "Admin$stamp"
$ins = Register 'z' "Zz$stamp" "Owner$stamp"
$insTwo = Register 'w' "Ww$stamp" "Other$stamp"
# Every role given below is taken back in the finally block before the summary
try {
Sql 'exam_auth' "update users set role='ADMIN' where email='$($admTwo.email)'" | Out-Null
Sql 'exam_auth' "update users set role='INSTRUCTOR' where email in ('$($ins.email)','$($insTwo.email)')" | Out-Null
$admTwo.token = (Login $admTwo.email $password).data.accessToken
$ins.token = (Login $ins.email $password).data.accessToken
$insTwo.token = (Login $insTwo.email $password).data.accessToken
Write-Output "accounts: p01..p12 (students), y (ADMIN for the test), z, w (INSTRUCTOR for the test) -$stamp@x.test"

function Option($text, $correct, $order) { @{ optionText = $text; isCorrect = $correct; orderIndex = $order } }
function ExamBody($title, [DateTime]$startUtc, [DateTime]$endUtc, $maxAttempts, $questions) {
    @{ title = $title; description = 'B4 verification'; durationMinutes = 30; startTime = (UtcText $startUtc); endTime = (UtcText $endUtc)
       shuffleQuestions = $false; shuffleOptions = $false; showResultsImmediately = $true; maxAttempts = $maxAttempts; passingScore = 50; questions = $questions }
}
$start = [DateTime]::UtcNow.AddSeconds(20)
$start = $start.AddTicks(-($start.Ticks % [TimeSpan]::TicksPerSecond))
$end = $start.AddHours(6)
$monQuestions = @(1..3 | ForEach-Object { @{ questionText = "T-b4 mon q$_"; questionType = 'MULTIPLE_CHOICE'; points = 1; orderIndex = $_ - 1; allowMultipleAnswers = $false; explanation = 'T-b4 secret explanation'; options = @((Option 'wrong' $false 0), (Option 'right' $true 1)) } })
$anaQuestions = @(
    @{ questionText = 'T-b4 single'; questionType = 'MULTIPLE_CHOICE'; points = 2; orderIndex = 0; allowMultipleAnswers = $false; options = @((Option 'A' $true 0), (Option 'B' $false 1), (Option 'C' $false 2)) },
    @{ questionText = 'T-b4 multi'; questionType = 'MULTIPLE_CHOICE'; points = 3; orderIndex = 1; allowMultipleAnswers = $true; options = @((Option 'W' $true 0), (Option 'X' $true 1), (Option 'Y' $false 2), (Option 'Z' $false 3)) },
    @{ questionText = 'T-b4 truefalse'; questionType = 'TRUE_FALSE'; points = 1; orderIndex = 2; options = @((Option 'True' $true 0), (Option 'False' $false 1)) },
    @{ questionText = 'T-b4 blank'; questionType = 'FILL_BLANK'; points = 2; orderIndex = 3; options = @((Option 'Baku' $true 0)) },
    @{ questionText = ('T-b4 essay ' + ('long text ' * 30)); questionType = 'OPEN_ENDED'; points = 4; orderIndex = 4 }
)
$monExam = Api 'POST' '/api/exams' $ins.token (ExamBody "T-b4-$stamp-mon" $start $end 2 $monQuestions)
$anaExam = Api 'POST' '/api/exams' $ins.token (ExamBody "T-b4-$stamp-ana" $start $end 2 $anaQuestions)
$otherExam = Api 'POST' '/api/exams' $insTwo.token (ExamBody "T-b4-$stamp-other" $start $end 1 $monQuestions)
$idMon = $monExam.data.id; $idAna = $anaExam.data.id; $idOther = $otherExam.data.id
Check 'the T-b4 exams are created (monitoring, analytics with 5 question types, one of another instructor)' ($monExam.status -eq 201 -and $anaExam.status -eq 201 -and $otherExam.status -eq 201) "$($monExam.status), $($anaExam.status) $($anaExam.code), $($otherExam.status)"
# B13: the instructors' exams are for groups; the participants of this script take them as PUBLIC exams
foreach ($examId in $idMon, $idAna, $idOther) { if ((Open-ExamToEveryone $adm.token $examId).status -ne 200) { throw "open $examId to everybody failed" } }
foreach ($examId in $idMon, $idAna) { if ((Api 'POST' "/api/exams/$examId/publish" $ins.token $null).status -ne 200) { throw "publish $examId failed" } }
if ((Api 'POST' "/api/exams/$idOther/publish" $insTwo.token $null).status -ne 200) { throw 'publish other failed' }
$wait = ($start - [DateTime]::UtcNow).TotalSeconds + 2
if ($wait -gt 0) { Start-Sleep -Seconds ([int][Math]::Ceiling($wait)) }

function Begin($examId, $who) { (Api 'POST' "/api/exams/$examId/start" $who.token @{}) }
function Finish($sessionId, $who) { (Api 'POST' "/api/exams/session/$sessionId/submit" $who.token $null) }
function Pick($started, $who, $needle, $choices) {
    $q = @($started.data.questions) | Where-Object { $_.questionText -like "*$needle*" }
    $ids = @($q.options | Where-Object { $_.optionText -in $choices } | ForEach-Object { $_.id })
    return (Api 'POST' "/api/exams/session/$($started.data.sessionId)/answer" $who.token @{ questionId = $q.id; selectedOptionIds = $ids })
}
function Write-Text($started, $who, $needle, $text) {
    $q = @($started.data.questions) | Where-Object { $_.questionText -like "*$needle*" }
    return (Api 'POST' "/api/exams/session/$($started.data.sessionId)/answer" $who.token @{ questionId = $q.id; textAnswer = $text })
}
function Fraud($sessionId, $who, $type, $times) { foreach ($k in 1..$times) { (Api 'POST' "/api/exams/session/$sessionId/fraud-event" $who.token @{ type = $type; details = 'T-b4 event' }) | Out-Null } }

# ---------- e) analytics exam: 12 participants, one of them twice ----------
# single / multi / truefalse / blank / essay points (null = no answer; 'none' = text but left ungraded)
$plan = @{
    1  = @('A', @('W', 'X'), 'True', 'Baku', 4)
    2  = @('A', @('W', 'X'), 'True', ' baku ', 3)
    3  = @('A', @('W', 'X'), 'True', 'BAKU', 2)
    4  = @('A', @('W', 'X'), 'False', 'baku', 2)
    5  = @('A', @('W'), 'True', 'Ganja', 2)
    6  = @('B', @('W', 'X'), 'True', 'ganja', 1)
    7  = @('A', @('W', 'Y'), 'False', 'Baki', 1)
    8  = @('B', @('X'), 'True', $null, 0)
    9  = @('C', @('Y', 'Z'), 'False', 'sumqayit', 0)
    10 = @($null, @('W', 'X'), 'False', 'ganja', 0)
    11 = @('A', @('W', 'X'), 'True', 'baku', 'none')
    12 = @('A', @('W', 'X'), 'True', 'baku', $null)
}
# p12 first makes a weak attempt; the later, better one must be the one that counts
$weak = Begin $idAna $people[12]
Pick $weak $people[12] 'single' @('B') | Out-Null
Finish $weak.data.sessionId $people[12] | Out-Null
$anaSessions = @{}
foreach ($n in 1..12) {
    $who = $people[$n]; $row = $plan[$n]
    $s = Begin $idAna $who
    if ($s.status -ne 200) { throw "start ana p$n answered $($s.status) $($s.code)" }
    if ($row[0]) { Pick $s $who 'single' @($row[0]) | Out-Null }
    Pick $s $who 'multi' $row[1] | Out-Null
    Pick $s $who 'truefalse' @($row[2]) | Out-Null
    if ($null -ne $row[3]) { Write-Text $s $who 'blank' $row[3] | Out-Null }
    if ($null -ne $row[4]) { Write-Text $s $who 'essay' "T-b4 essay of p$n" | Out-Null }
    $f = Finish $s.data.sessionId $who
    if ($f.status -ne 200) { throw "submit ana p$n answered $($f.status)" }
    $anaSessions[$n] = $s.data.sessionId
}
$gradedOk = $true
foreach ($n in 1..10) {
    $view = Api 'GET' "/api/exams/session/$($anaSessions[$n])/grading" $ins.token $null
    $g = Api 'PUT' "/api/exams/session/$($anaSessions[$n])/grades" $ins.token (Grades @(@{ answerId = @($view.data.answers)[0].answerId; points = $plan[$n][4]; expectedVersion = 0 }))
    if ($g.status -ne 200 -or $g.data.gradingStatus -ne 'COMPLETE') { $gradedOk = $false }
}
$stored = Sql 'exam_db' "select string_agg(percentage::text, ',' order by percentage desc, id) from (select distinct on (user_id) id, user_id, percentage from exam_sessions where exam_id=$idAna and status='COMPLETED' and grading_status<>'PENDING' order by user_id, percentage desc, submitted_at, id) b"
Check 'regress: 13 sessions submitted, 10 open answers graded; best percentages as planned' ($gradedOk -and $stored -eq '100.00,91.67,83.33,75.00,66.67,41.67,41.67,25.00,25.00,8.33,0.00') $stored

# ---------- b) monitoring: five participants at once ----------
$live = @{}
foreach ($n in 1..5) { $live[$n] = Begin $idMon $people[$n]; if ($live[$n].status -ne 200) { throw "start mon p$n answered $($live[$n].status) $($live[$n].code)" } }
# answers: p1 none, p2 one, p3 two, p4 three, p5 one
Pick $live[2] $people[2] 'q1' @('right') | Out-Null
Pick $live[3] $people[3] 'q1' @('right') | Out-Null; Pick $live[3] $people[3] 'q2' @('wrong') | Out-Null
Pick $live[4] $people[4] 'q1' @('right') | Out-Null; Pick $live[4] $people[4] 'q2' @('right') | Out-Null; Pick $live[4] $people[4] 'q3' @('wrong') | Out-Null
Pick $live[5] $people[5] 'q3' @('right') | Out-Null
# weighted sums 0, 2, 8, 20, 40 -> LOW, LOW, MEDIUM, HIGH, CRITICAL (boundaries 5 / 15 / 30)
Fraud $live[2].data.sessionId $people[2] 'TAB_SWITCH' 2
Fraud $live[3].data.sessionId $people[3] 'FULLSCREEN_EXIT' 4
Fraud $live[4].data.sessionId $people[4] 'DEVTOOLS' 4
Fraud $live[5].data.sessionId $people[5] 'DEVTOOLS' 8
$sid = @{}; foreach ($n in 1..5) { $sid[$n] = $live[$n].data.sessionId }
Start-Sleep -Seconds 4

$active = Api 'GET' '/api/exams/monitoring/active' $ins.token $null
$rowMon = @($active.data) | Where-Object { $_.examId -eq $idMon }
Check '/monitoring/active (owner): exactly the exam that is being taken' ($active.status -eq 200 -and @($active.data).Count -eq 1 -and $rowMon.title -eq "T-b4-$stamp-mon") "status $($active.status) $($active.code); $(@($active.data).Count) exams"
Check 'its figures: 5 running, 18 violations, highest CRITICAL, riskCounts LOW 2 / MEDIUM 1 / HIGH 1 / CRITICAL 1' ($rowMon.inProgressCount -eq 5 -and $rowMon.violationCount -eq 18 -and $rowMon.highestRiskLevel -eq 'CRITICAL' -and $rowMon.riskCounts.LOW -eq 2 -and $rowMon.riskCounts.MEDIUM -eq 1 -and $rowMon.riskCounts.HIGH -eq 1 -and $rowMon.riskCounts.CRITICAL -eq 1 -and $rowMon.lastEventAt -match 'Z$' -and $rowMon.startTime -match 'Z$') "running $($rowMon.inProgressCount), violations $($rowMon.violationCount), highest $($rowMon.highestRiskLevel), counts $($rowMon.riskCounts.LOW)/$($rowMon.riskCounts.MEDIUM)/$($rowMon.riskCounts.HIGH)/$($rowMon.riskCounts.CRITICAL)"
$activeKeys = 'examId', 'title', 'startTime', 'endTime', 'inProgressCount', 'violationCount', 'highestRiskLevel', 'riskCounts', 'lastEventAt'
Check 'an active row has the nine listed fields' (-not ($activeKeys | Where-Object { $_ -notin $rowMon.PSObject.Properties.Name })) (($rowMon.PSObject.Properties.Name) -join ', ')
$activeAdmin = Api 'GET' '/api/exams/monitoring/active' $admTwo.token $null
$listed = @($activeAdmin.data | ForEach-Object { $_.examId })
$liveInDb = @(Sql 'exam_db' "select distinct exam_id from exam_sessions where status='IN_PROGRESS' and end_time > LOCALTIMESTAMP order by 1")
Check 'ADMIN sees every exam with a running session and no other (not T-b3-load, not the finished analytics exam)' ($activeAdmin.status -eq 200 -and $listed -contains $idMon -and $listed -notcontains $idAna -and $listed -notcontains $idOther -and $listed -notcontains 102 -and (($listed | Sort-Object) -join ',') -eq ($liveInDb -join ',')) "listed $($listed -join ','); running in the database $($liveInDb -join ',')"
Check 'the riskiest exam comes first' (@($activeAdmin.data)[0].examId -eq $idMon -or @($activeAdmin.data)[0].highestRiskLevel -eq 'CRITICAL')
# c) roles
$activeOther = Api 'GET' '/api/exams/monitoring/active' $insTwo.token $null
Check "another INSTRUCTOR: /active is empty, the exam's monitoring, violations and analytics are 403" ($activeOther.status -eq 200 -and @($activeOther.data).Count -eq 0 -and (Api 'GET' "/api/exams/$idMon/monitoring" $insTwo.token $null).status -eq 403 -and (Api 'GET' "/api/exams/session/$($sid[5])/violations" $insTwo.token $null).status -eq 403 -and (Api 'GET' "/api/exams/$idAna/analytics/questions" $insTwo.token $null).status -eq 403 -and (Api 'GET' "/api/exams/$idAna/analytics/overview" $insTwo.token $null).status -eq 403) "active $($activeOther.status) with $(@($activeOther.data).Count)"
$studentStatuses = @((Api 'GET' '/api/exams/monitoring/active' $people[1].token $null).status, (Api 'GET' "/api/exams/$idMon/monitoring" $people[1].token $null).status, (Api 'GET' "/api/exams/session/$($sid[1])/violations" $people[1].token $null).status, (Api 'GET' "/api/exams/$idAna/analytics/questions" $people[1].token $null).status, (Api 'GET' "/api/exams/$idAna/analytics/overview" $people[1].token $null).status, (Api 'GET' '/api/analytics/overview' $people[1].token $null).status)
Check 'a STUDENT: 403 on all six new endpoints (also for the own session)' (-not ($studentStatuses | Where-Object { $_ -ne 403 })) ($studentStatuses -join ', ')
Check 'no token: 401; an unknown exam or session: 404' ((Api 'GET' '/api/exams/monitoring/active' $null $null).status -eq 401 -and (Api 'GET' '/api/analytics/overview' $null $null).status -eq 401 -and (Api 'GET' '/api/exams/99999999/monitoring' $admTwo.token $null).status -eq 404 -and (Api 'GET' "/api/exams/session/$([Guid]::NewGuid())/violations" $admTwo.token $null).status -eq 404)

function Monitor($query, $token) { (Api 'GET' "/api/exams/$idMon/monitoring$query" $token $null) }
function Who($response) { @($response.data.sessions.content | ForEach-Object { $s = $_.sessionId; ($sid.Keys | Where-Object { $sid[$_] -eq $s }) }) -join ',' }
$mon = Monitor '' $ins.token
$rows = @($mon.data.sessions.content)
Check 'exam monitoring header: 5 running, 18 violations, riskCounts 2/1/1/1, 3 questions, generatedAt' ($mon.status -eq 200 -and $mon.data.inProgressCount -eq 5 -and $mon.data.violationCount -eq 18 -and $mon.data.riskCounts.LOW -eq 2 -and $mon.data.riskCounts.MEDIUM -eq 1 -and $mon.data.riskCounts.HIGH -eq 1 -and $mon.data.riskCounts.CRITICAL -eq 1 -and $mon.data.totalQuestions -eq 3 -and $mon.data.generatedAt -match 'Z$' -and $mon.data.sessions.size -eq 25) "status $($mon.status) $($mon.code)"
Check 'default order: risk descending (p5, p4, p3, p2, p1)' ((Who $mon) -eq '5,4,3,2,1') (Who $mon)
$r5 = $rows | Where-Object { $_.sessionId -eq $sid[5] }; $r4 = $rows | Where-Object { $_.sessionId -eq $sid[4] }; $r3 = $rows | Where-Object { $_.sessionId -eq $sid[3] }; $r2 = $rows | Where-Object { $_.sessionId -eq $sid[2] }; $r1 = $rows | Where-Object { $_.sessionId -eq $sid[1] }
$sessionKeys = 'sessionId', 'userId', 'participantName', 'email', 'attemptNumber', 'startedAt', 'deadline', 'answeredCount', 'totalQuestions', 'violationCount', 'countsByType', 'riskScore', 'riskLevel', 'lastEventAt', 'lastEventType'
Check 'a session row has the fifteen listed fields' (-not ($sessionKeys | Where-Object { $_ -notin $r5.PSObject.Properties.Name })) (($r5.PSObject.Properties.Name) -join ', ')
Check 'risk by hand: 0 LOW, 2 LOW, 8 MEDIUM, 20 HIGH, 40 CRITICAL' ("$($r1.riskScore) $($r1.riskLevel),$($r2.riskScore) $($r2.riskLevel),$($r3.riskScore) $($r3.riskLevel),$($r4.riskScore) $($r4.riskLevel),$($r5.riskScore) $($r5.riskLevel)" -eq '0 LOW,2 LOW,8 MEDIUM,20 HIGH,40 CRITICAL') "$($r1.riskScore) $($r1.riskLevel), $($r2.riskScore) $($r2.riskLevel), $($r3.riskScore) $($r3.riskLevel), $($r4.riskScore) $($r4.riskLevel), $($r5.riskScore) $($r5.riskLevel)"
Check 'violations and types: 0, 2 TAB_SWITCH, 4 FULLSCREEN_EXIT, 4 DEVTOOLS, 8 DEVTOOLS; last event type and time' ($r1.violationCount -eq 0 -and $null -eq $r1.lastEventAt -and $r2.violationCount -eq 2 -and $r2.countsByType.TAB_SWITCH -eq 2 -and $r3.countsByType.FULLSCREEN_EXIT -eq 4 -and $r4.countsByType.DEVTOOLS -eq 4 -and $r5.violationCount -eq 8 -and $r5.countsByType.DEVTOOLS -eq 8 -and $r5.lastEventType -eq 'DEVTOOLS' -and $r5.lastEventAt -match 'Z$' -and $r3.lastEventType -eq 'FULLSCREEN_EXIT')
$dbAnswered = @(1..5 | ForEach-Object { Sql 'exam_db' "select count(*) from user_answers a join exam_sessions s on s.id=a.session_id where s.session_id='$($sid[$_])'" }) -join ','
Check 'answeredCount is the real number of answers (0, 1, 2, 3, 1) of 3 questions' ("$($r1.answeredCount),$($r2.answeredCount),$($r3.answeredCount),$($r4.answeredCount),$($r5.answeredCount)" -eq '0,1,2,3,1' -and $dbAnswered -eq '0,1,2,3,1' -and $r4.totalQuestions -eq 3) "api $($r1.answeredCount),$($r2.answeredCount),$($r3.answeredCount),$($r4.answeredCount),$($r5.answeredCount); database $dbAnswered"
Check 'participant, attempt, start and deadline' ($r3.userId -eq $people[3].id -and $r3.participantName -eq $people[3].name -and $r3.email -eq $people[3].email -and $r3.attemptNumber -eq 1 -and $r3.startedAt -match 'Z$' -and $r3.deadline -eq $live[3].data.endTime)
Check 'no answer, no key and no question text in the monitoring answer' ($mon.raw -notmatch 'isCorrect|textAnswer|selectedOptionIds|explanation|T-b4 secret|questionText|optionText')
$byName = Monitor "?q=$($people[3].first)" $ins.token
Check 'q by name: one row, the header still covers all five' ((Who $byName) -eq '3' -and $byName.data.sessions.totalElements -eq 1 -and $byName.data.inProgressCount -eq 5 -and $byName.data.violationCount -eq 18)
Check 'q by email prefix, by user id, by the common prefix; one character 400; no match empty with header' ((Who (Monitor "?q=be-b4-p04-$stamp" $ins.token)) -eq '4' -and (Who (Monitor "?q=$($people[5].id)" $ins.token)) -eq '5' -and (Monitor "?q=mb$stamp" $ins.token).data.sessions.totalElements -eq 5 -and (Monitor '?q=M' $ins.token).status -eq 400 -and (Monitor '?q=zzqqxx' $ins.token).data.sessions.totalElements -eq 0 -and (Monitor '?q=zzqqxx' $ins.token).data.inProgressCount -eq 5)
Check 'q of a participant who is not in a running session of this exam: empty' ((Monitor "?q=$($people[9].first)" $ins.token).data.sessions.totalElements -eq 0)
Check 'riskLevel filter: LOW -> p2, p1; CRITICAL -> p5; an unknown level 400' ((Who (Monitor '?riskLevel=LOW' $ins.token)) -eq '2,1' -and (Who (Monitor '?riskLevel=critical' $ins.token)) -eq '5' -and (Monitor '?riskLevel=EXTREME' $ins.token).status -eq 400)
$noneAtLevel = Monitor "?riskLevel=HIGH&q=$($people[1].first)" $ins.token
Check 'a filter that matches nobody still answers the header (5 running)' ($noneAtLevel.status -eq 200 -and $noneAtLevel.data.sessions.totalElements -eq 0 -and $noneAtLevel.data.inProgressCount -eq 5 -and $noneAtLevel.data.riskCounts.CRITICAL -eq 1)
Check 'sort=risk&order=asc; sort=violations; sort=progress' ((Who (Monitor '?sort=risk&order=asc' $ins.token)) -eq '1,2,3,4,5' -and (Who (Monitor '?sort=violations' $ins.token)) -eq '5,4,3,2,1' -and ((Who (Monitor '?sort=progress' $ins.token)).Split(',')[0..1] -join ',') -eq '4,3' -and (Who (Monitor '?sort=progress&order=asc' $ins.token)).Split(',')[0] -eq '1') "violations: $(Who (Monitor '?sort=violations' $ins.token)); progress: $(Who (Monitor '?sort=progress' $ins.token))"
Check 'sort=startedAt&order=asc (order of starting); sort=name (last name), order=desc reversed' ((Who (Monitor '?sort=startedAt&order=asc' $ins.token)) -eq '1,2,3,4,5' -and (Who (Monitor '?sort=name' $ins.token)) -eq '1,2,3,4,5' -and (Who (Monitor '?sort=name&order=desc' $ins.token)) -eq '5,4,3,2,1')
$pg0 = Monitor '?size=2&page=0' $ins.token; $pg2 = Monitor '?size=2&page=2' $ins.token
Check 'paging: 2 + 2 + 1 of 5; size=500 clamped to 50; an unknown sort 400' ((Who $pg0) -eq '5,4' -and (Who $pg2) -eq '1' -and $pg0.data.sessions.totalPages -eq 3 -and $pg0.data.sessions.hasNext -eq $true -and (Monitor '?size=500' $ins.token).data.sessions.size -eq 50 -and (Monitor '?sort=height' $ins.token).status -eq 400)
Check 'ADMIN sees the same exam' ((Who (Monitor '' $admTwo.token)) -eq '5,4,3,2,1')

# violations of one session
$vAll = Api 'GET' "/api/exams/session/$($sid[5])/violations" $ins.token $null
$vIds = @($vAll.data | ForEach-Object { [long]$_.id })
Check 'violations of p5: 8 rows with id, type, occurredAt, details, in id order' ($vAll.status -eq 200 -and $vIds.Count -eq 8 -and (($vIds | Sort-Object) -join ',') -eq ($vIds -join ',') -and @($vAll.data)[0].type -eq 'DEVTOOLS' -and @($vAll.data)[0].occurredAt -match 'Z$' -and @($vAll.data)[0].details -eq 'T-b4 event' -and (-not ('id', 'type', 'occurredAt', 'details' | Where-Object { $_ -notin @($vAll.data)[0].PSObject.Properties.Name }))) "status $($vAll.status) $($vAll.code), $($vIds.Count) rows"
$vSince = Api 'GET' "/api/exams/session/$($sid[5])/violations?sinceId=$($vIds[4])" $ins.token $null
Check 'sinceId: only the 3 events after the fifth' ((@($vSince.data | ForEach-Object { [long]$_.id }) -join ',') -eq ($vIds[5..7] -join ','))
Fraud $sid[5] $people[5] 'COPY_ATTEMPT' 1
$vNew = Api 'GET' "/api/exams/session/$($sid[5])/violations?sinceId=$($vIds[7])" $ins.token $null
Check 'after one more event, sinceId = the last id seen returns exactly the new one' (@($vNew.data).Count -eq 1 -and @($vNew.data)[0].type -eq 'COPY_ATTEMPT' -and [long]@($vNew.data)[0].id -gt $vIds[7])
Check 'limit=2 returns 2; a sinceId beyond the end returns none; another session has its own events' (@((Api 'GET' "/api/exams/session/$($sid[5])/violations?limit=2" $ins.token $null).data).Count -eq 2 -and @((Api 'GET' "/api/exams/session/$($sid[5])/violations?sinceId=999999999" $ins.token $null).data).Count -eq 0 -and @((Api 'GET' "/api/exams/session/$($sid[2])/violations" $ins.token $null).data).Count -eq 2 -and @((Api 'GET' "/api/exams/session/$($sid[1])/violations" $ins.token $null).data).Count -eq 0)

# ---------- d) cache: 3 seconds ----------
Start-Sleep -Seconds 4
$c1 = Monitor '?sort=risk&order=desc&size=5' $ins.token
$a1 = @((Api 'GET' '/api/exams/monitoring/active' $ins.token $null).data)[0]
Fraud $sid[1] $people[1] 'TAB_SWITCH' 1
$c2 = Monitor '?sort=risk&order=desc&size=5' $ins.token
$a2 = @((Api 'GET' '/api/exams/monitoring/active' $ins.token $null).data)[0]
Check 'two calls within 3 seconds: the same answer (a new event is not in it yet)' ($c2.data.generatedAt -eq $c1.data.generatedAt -and $c2.data.violationCount -eq $c1.data.violationCount -and $a2.violationCount -eq $a1.violationCount) "generatedAt $($c1.data.generatedAt) / $($c2.data.generatedAt); violations $($c1.data.violationCount) / $($c2.data.violationCount)"
Start-Sleep -Seconds 4
$c3 = Monitor '?sort=risk&order=desc&size=5' $ins.token
$a3 = @((Api 'GET' '/api/exams/monitoring/active' $ins.token $null).data)[0]
Check '4 seconds later: a new answer with the event (19 -> 20 violations)' ($c3.data.generatedAt -ne $c1.data.generatedAt -and $c3.data.violationCount -eq $c1.data.violationCount + 1 -and $a3.violationCount -eq $a1.violationCount + 1 -and $c3.data.violationCount -eq 20) "generatedAt $($c3.data.generatedAt); violations $($c3.data.violationCount), active $($a3.violationCount)"
$keys = @(docker exec exam-redis redis-cli --scan --pattern 'exam:read:monitoring:*')
$ttl = if ($keys.Count) { [int](docker exec exam-redis redis-cli ttl $keys[0]) } else { -9 }
Check 'the answers are in Redis under exam:read:monitoring:* with a TTL of at most 3 seconds' ($keys.Count -ge 1 -and $ttl -le 3 -and $ttl -ge -2) "$($keys.Count) keys, ttl $ttl s"
$otherView = Api 'GET' "/api/exams/$idMon/monitoring?sort=risk&order=desc&size=5" $insTwo.token $null
Check "the cache never serves another instructor: still 403 right after the owner's call" ($otherView.status -eq 403)

# Redis stopped for a short while: the endpoints answer from the database, participants are not affected
docker-compose stop redis 2>&1 | Out-Null
Start-Sleep -Seconds 2
$down1 = Call $EXAM 'GET' "/api/exams/$idMon/monitoring" $ins.token $null
$down2 = Call $EXAM 'GET' '/api/exams/monitoring/active' $ins.token $null
$down3 = Call $EXAM 'GET' "/api/exams/$idMon/monitoring" $ins.token $null
$downAnswer = Call $EXAM 'POST' "/api/exams/session/$($sid[1])/answer" $people[1].token @{ questionId = (@($live[1].data.questions) | Where-Object { $_.questionText -like '*q1*' }).id; selectedOptionIds = @((@($live[1].data.questions) | Where-Object { $_.questionText -like '*q1*' }).options[1].id) }
$downFraud = Call $EXAM 'POST' "/api/exams/session/$($sid[1])/fraud-event" $people[1].token @{ type = 'WINDOW_BLUR'; details = 'T-b4 event' }
$downGateway = Api 'GET' "/api/exams/$idMon/monitoring" $ins.token $null
Check 'Redis stopped: monitoring answers from the database (200, 5 running, 20 violations)' ($down1.status -eq 200 -and $down1.data.inProgressCount -eq 5 -and $down1.data.violationCount -eq 20 -and $down2.status -eq 200 -and @($down2.data).Count -eq 1 -and $down3.status -eq 200) "exam monitoring $($down1.status) in $($down1.ms) ms, active $($down2.status) in $($down2.ms) ms, again $($down3.status) in $($down3.ms) ms; through the gateway: $($downGateway.status)"
Check 'Redis stopped: a participant still answers and reports an event without error' ($downAnswer.status -eq 200 -and $downFraud.status -eq 202) "answer $($downAnswer.status) in $($downAnswer.ms) ms, fraud event $($downFraud.status)"
docker-compose start redis 2>&1 | Out-Null
for ($i = 0; $i -lt 30; $i++) { if ((docker exec exam-redis redis-cli ping 2>$null) -eq 'PONG') { break }; Start-Sleep -Seconds 1 }
Start-Sleep -Seconds 8
$up1 = Monitor '' $ins.token
$keysAfter = @(docker exec exam-redis redis-cli --scan --pattern 'exam:read:monitoring:*')
$upAnswer = Api 'POST' "/api/exams/session/$($sid[1])/answer" $people[1].token @{ questionId = (@($live[1].data.questions) | Where-Object { $_.questionText -like '*q2*' }).id; selectedOptionIds = @((@($live[1].data.questions) | Where-Object { $_.questionText -like '*q2*' }).options[0].id) }
Check 'Redis started again: monitoring through the gateway works, the cache is used again, a participant answers' ($up1.status -eq 200 -and $up1.data.violationCount -eq 21 -and $upAnswer.status -eq 200 -and $keysAfter.Count -ge 1) "monitoring $($up1.status), violations $($up1.data.violationCount), answer $($upAnswer.status), keys $($keysAfter.Count)"

# a participant who submits, and one whose time is over, leave the list
$subP2 = Finish $sid[2] $people[2]
Start-Sleep -Seconds 4
$afterSubmit = Monitor '' $ins.token
Check 'after p2 submitted: 4 running, p2 is gone' ($subP2.status -eq 200 -and $afterSubmit.data.inProgressCount -eq 4 -and (Who $afterSubmit) -eq '5,4,3,1') "running $($afterSubmit.data.inProgressCount): $(Who $afterSubmit)"
# only this test session gets an end time in the past
Sql 'exam_db' "update exam_sessions set end_time = LOCALTIMESTAMP - interval '1 second' where session_id = '$($sid[3])' and exam_id = $idMon" | Out-Null
Start-Sleep -Seconds 4
$afterExpiry = Monitor '' $ins.token
$statusP3 = Sql 'exam_db' "select status from exam_sessions where session_id='$($sid[3])'"
$activeAfter = @((Api 'GET' '/api/exams/monitoring/active' $ins.token $null).data)[0]
Check "after p3's time ran out: 3 running, p3 is gone (also before the scheduler closes the session)" ($afterExpiry.data.inProgressCount -eq 3 -and (Who $afterExpiry) -eq '5,4,1' -and $activeAfter.inProgressCount -eq 3) "running $($afterExpiry.data.inProgressCount): $(Who $afterExpiry); the session row was $statusP3 at that moment"
Check 'the violations of a finished session can still be read' (@((Api 'GET' "/api/exams/session/$($sid[2])/violations" $ins.token $null).data).Count -eq 2)

# ---------- e) analytics, compared with figures worked out by hand ----------
$qa = Api 'GET' "/api/exams/$idAna/analytics/questions" $ins.token $null
$q = @{}; foreach ($item in @($qa.data.questions)) { $key = if ($item.text -like 'T-b4 essay*') { 'essay' } else { $item.text.Replace('T-b4 ', '') }; $q[$key] = $item }
Check 'question analytics: 11 participants in the figures, 1 session waiting for a grader, 5 questions, generatedAt' ($qa.status -eq 200 -and $qa.data.participantCount -eq 11 -and $qa.data.pendingGradingSessionCount -eq 1 -and @($qa.data.questions).Count -eq 5 -and $qa.data.generatedAt -match 'Z$') "status $($qa.status) $($qa.code); participants $($qa.data.participantCount), pending $($qa.data.pendingGradingSessionCount)"
function Line($x) { "$($x.answeredCount)/$($x.unansweredCount)/$($x.correctCount)/$($x.partiallyCorrectCount)/$('{0:0.0000}' -f [decimal]$x.correctRate)/$('{0:0.0000}' -f [decimal]$x.averageScoreRate)/$($x.difficulty)/$('{0:0.0000}' -f [decimal]$x.discriminationIndex)" }
# answered / unanswered / correct / partial / correctRate / averageScoreRate / difficulty / discrimination (top 3 vs bottom 3 of 11)
$expected = [ordered]@{
    'single'    = '10/1/7/0/0.7000/0.6364/MEDIUM/1.0000'
    'multi'     = '11/0/7/0/0.6364/0.6364/MEDIUM/0.6667'
    'truefalse' = '11/0/7/0/0.6364/0.6364/MEDIUM/0.6667'
    'blank'     = '10/1/5/0/0.5000/0.4545/MEDIUM/1.0000'
    'essay'     = '10/1/1/6/0.1000/0.3409/HARD/0.3333'
}
foreach ($key in $expected.Keys) { Check "question '$key' matches the hand calculation" ((Line $q[$key]) -eq $expected[$key]) "api $(Line $q[$key]); by hand $($expected[$key])" }
function Opts($x) { (@($x.options) | ForEach-Object { "$($_.text):$($_.selectedCount):$('{0:0.0000}' -f [decimal]$_.selectedRate):$($_.isCorrect)" }) -join ' ' }
Check 'options of the single choice: A 7 (0.7), B 2, C 1, with the key' ((Opts $q['single']) -eq 'A:7:0.7000:True B:2:0.2000:False C:1:0.1000:False') (Opts $q['single'])
Check 'options of the multiple choice: W 9, X 8, Y 2, Z 1 of 11' ((Opts $q['multi']) -eq 'W:9:0.8182:True X:8:0.7273:True Y:2:0.1818:False Z:1:0.0909:False') (Opts $q['multi'])
Check 'options of true/false: True 7, False 4' ((Opts $q['truefalse']) -eq 'True:7:0.6364:True False:4:0.3636:False') (Opts $q['truefalse'])
$tops = (@($q['blank'].topAnswers) | ForEach-Object { "$($_.text):$($_.count):$($_.isCorrect)" }) -join ' '
Check 'fill-in-the-blank: most frequent answers, trimmed and lower case, the correct one marked' ($tops -eq 'baku:5:True ganja:3:False baki:1:False sumqayit:1:False') $tops
Check 'open question: 10 graded, 1 waiting, average grade 37.5 %; text cut to 200 characters; no options' ($q['essay'].gradedCount -eq 10 -and $q['essay'].pendingGradingCount -eq 1 -and [decimal]$q['essay'].averageGradePercent -eq 37.5 -and $q['essay'].text.Length -eq 200 -and -not (Has $q['essay'] 'options') -and -not (Has $q['single'] 'gradedCount') -and (Has $q['blank'] 'topAnswers')) "graded $($q['essay'].gradedCount), pending $($q['essay'].pendingGradingCount), average $($q['essay'].averageGradePercent), text length $($q['essay'].text.Length)"
Check 'types, order and maximum points' ((@($qa.data.questions) | ForEach-Object { "$($_.orderIndex):$($_.type):$(([decimal]$_.maxPoints).ToString('0.##', [Globalization.CultureInfo]::InvariantCulture))" }) -join ' ' -eq '0:MULTIPLE_CHOICE:2 1:MULTIPLE_CHOICE:3 2:TRUE_FALSE:1 3:FILL_BLANK:2 4:OPEN_ENDED:4')

$ov = Api 'GET' "/api/exams/$idAna/analytics/overview" $ins.token $null
$o = $ov.data
$dist = (@($o.scoreDistribution) | ForEach-Object { $_.count }) -join ','
Check 'overview counts: 11 in the figures of 12 participants, 13 sessions, 13 completed, 1 waiting, 0 closed by the clock' ($ov.status -eq 200 -and $o.participantCount -eq 11 -and $o.totalParticipantCount -eq 12 -and $o.sessionCount -eq 13 -and $o.completedCount -eq 13 -and $o.inProgressCount -eq 0 -and $o.pendingGradingCount -eq 1 -and $o.expiredOrAutoSubmittedCount -eq 0 -and $o.generatedAt -match 'Z$') "status $($ov.status) $($ov.code); $($o.participantCount)/$($o.totalParticipantCount)/$($o.sessionCount)/$($o.completedCount)/$($o.pendingGradingCount)"
Check 'scoreDistribution by hand (2,0,2,0,2,0,1,1,1,2) and its sum = participantCount' ($dist -eq '2,0,2,0,2,0,1,1,1,2' -and (@($o.scoreDistribution) | Measure-Object count -Sum).Sum -eq $o.participantCount -and @($o.scoreDistribution)[9].label -eq '90-100%' -and @($o.scoreDistribution)[0].from -eq 0) $dist
# By hand: sum 558.34 / 11 = 50.76; the 6th of 11 sorted values = 41.67; 5 of 11 at or above 50 = 45.45
Check 'average 50.76, median 41.67, pass rate 45.45: the waiting session is not in them' ([decimal]$o.averagePercentage -eq 50.76 -and [decimal]$o.medianPercentage -eq 41.67 -and [decimal]$o.passRate -eq 45.45) "average $($o.averagePercentage), median $($o.medianPercentage), pass rate $($o.passRate)"
$dbDuration = Sql 'exam_db' "select round(avg(extract(epoch from (submitted_at - start_time)))) from (select distinct on (user_id) * from exam_sessions where exam_id=$idAna and status='COMPLETED' and grading_status<>'PENDING' order by user_id, percentage desc, submitted_at, id) b"
Check 'attempts per participant 11 / 1 / 0; average duration as in the database' ($o.attemptsPerParticipant.one -eq 11 -and $o.attemptsPerParticipant.two -eq 1 -and $o.attemptsPerParticipant.threeOrMore -eq 0 -and "$($o.averageDurationSeconds)" -eq $dbDuration) "attempts $($o.attemptsPerParticipant.one)/$($o.attemptsPerParticipant.two)/$($o.attemptsPerParticipant.threeOrMore); duration $($o.averageDurationSeconds) s, database $dbDuration"
$bakuToday = [DateTime]::UtcNow.AddHours(4).ToString('yyyy-MM-dd')
$daySum = (@($o.sessionsPerDay) | Measure-Object count -Sum).Sum
Check 'sessionsPerDay: 60 days ending today in Baku, all 13 sessions on the last day' (@($o.sessionsPerDay).Count -eq 60 -and @($o.sessionsPerDay)[59].date -eq $bakuToday -and @($o.sessionsPerDay)[59].count -eq 13 -and $daySum -eq 13) "$(@($o.sessionsPerDay).Count) days, last $(@($o.sessionsPerDay)[59].date) = $(@($o.sessionsPerDay)[59].count), sum $daySum"
# small sample: the monitoring exam has one finished attempt
$small = Api 'GET' "/api/exams/$idMon/analytics/questions" $ins.token $null
$smallQ = @($small.data.questions)[0]
Check 'small sample (fewer than 5 participants): INSUFFICIENT_DATA and no discrimination index' ($small.status -eq 200 -and $small.data.participantCount -ge 1 -and $small.data.participantCount -lt 5 -and -not (@($small.data.questions) | Where-Object { $_.difficulty -ne 'INSUFFICIENT_DATA' }) -and -not (Has $smallQ 'discriminationIndex')) "participants $($small.data.participantCount), difficulty $($smallQ.difficulty)"
$emptyAna = Api 'GET' "/api/exams/$idOther/analytics/overview" $admTwo.token $null
Check 'an exam nobody took: zeros, no average, distribution of ten zero buckets' ($emptyAna.status -eq 200 -and $emptyAna.data.participantCount -eq 0 -and $null -eq $emptyAna.data.averagePercentage -and @($emptyAna.data.scoreDistribution).Count -eq 10 -and (@($emptyAna.data.scoreDistribution) | Measure-Object count -Sum).Sum -eq 0)
# cache: 60 seconds, generatedAt says how old
Sql 'exam_db' 'select 1' | Out-Null
$qaAgain = Api 'GET' "/api/exams/$idAna/analytics/questions" $ins.token $null
$ovAgain = Api 'GET' "/api/exams/$idAna/analytics/overview" $ins.token $null
$anaTtl = [int](docker exec exam-redis redis-cli ttl "exam:read:analytics:questions:${idAna}:$($ins.id)")
Check 'a second call comes from the cache: the same generatedAt, TTL at most 60 seconds' ($qaAgain.data.generatedAt -eq $qa.data.generatedAt -and $ovAgain.data.generatedAt -eq $o.generatedAt -and $anaTtl -gt 0 -and $anaTtl -le 60) "generatedAt $($qaAgain.data.generatedAt), ttl $anaTtl s"
$qaAdmin = Api 'GET' "/api/exams/$idAna/analytics/questions" $admTwo.token $null
Check 'ADMIN gets the same figures for an exam of someone else' ($qaAdmin.status -eq 200 -and $qaAdmin.data.participantCount -eq 11 -and (Line (@($qaAdmin.data.questions)[0])) -eq $expected['single'])

# ---------- g) platform ----------
$plat = Api 'GET' '/api/analytics/overview' $admTwo.token $null
$pd = $plat.data
Check 'platform overview: ADMIN 200, INSTRUCTOR 403, STUDENT 403' ($plat.status -eq 200 -and (Api 'GET' '/api/analytics/overview' $ins.token $null).status -eq 403 -and (Api 'GET' '/api/analytics/overview' $people[1].token $null).status -eq 403) "admin $($plat.status) $($plat.code)"
$dbStatus = (@(Sql 'exam_db' "select status||':'||count(*) from exams where active = true group by status order by status") -join ',')
$apiStatus = (@($pd.examsByStatus.PSObject.Properties | Where-Object { $_.Value -gt 0 } | Sort-Object Name | ForEach-Object { "$($_.Name):$($_.Value)" }) -join ',')
Check 'examsByStatus as in the database, every status present' ($apiStatus -eq $dbStatus -and @($pd.examsByStatus.PSObject.Properties).Count -eq 6) "api $apiStatus"
$fromUtc = [DateTime]::UtcNow.AddHours(4).Date.AddDays(-29).AddHours(-4).ToString('yyyy-MM-dd HH:mm:ss')
$dbPlat = Sql 'exam_db' "select (select count(*) from exam_sessions where status='COMPLETED' and grading_status='PENDING')||'|'||(select count(*) from exam_sessions where start_time >= timestamp '$fromUtc')||'|'||(select count(distinct user_id) from exam_sessions where ((start_time at time zone 'UTC') at time zone 'Asia/Baku')::date = date '$bakuToday')"
$sessionSum = (@($pd.sessionsPerDay) | Measure-Object count -Sum).Sum
Check 'pendingGradingTotal, the sum of sessionsPerDay (30 days) and the participants of today as in the database' ("$($pd.pendingGradingTotal)|$sessionSum|$(@($pd.uniqueParticipantsPerDay)[29].count)" -eq $dbPlat -and @($pd.sessionsPerDay).Count -eq 30 -and @($pd.sessionsPerDay)[29].date -eq $bakuToday -and $pd.days -eq 30 -and $pd.generatedAt -match 'Z$') "api $($pd.pendingGradingTotal)|$sessionSum|$(@($pd.uniqueParticipantsPerDay)[29].count); database $dbPlat"
$dbOverall = Sql 'exam_db' "select round(avg(percentage),2)||'|'||round(100.0*count(*) filter (where passed)/count(*),2) from (select distinct on (exam_id, user_id) percentage, passed from exam_sessions where status='COMPLETED' and grading_status<>'PENDING' order by exam_id, user_id, percentage desc nulls last, submitted_at, id) b"
Check 'overall average and pass rate (best attempt per exam and participant) as in the database' ("$('{0:0.00}' -f [decimal]$pd.averagePercentageOverall)|$('{0:0.00}' -f [decimal]$pd.passRateOverall)" -eq $dbOverall) "api $($pd.averagePercentageOverall)|$($pd.passRateOverall); database $dbOverall"
# Which exams lead depends on the data (a cleanup removes test exams): id, sessions, participants and title come from the database
$dbTop = (@(Sql 'exam_db' "select c.exam_id||':'||c.sessions||':'||c.participants||':'||x.title from (select exam_id, count(*) sessions, count(distinct user_id) participants from exam_sessions where start_time >= timestamp '$fromUtc' group by exam_id order by count(*) desc, exam_id limit 5) c join exams x on x.id = c.exam_id order by c.sessions desc, x.id") -join ',')
$apiTop = (@($pd.topExamsBySessions) | ForEach-Object { "$($_.examId):$($_.sessionCount):$($_.participantCount):$($_.title)" }) -join ','
Check 'topExamsBySessions: the five exams with the most sessions, with title and participants, as in the database' (@($pd.topExamsBySessions).Count -eq 5 -and $apiTop -eq $dbTop) "api $apiTop; database $dbTop"
Check 'activeNowInProgressSessions is a real count' ($pd.activeNowInProgressSessions -ge 3 -and $pd.activeNowInProgressSessions -le [int](Sql 'exam_db' "select count(*) from exam_sessions where status='IN_PROGRESS'") + 2) "$($pd.activeNowInProgressSessions)"
$dbUsers = Sql 'exam_auth' "select count(*)||'|'||count(*) filter (where email_verified)||'|'||count(*) filter (where not coalesce(email_verified,false))||'|'||count(*) filter (where account_locked)||'|'||count(*) filter (where created_at >= timestamp '$fromUtc') from users"
$regSum = (@($pd.users.registrationsPerDay) | Measure-Object count -Sum).Sum
Check 'user figures from auth-service: total, verified, unverified, locked as in its database; registrations add up' ($pd.usersAvailable -eq $true -and "$($pd.users.totalUsers)|$($pd.users.verifiedUsers)|$($pd.users.unverifiedUsers)|$($pd.users.lockedUsers)|$regSum" -eq $dbUsers -and @($pd.users.registrationsPerDay).Count -eq 30 -and @($pd.users.registrationsPerDay)[29].date -eq $bakuToday) "api $($pd.users.totalUsers)|$($pd.users.verifiedUsers)|$($pd.users.unverifiedUsers)|$($pd.users.lockedUsers)|$regSum; database total|verified|unverified|locked|registered in 30 days $dbUsers"
Check 'the user figures are numbers only: no email, name or id of a user' ($plat.raw -notmatch '@x\.test|firstName|lastName|"email"' -and (($pd.users.PSObject.Properties.Name | Sort-Object) -join ',') -eq 'lockedUsers,registrationsPerDay,totalUsers,unverifiedUsers,verifiedUsers')
Check 'days=7 gives 7 days; days=0 and days=91 are 400' (@((Api 'GET' '/api/analytics/overview?days=7' $admTwo.token $null).data.sessionsPerDay).Count -eq 7 -and (Api 'GET' '/api/analytics/overview?days=0' $admTwo.token $null).status -eq 400 -and (Api 'GET' '/api/analytics/overview?days=91' $admTwo.token $null).status -eq 400)
$platAgain = Api 'GET' '/api/analytics/overview' $admTwo.token $null
Check 'a second call comes from the cache (same generatedAt)' ($platAgain.data.generatedAt -eq $pd.generatedAt)
# the internal endpoint needs the shared secret
function Internal($headers) { try { [int](Invoke-WebRequest -Uri 'http://127.0.0.1:8081/internal/users/stats' -Method Post -Headers $headers -ContentType 'application/json' -Body '{"days":30}' -UseBasicParsing -TimeoutSec 15).StatusCode } catch { if ($_.Exception.Response) { [int]$_.Exception.Response.StatusCode } else { 0 } } }
$noSecret = Internal @{}; $badSecret = Internal @{ 'X-Internal-Secret' = ('x' * 40) }; $viaGateway = (Api 'POST' '/internal/users/stats' $admTwo.token @{ days = 30 }).status
Check '/internal/users/stats without or with a wrong secret is refused, and the gateway does not route it' ($noSecret -in 401, 403, 404 -and $badSecret -in 401, 403, 404 -and $viaGateway -in 401, 403, 404) "no secret $noSecret, wrong secret $badSecret, through the gateway $viaGateway"

# auth-service stopped for a short while: the overview still answers, without the user figures
docker-compose stop auth-service 2>&1 | Out-Null
Start-Sleep -Seconds 2
$noAuth = Api 'GET' '/api/analytics/overview?days=29' $admTwo.token $null
Check 'auth-service stopped: overview 200, usersAvailable=false, users null, everything else there' ($noAuth.status -eq 200 -and $noAuth.data.usersAvailable -eq $false -and $null -eq $noAuth.data.users -and @($noAuth.data.sessionsPerDay).Count -eq 29 -and @($noAuth.data.topExamsBySessions).Count -eq 5 -and $noAuth.data.pendingGradingTotal -eq $pd.pendingGradingTotal) "status $($noAuth.status) $($noAuth.code) in $($noAuth.ms) ms, usersAvailable $($noAuth.data.usersAvailable)"
$noAuthMonitor = Monitor '' $ins.token
Check 'auth-service stopped: monitoring still answers, only the names are missing' ($noAuthMonitor.status -eq 200 -and $noAuthMonitor.data.inProgressCount -eq 3) "status $($noAuthMonitor.status), names: $((@($noAuthMonitor.data.sessions.content) | ForEach-Object { if ($_.participantName) { 'yes' } else { 'no' } }) -join ',')"
docker-compose start auth-service 2>&1 | Out-Null
$authUp = $false
for ($i = 0; $i -lt 60; $i++) { try { if ((Invoke-RestMethod 'http://127.0.0.1:8081/actuator/health' -TimeoutSec 3).status -eq 'UP') { $authUp = $true; break } } catch { }; Start-Sleep -Seconds 3 }
Start-Sleep -Seconds 3
$authBack = Api 'GET' '/api/analytics/overview?days=29' $admTwo.token $null
Check 'auth-service started again: the user figures are back at once (the incomplete answer was not cached)' ($authUp -and $authBack.status -eq 200 -and $authBack.data.usersAvailable -eq $true -and $authBack.data.users.totalUsers -eq $pd.users.totalUsers) "auth up $authUp; usersAvailable $($authBack.data.usersAvailable)"
$loginAgain = Login $people[6].email $password
Check 'regress: login works again after the restart of auth-service' ($loginAgain.status -eq 200) "status $($loginAgain.status)"

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
Write-Output "exam ids: mon=$idMon ana=$idAna other=$idOther; stamp=$stamp; running sessions left: p1, p4, p5 of the monitoring exam"
