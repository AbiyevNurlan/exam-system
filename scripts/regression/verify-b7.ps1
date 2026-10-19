# Live verification of feat/admin-api-gaps (B7): group list figures, group exams, member search, exam
# audience fields, preview without an exam, result filters and sort, CSV export, grading overview,
# audit list, start before the start time, permissions, timings.
# Admin credentials are read from F:\exam-system\.env into variables only; nothing secret is printed.
# Creates T-b7-<stamp>-* groups and exams and be-b7-*-<stamp>@x.test accounts; deletes only the one
# T-b7 exam it creates for that purpose (to check that a deleted exam is not counted).
param([switch]$SkipExportLimit)
$ErrorActionPreference = 'Continue'
[Threading.Thread]::CurrentThread.CurrentCulture = [Globalization.CultureInfo]::InvariantCulture
Set-Location F:\exam-system
$GW = 'http://127.0.0.1:8080'
. "$PSScriptRoot\_common.ps1"
Wait-Gateway $GW
$MAIL = 'http://127.0.0.1:8025'
$stamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString('x')
$T = "T-b7-$stamp"
$script:results = @()
$script:throttled = 0
$script:otps = @()

function Check($name, $ok, $detail = '') {
    $script:results += [pscustomobject]@{ name = $name; ok = [bool]$ok }
    $line = $(if ($ok) { 'PASS' } else { 'FAIL' }) + "  $name"
    if ($detail -ne '') { $line += "  ($detail)" }
    Write-Output $line
}
# The gateway limits requests per client IP, and this IP is shared with whoever uses the frontend on
# this machine: a 429 is waited out and the request repeated (at most 15 times)
function Api($method, $path, $token, $body) {
    for ($attempt = 0; $attempt -lt 15; $attempt++) {
        $r = ApiOnce $method $path $token $body
        if ($r.status -ne 429) { return $r }
        $script:throttled++
        Start-Sleep -Milliseconds 1500
    }
    return $r
}
function ApiOnce($method, $path, $token, $body) {
    $headers = @{}
    if ($token) { $headers['Authorization'] = "Bearer $token" }
    $params = @{ Uri = "$GW$path"; Method = $method; Headers = $headers; UseBasicParsing = $true; TimeoutSec = 120 }
    if ($null -ne $body) {
        $json = if ($body -is [string]) { $body } else { $body | ConvertTo-Json -Depth 10 }
        $params['Body'] = [Text.Encoding]::UTF8.GetBytes($json); $params['ContentType'] = 'application/json'
    }
    $watch = [Diagnostics.Stopwatch]::StartNew()
    try {
        $r = Invoke-WebRequest @params
        $raw = $r.Content
        if ($raw -is [byte[]]) { $raw = [Text.Encoding]::UTF8.GetString($raw) }
        $status = [int]$r.StatusCode
    } catch {
        $resp = $_.Exception.Response
        if (-not $resp) { return @{ status = 0; raw = $_.Exception.Message; json = $null; data = $null; code = $null; ms = $watch.ElapsedMilliseconds } }
        $status = [int]$resp.StatusCode
        $stream = $resp.GetResponseStream()
        if ($stream.CanSeek) { $stream.Position = 0 }
        $raw = (New-Object IO.StreamReader($stream)).ReadToEnd()
    }
    $watch.Stop()
    $json = $null
    if ($raw) { try { $json = $raw | ConvertFrom-Json } catch { $json = $null } }
    $data = $json; $code = $null
    if ($json -and ($json.PSObject.Properties.Name -contains 'data')) { $data = $json.data }
    if ($json -and $json.error) { $code = $json.error.code }
    return @{ status = $status; raw = $raw; json = $json; data = $data; code = $code; ms = $watch.ElapsedMilliseconds }
}
# A download: status, headers and the raw bytes
function Download($path, $token) {
    $request = [Net.HttpWebRequest]::Create("$GW$path")
    $request.Headers.Add('Authorization', "Bearer $token")
    $request.Timeout = 120000
    try { $response = $request.GetResponse() } catch { $response = $_.Exception.InnerException.Response; if (-not $response) { $response = $_.Exception.Response } }
    if (-not $response) { return @{ status = 0 } }
    $memory = New-Object IO.MemoryStream
    # A stream that breaks after the headers (the server failed half way) is reported, not thrown
    $broken = $false
    try { $response.GetResponseStream().CopyTo($memory) } catch { $broken = $true }
    $result = @{ status = [int]$response.StatusCode; type = $response.ContentType; disposition = $response.Headers['Content-Disposition']
                 total = $response.Headers['X-Total-Count']; bytes = $memory.ToArray(); broken = $broken }
    $response.Close()
    return $result
}
function Sql($db, $query) { docker exec exam-postgres psql -U postgres -d $db -Atc $query }
function Has($object, $name) { $object -and ($object.PSObject.Properties.Name -contains $name) }
function MailedCode($email) {
    for ($i = 0; $i -lt 40; $i++) {
        $found = Invoke-RestMethod "$MAIL/api/v1/search?query=to:$email"
        if ($found.messages_count -gt 0) {
            $message = Invoke-RestMethod "$MAIL/api/v1/message/$($found.messages[0].ID)"
            if ($message.Text -match '(?<!\d)(\d{6})(?!\d)') { $script:otps += $Matches[1]; return $Matches[1] }
        }
        Start-Sleep -Milliseconds 500
    }
    throw "no mail for $email"
}
$password = 'Tt9!' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
function Register($name, $first, $last) {
    $email = "be-b7-$name-$stamp@x.test"
    for ($try = 0; $try -lt 8; $try++) {
        $r = Api 'POST' '/auth/register' $null @{ email = $email; password = $password; firstName = $first; lastName = $last }
        if ($r.status -ne 429) { break }
        Start-Sleep -Seconds 6
    }
    if ($r.status -ne 201) { throw "register $name answered $($r.status)" }
    $code = MailedCode $email
    for ($try = 0; $try -lt 8; $try++) {
        $v = Api 'POST' '/auth/verify-otp' $null @{ email = $email; code = $code }
        if ($v.status -ne 429) { break }
        Start-Sleep -Seconds 6
    }
    if ($v.status -ne 200) { throw "verify $name answered $($v.status)" }
    return @{ email = $email; id = $v.data.user.id; token = $v.data.accessToken; first = $first; last = $last }
}
function Login($email, $pw) { (Api 'POST' '/auth/login' $null @{ email = $email; password = $pw }) }
function UtcText([DateTime]$utc) { $utc.ToString('yyyy-MM-ddTHH:mm:ss') }
function Option($text, $correct, $order) { @{ optionText = $text; isCorrect = $correct; orderIndex = $order } }
function Mc($text, $points, $order) { @{ questionText = $text; questionType = 'MULTIPLE_CHOICE'; points = $points; orderIndex = $order; allowMultipleAnswers = $false; options = @((Option 'wrong' $false 0), (Option 'right' $true 1)) } }
function Open($text, $points, $order) { @{ questionText = $text; questionType = 'OPEN_ENDED'; points = $points; orderIndex = $order } }
function ExamBody($title, [DateTime]$startUtc, [DateTime]$endUtc, $duration, $questions, $groups, $perDay) {
    $body = @{ title = $title; description = 'B7 verification'; durationMinutes = $duration; startTime = (UtcText $startUtc); endTime = (UtcText $endUtc)
               shuffleQuestions = $false; shuffleOptions = $false; showResultsImmediately = $true; maxAttempts = 3; passingScore = 50; questions = $questions }
    if ($groups) { $body['accessType'] = 'GROUP'; $body['groupIds'] = @($groups) }
    if ($perDay) { $body['maxAttemptsPerDay'] = $perDay }
    return $body
}
function Answer($started, $token, $needle, $choice, $text) {
    $q = @($started.data.questions) | Where-Object { $_.questionText -like "*$needle*" }
    $body = @{ questionId = $q.id }
    if ($null -ne $choice) { $body['selectedOptionIds'] = @(($q.options | Where-Object { $_.optionText -eq $choice }).id) } else { $body['textAnswer'] = $text }
    return (Api 'POST' "/api/exams/session/$($started.data.sessionId)/answer" $token $body)
}
function Begin($examId, $token) { (Api 'POST' "/api/exams/$examId/start" $token @{}) }
function Finish($sessionId, $token) { (Api 'POST' "/api/exams/session/$sessionId/submit" $token $null) }
function Fraud($sessionId, $token, $type, $times) { for ($i = 0; $i -lt $times; $i++) { $null = Api 'POST' "/api/exams/session/$sessionId/fraud-event" $token @{ type = $type } } }
function Median($values) { $sorted = @($values | Sort-Object); $sorted[[int][Math]::Floor($sorted.Count / 2)] }
# Restart exam-service through scripts\up.ps1 and wait for the NEW container to serve (the old one
# may still answer for a moment after up -d returns)
# The gateway's circuit breaker opens when calls fail (e.g. during a restart) and answers with a
# fallback for a while: wait until five calls in a row reach exam-service
function WaitReady() {
    $streak = 0
    for ($i = 0; $i -lt 60 -and $streak -lt 5; $i++) {
        $r = Api 'GET' '/api/grading/pending-count' $adm.token $null
        if ($r.status -eq 200 -and $r.json.success -eq $true) { $streak++ } else { $streak = 0; Start-Sleep 2 }
    }
    return $streak -ge 5
}
function RestartExamService() {
    $before = docker inspect -f '{{.State.StartedAt}}' exam-exam-service
    & .\scripts\up.ps1 exam-service | Out-Null
    for ($i = 0; $i -lt 60; $i++) {
        Start-Sleep 3
        $now = docker inspect -f '{{.State.StartedAt}}' exam-exam-service
        if ($now -ne $before -and (docker logs exam-exam-service --tail 200 2>&1 | Select-String 'Started ExamServiceApplication')) { break }
    }
    $null = WaitReady
}

# ---------- setup ----------
$adminEmail = $null; $adminPassword = $null
foreach ($line in Get-Content F:\exam-system\.env) {
    if ($line -match '^ADMIN_EMAIL=(.*)$') { $adminEmail = $Matches[1].Trim() }
    if ($line -match '^ADMIN_PASSWORD=(.*)$') { $adminPassword = $Matches[1].Trim() }
}
$adminLogin = Login $adminEmail $adminPassword
if ($adminLogin.status -ne 200) { throw "admin login answered $($adminLogin.status)" }
$adm = @{ id = $adminLogin.data.user.id; token = $adminLogin.data.accessToken }
if (-not (WaitReady)) { throw 'exam-service is not reachable through the gateway' }

$pA = Register 'a' "Nb7${stamp}a" "Aa$stamp"
$pB = Register 'b' "Nb7${stamp}b" "Bb$stamp"
$pC = Register 'c' "Nb7${stamp}c" "Cc$stamp"
$pD = Register 'd' "Nb7${stamp}d" "Dd$stamp"
$insZ = Register 'z' "Zz$stamp" "Owner$stamp"
$insY = Register 'y' "Yy$stamp" "Other$stamp"
# Every role given below is taken back in the finally block before the summary
try {
Sql 'exam_auth' "update users set role='INSTRUCTOR' where email in ('$($insZ.email)','$($insY.email)')" | Out-Null
# A name that a spreadsheet would read as a formula (own test account only)
Sql 'exam_auth' "update users set first_name='=SUM(1+1)' where email='$($pD.email)'" | Out-Null
$insZ.token = (Login $insZ.email $password).data.accessToken
$insY.token = (Login $insY.email $password).data.accessToken
Write-Output "run $stamp; accounts a, b, c, d (students), z, y (INSTRUCTOR for the test) be-b7-*-$stamp@x.test"

# ---------- groups ----------
$g1 = (Api 'POST' '/api/admin/groups' $adm.token @{ name = "$T-g1" }).data.id
$g2 = (Api 'POST' '/api/admin/groups' $adm.token @{ name = "$T-g2" }).data.id
$g3 = (Api 'POST' '/api/admin/groups' $adm.token @{ name = "$T-g3" }).data.id
foreach ($p in $pA, $pB, $pD) { $null = Api 'POST' "/api/admin/groups/$g1/members" $adm.token @{ userId = $p.id } }
foreach ($p in $pB, $pC) { $null = Api 'POST' "/api/admin/groups/$g2/members" $adm.token @{ userId = $p.id } }
# B8: an INSTRUCTOR picks only the own groups for an audience, so z owns the group of E5 (same members as g2).
# Its name is outside "$T-" on purpose: the group list and audit checks below count the admin's three groups.
$gz = (Api 'POST' '/api/admin/groups' $insZ.token @{ name = "T-b7z-$stamp-own" }).data.id
foreach ($p in $pB, $pC) { $null = Api 'POST' "/api/admin/groups/$gz/members" $insZ.token @{ userId = $p.id } }
$invite = Api 'POST' "/api/admin/groups/$g1/members" $adm.token @{ email = "be-b7-inv-$stamp@x.test" }
$link = Api 'POST' "/api/groups/$g1/join-link" $adm.token @{ requiresApproval = $true; maxUses = 10 }
$joinCode = $link.data.code
$joinC = Api 'POST' "/api/join/$joinCode" $pC.token $null
Check 'setup: 3 groups, members, an invite, an approval link and a request; instructor z owns a group of 2' ($g1 -and $g2 -and $g3 -and $gz -and (Sql 'exam_db' "select count(*) from user_group_members where group_id=$gz") -eq '2' -and $invite.data.status -eq 'INVITED' -and $joinC.data.status -eq 'PENDING') "g1 $g1, g2 $g2, g3 $g3, gz $gz"

# Exams: E1 (admin, GROUP g1+g2, results), E2 (admin, PUBLIC), E3 (admin, GROUP g1, draft), E4 (GROUP g1, deleted)
$start = [DateTime]::UtcNow.AddSeconds(20); $start = $start.AddTicks(-($start.Ticks % [TimeSpan]::TicksPerSecond))
$questions = @((Mc "$T q1" 2 0), @{ questionText = "$T q2"; questionType = 'TRUE_FALSE'; points = 2; orderIndex = 1; allowMultipleAnswers = $false; options = @((Option 'Doğru' $true 0), (Option 'Yanlış' $false 1)) }, (Open "$T q3" 1 2))
$e1 = (Api 'POST' '/api/exams' $adm.token (ExamBody "$T-e1" $start $start.AddHours(6) 30 $questions @($g1, $g2) $null)).data.id
$e2 = (Api 'POST' '/api/exams' $adm.token (ExamBody "$T-e2" $start $start.AddHours(6) 30 @((Mc "$T p1" 1 0)) $null $null)).data.id
$e3 = (Api 'POST' '/api/exams' $adm.token (ExamBody "$T-e3" $start.AddDays(1) $start.AddDays(2) 30 @((Mc "$T d1" 1 0)) @($g1) $null)).data.id
$e4 = (Api 'POST' '/api/exams' $adm.token (ExamBody "$T-e4" $start.AddDays(1) $start.AddDays(2) 30 @((Mc "$T x1" 1 0)) @($g1) $null)).data.id
$e4Deleted = (Api 'DELETE' "/api/exams/$e4" $adm.token $null).status
# E5 (instructor z, GROUP gz = z's own group, one minute: runs out), E6 (admin, GROUP g1, starts in 2 hours, daily limit 1)
$e5 = (Api 'POST' '/api/exams' $insZ.token (ExamBody "$T-e5" $start $start.AddHours(6) 1 @((Open "$T o1" 2 0), (Mc "$T m1" 1 1)) @($gz) $null)).data.id
$e6 = (Api 'POST' '/api/exams' $adm.token (ExamBody "$T-e6" ([DateTime]::UtcNow.AddHours(2)) ([DateTime]::UtcNow.AddHours(4)) 30 @((Mc "$T s1" 1 0)) @($g1) 1)).data.id
# E1, E2 and E5 are published now; E3 stays a draft; E6 is published in part 7
$published = @($e1, $e2) | ForEach-Object { (Api 'POST' "/api/exams/$_/publish" $adm.token $null).status }
$published += (Api 'POST' "/api/exams/$e5/publish" $insZ.token $null).status
Check 'setup: exams created, E4 deleted, E1 E2 E5 published' ($e1 -and $e2 -and $e3 -and $e5 -and $e6 -and $e4Deleted -eq 204 -and ($published -join ',') -eq '200,200,200') "e1 $e1, e2 $e2, e3 $e3, e4 $e4 (deleted: $e4Deleted), e5 $e5, e6 $e6; publish $($published -join ',')"

# ---------- 1. group list ----------
$list = Api 'GET' "/api/admin/groups?name=$T&size=50" $adm.token $null
$rows = @($list.data.content)
$expected = Sql 'exam_db' @"
select g.id||'|'||(select count(*) from user_group_members where group_id=g.id)||'|'||(select count(*) from pending_group_invites where group_id=g.id)
  ||'|'||(select count(*) from group_join_requests where group_id=g.id and status='PENDING')
  ||'|'||(select count(*) from exam_audience a join exams e on e.id=a.exam_id and e.active and e.access_type='GROUP' where a.group_id=g.id)
  ||'|'||coalesce((select l.enabled::text||'|'||l.requires_approval::text||'|'||l.uses||'|'||coalesce(l.max_uses::text,'') from group_join_links l where l.group_id=g.id),'none')
from user_groups g where g.name like '$T-%' and g.active order by lower(g.name)
"@
$actual = $rows | ForEach-Object {
    $link = if ($_.joinLink.exists) { "$($_.joinLink.enabled.ToString().ToLower())|$($_.joinLink.requiresApproval.ToString().ToLower())|$($_.joinLink.uses)|$($_.joinLink.maxUses)" } else { 'none' }
    "$($_.id)|$($_.memberCount)|$($_.pendingInviteCount)|$($_.pendingJoinRequests)|$($_.examCount)|$link"
}
Check 'group list: counts and link state per row equal hand-written SQL' (($expected -join ';') -eq ($actual -join ';')) (($actual -join ' ; '))
$row1 = $rows | Where-Object { $_.id -eq $g1 }
Check 'group list: g1 = 3 members, 1 invite, 1 open request, 3 exams (E1, E3, E6; deleted E4 not counted), usable approval link' ($row1.memberCount -eq 3 -and $row1.pendingInviteCount -eq 1 -and $row1.pendingJoinRequests -eq 1 -and $row1.examCount -eq 3 -and $row1.joinLink.usable -eq $true -and $row1.joinLink.requiresApproval -eq $true) ($row1 | ConvertTo-Json -Compress -Depth 4)
$row3 = $rows | Where-Object { $_.id -eq $g3 }
Check 'group list: a group without a link says exists=false (no 404 call needed)' ($row3.joinLink.exists -eq $false -and -not (Has $row3.joinLink 'enabled')) ($row3.joinLink | ConvertTo-Json -Compress)
$single = Api 'GET' "/api/admin/groups/$g1" $adm.token $null
Check 'single group: same figures as its row' ($single.data.memberCount -eq 3 -and $single.data.examCount -eq 3 -and $single.data.pendingJoinRequests -eq 1 -and $single.data.joinLink.exists)
Check 'old link endpoint unchanged: a group without a link still answers 404 JOIN_LINK_NOT_FOUND' ((Api 'GET' "/api/groups/$g3/join-link" $adm.token $null).code -eq 'JOIN_LINK_NOT_FOUND')
$times = @(); for ($i = 0; $i -lt 7; $i++) { $times += (Api 'GET' '/api/admin/groups?size=50' $adm.token $null).ms }
$all = Api 'GET' '/api/admin/groups?size=50' $adm.token $null
$biggest = (@($all.data.content) | Measure-Object -Property memberCount -Maximum).Maximum
Check 'group list: a page of 50 with 20 000+ participants in the database under 300 ms (median of 7)' ((Median $times) -lt 300) "median $(Median $times) ms, all: $($times -join ', '); largest group on the page $biggest members; $($all.data.totalElements) groups"

$exams = Api 'GET' "/api/admin/groups/$g1/exams" $adm.token $null
$examIds = @($exams.data.content | ForEach-Object { $_.id }) | Sort-Object
Check 'group exams: E1 and E3 (and E6), not the deleted E4' (($examIds -join ',') -eq ((@($e1, $e3, $e6) | Sort-Object) -join ',') -and $exams.data.totalElements -eq 3) "ids $($examIds -join ','); fields $((@($exams.data.content)[0].PSObject.Properties.Name) -join ',')"
Check 'group exams: unknown group 404' ((Api 'GET' '/api/admin/groups/99999999/exams' $adm.token $null).code -eq 'GROUP_NOT_FOUND')

$search = Api 'GET' "/api/admin/groups/$g1/members?q=Nb7${stamp}a" $adm.token $null
$hit = @($search.data.content)[0]
Check 'member search: by first name finds A only, with source, addedAt and a masked email' ($search.data.totalElements -eq 1 -and $hit.userId -eq $pA.id -and $hit.source -eq 'ADMIN' -and $hit.addedAt -match 'Z$' -and $hit.email -match '\*\*\*') "$($hit.email) $($hit.source) $($hit.addedAt)"
$byEmail = Api 'GET' "/api/admin/groups/$g1/members?q=be-b7-b-$stamp" $adm.token $null
Check 'member search: by email prefix' ($byEmail.data.totalElements -eq 1 -and @($byEmail.data.content)[0].userId -eq $pB.id)
Check 'member search: not a member of this group is not found' ((Api 'GET' "/api/admin/groups/$g1/members?q=Nb7${stamp}c" $adm.token $null).data.totalElements -eq 0)
Check 'member search: one character is 400' ((Api 'GET' "/api/admin/groups/$g1/members?q=N" $adm.token $null).status -eq 400)
# A big group: compare with the two databases
$bulk = Sql 'exam_db' "select g.id from user_groups g where g.active and g.name like 'T-f2-%-bulk' order by g.id desc limit 1"
if ($bulk) {
    $ids = Sql 'exam_db' "select string_agg(quote_literal(user_id::text), ',') from user_group_members where group_id=$bulk"
    # The id list is too long for a command line: the statement goes in through stdin
    $want = "select count(*) from users where id::text in ($ids) and role='STUDENT' and email like 't-b5-191%';" | docker exec -i exam-postgres psql -U postgres -d exam_auth -At
    $got = Api 'GET' "/api/admin/groups/$bulk/members?q=t-b5-191&size=50" $adm.token $null
    Check 'member search: in a group of 1 000 the total equals SQL over both databases' ($got.data.totalElements -eq [int]$want) "group $bulk, q=t-b5-191: api $($got.data.totalElements), sql $want, $($got.ms) ms"
}

# ---------- 2. exam audience ----------
$manage = Api 'GET' "/api/exams/manage?search=$T&size=50" $adm.token $null
$m1 = @($manage.data.content) | Where-Object { $_.id -eq $e1 }
$m2 = @($manage.data.content) | Where-Object { $_.id -eq $e2 }
$oldKeys = 'id', 'title', 'status', 'accessType', 'startTime', 'endTime', 'durationMinutes', 'maxAttempts', 'maxAttemptsPerDay', 'totalQuestions', 'totalPoints', 'sessionCount', 'participantCount', 'createdBy', 'createdAt'
Check 'manage list: E1 GROUP with audienceGroupCount 2 and sorted groupIds; E2 PUBLIC with 0 and []' ($m1.accessType -eq 'GROUP' -and $m1.audienceGroupCount -eq 2 -and (@($m1.groupIds) -join ',') -eq ((@($g1, $g2) | Sort-Object) -join ',') -and $m2.audienceGroupCount -eq 0 -and @($m2.groupIds).Count -eq 0) "E1 $(@($m1.groupIds) -join ','), E2 $($m2.audienceGroupCount)"
Check 'manage list: every old field is still there' (-not ($oldKeys | Where-Object { $_ -notin $m1.PSObject.Properties.Name })) (($m1.PSObject.Properties.Name) -join ',')
$byId = Api 'GET' "/api/exams/$e1" $adm.token $null
Check 'GET /api/exams/{id} (admin): accessType, audienceGroupCount, groupIds and the questions with the key' ($byId.data.accessType -eq 'GROUP' -and $byId.data.audienceGroupCount -eq 2 -and @($byId.data.groupIds).Count -eq 2 -and @($byId.data.questions).Count -eq 3 -and (Has @($byId.data.questions)[0].options[0] 'isCorrect')) "$($byId.data.audienceGroupCount) $(@($byId.data.groupIds) -join ',')"
Start-Sleep -Seconds ([Math]::Max(0, ($start - [DateTime]::UtcNow).TotalSeconds + 2))
$asStudent = Api 'GET' "/api/exams/$e1" $pA.token $null
Check 'GET /api/exams/{id} (participant): no audience fields' ($asStudent.status -eq 200 -and -not (Has $asStudent.data 'groupIds') -and -not (Has $asStudent.data 'audienceGroupCount')) "status $($asStudent.status); $(($asStudent.data.PSObject.Properties.Name) -join ',')"
Check 'GET /api/exams/{id}: an instructor outside the audience gets 404 as before' ((Api 'GET' "/api/exams/$e1" $insY.token $null).status -eq 404)

$preview = Api 'POST' '/api/exams/audience/preview' $adm.token @{ groupIds = @($g1, $g2, 99999999) }
$distinct = Sql 'exam_db' "select count(distinct user_id) from user_group_members where group_id in ($g1,$g2)"
$per = (@($preview.data.perGroup) | Sort-Object groupId | ForEach-Object { "$($_.groupId)=$($_.members)" }) -join ','
Check 'preview without exam: 3 + 2 members, B in both counts once (4), unknown group listed' ($preview.status -eq 200 -and $preview.data.distinctEligible -eq [int]$distinct -and $preview.data.distinctEligible -eq 4 -and $per -eq "$g1=3,$g2=2" -and (@($preview.data.unknownGroupIds) -join ',') -eq '99999999') "$per distinct $($preview.data.distinctEligible) (sql $distinct)"
$stored = Api 'GET' "/api/exams/$e1/audience/preview" $adm.token $null
Check 'preview without exam: same numbers as the stored audience of E1' ($stored.data.distinctEligible -eq $preview.data.distinctEligible)
Check 'preview without exam: instructor 200, participant 403, 51 groups 400, empty list zeros' ((Api 'POST' '/api/exams/audience/preview' $insZ.token @{ groupIds = @($g1) }).status -eq 200 -and (Api 'POST' '/api/exams/audience/preview' $pA.token @{ groupIds = @($g1) }).status -eq 403 -and (Api 'POST' '/api/exams/audience/preview' $adm.token @{ groupIds = @(1..51) }).status -eq 400 -and (Api 'POST' '/api/exams/audience/preview' $adm.token @{ groupIds = @() }).data.distinctEligible -eq 0)

# ---------- 3. results ----------
$sA = Begin $e1 $pA.token; $null = Answer $sA $pA.token 'q1' 'right' $null; $null = Answer $sA $pA.token 'q2' 'Doğru' $null; $null = Answer $sA $pA.token 'q3' $null 'B7 answer of A'
Fraud $sA.data.sessionId $pA.token 'DEVTOOLS' 6
$sB = Begin $e1 $pB.token; $null = Answer $sB $pB.token 'q1' 'wrong' $null; $null = Answer $sB $pB.token 'q2' 'Doğru' $null; $null = Answer $sB $pB.token 'q3' $null 'B7 answer of B'
Fraud $sB.data.sessionId $pB.token 'TAB_SWITCH' 5
$sC = Begin $e1 $pC.token; $null = Answer $sC $pC.token 'q1' 'right' $null; $null = Answer $sC $pC.token 'q2' 'Yanlış' $null
Fraud $sC.data.sessionId $pC.token 'FULLSCREEN_EXIT' 8
$sD = Begin $e1 $pD.token
foreach ($s in @(@($sA, $pA), @($sB, $pB), @($sC, $pC), @($sD, $pD))) { $null = Finish $s[0].data.sessionId $s[1].token }
# E5: C answers the open question and lets the minute run out
$s5 = Begin $e5 $pC.token; $null = Answer $s5 $pC.token 'o1' $null 'B7 open answer, then time runs out'
Check 'results setup: four sessions in E1, one running in E5' ($sA.status -eq 200 -and $sD.status -eq 200 -and $s5.status -eq 200) "$($sA.status) $($sB.status) $($sC.status) $($sD.status) / $($s5.status)"

$weights = "CASE e.type WHEN 'TAB_SWITCH' THEN 1 WHEN 'WINDOW_BLUR' THEN 1 WHEN 'FULLSCREEN_EXIT' THEN 2 WHEN 'COPY_ATTEMPT' THEN 2 WHEN 'PASTE_ATTEMPT' THEN 2 WHEN 'DEVTOOLS' THEN 5 ELSE 1 END"
$levels = Sql 'exam_db' @"
select s.session_id||'='||(case when coalesce(sum($weights),0) >= 30 then 'CRITICAL' when coalesce(sum($weights),0) >= 15 then 'HIGH' when coalesce(sum($weights),0) >= 5 then 'MEDIUM' else 'LOW' end)||'='||coalesce(s.percentage::text,'')
from exam_sessions s left join session_fraud_events e on e.session_id=s.id where s.exam_id=$e1 group by s.id order by s.session_id
"@
$byLevel = @{}; $percent = @{}
foreach ($line in $levels) { $parts = $line -split '='; $byLevel[$parts[0]] = $parts[1]; $percent[$parts[0]] = [double]$parts[2] }
function Sessions($query) { @((Api 'GET' "/api/exams/$e1/results$query" $adm.token $null).data.content | ForEach-Object { $_.sessionId }) | Sort-Object }
$okLevels = $true; $levelDetail = @()
foreach ($level in 'LOW', 'MEDIUM', 'HIGH', 'CRITICAL') {
    $api = (Sessions "?riskLevel=$level") -join ','
    $db = (@($byLevel.Keys | Where-Object { $byLevel[$_] -eq $level }) | Sort-Object) -join ','
    if ($api -ne $db) { $okLevels = $false }
    $levelDetail += "$level api=$(@($api -split ',' | Where-Object { $_ }).Count) db=$(@($db -split ',' | Where-Object { $_ }).Count)"
}
Check 'results: riskLevel filter equals the risk level computed in SQL from the events' ($okLevels -and @($byLevel.Values | Sort-Object -Unique).Count -eq 4) ($levelDetail -join '; ')
$okRange = $true; $rangeDetail = @()
foreach ($range in @(@(50, $null), @($null, 50), @(40, 40), @(0, 100))) {
    $q = '?bestOnly=true'; if ($null -ne $range[0]) { $q += "&minPercent=$($range[0])" }; if ($null -ne $range[1]) { $q += "&maxPercent=$($range[1])" }
    $api = (Sessions $q) -join ','
    $db = (@($percent.Keys | Where-Object { ($null -eq $range[0] -or $percent[$_] -ge $range[0]) -and ($null -eq $range[1] -or $percent[$_] -le $range[1]) }) | Sort-Object) -join ','
    if ($api -ne $db) { $okRange = $false }
    $rangeDetail += "$($range[0])..$($range[1]): $(@($api -split ',' | Where-Object { $_ }).Count)"
}
Check 'results: minPercent / maxPercent equal the percentages in the database' $okRange ("percentages " + (($percent.Values | Sort-Object) -join ',') + "; " + ($rangeDetail -join '; '))
$risky = @((Api 'GET' "/api/exams/$e1/results?sort=risk" $adm.token $null).data.content | ForEach-Object { $_.riskLevel })
Check 'results: sort=risk is CRITICAL, HIGH, MEDIUM, LOW (desc by default)' (($risky -join ',') -eq 'CRITICAL,HIGH,MEDIUM,LOW') ($risky -join ',')
$riskyAsc = @((Api 'GET' "/api/exams/$e1/results?sort=risk&order=asc" $adm.token $null).data.content | ForEach-Object { $_.riskLevel })
Check 'results: sort=risk&order=asc reverses it' (($riskyAsc -join ',') -eq 'LOW,MEDIUM,HIGH,CRITICAL')
$legacy = Api 'GET' "/api/exams/$e1/results?page=0&size=25" $adm.token $null
$first = @($legacy.data.content)[0]
$oldRowKeys = 'sessionId', 'userId', 'participantName', 'email', 'attemptNumber', 'status', 'startedAt', 'submittedAt', 'durationSeconds', 'score', 'maxScore', 'percentage', 'passed', 'gradingStatus', 'violationCount', 'riskLevel'
Check 'results: the old call answers as before, rows keep every field and add submitKind MANUAL' ($legacy.data.totalElements -eq 4 -and -not ($oldRowKeys | Where-Object { $_ -notin $first.PSObject.Properties.Name }) -and @($legacy.data.content | Where-Object { $_.submitKind -ne 'MANUAL' }).Count -eq 0) (($first.PSObject.Properties.Name) -join ',')
Check 'results: wrong filters are 400 (min > max, 101, unknown level, unknown sort)' ((Api 'GET' "/api/exams/$e1/results?minPercent=60&maxPercent=40" $adm.token $null).status -eq 400 -and (Api 'GET' "/api/exams/$e1/results?maxPercent=101" $adm.token $null).status -eq 400 -and (Api 'GET' "/api/exams/$e1/results?riskLevel=EXTREME" $adm.token $null).status -eq 400 -and (Api 'GET' "/api/exams/$e1/results?sort=mood" $adm.token $null).status -eq 400)

# ---------- 4. CSV export ----------
$csv = Download "/api/exams/$e1/results/export" $adm.token
$text = [Text.Encoding]::UTF8.GetString($csv.bytes)
$lines = @($text.TrimStart([char]0xFEFF) -split "`r`n" | Where-Object { $_ -ne '' })
$bom = $csv.bytes.Length -ge 3 -and $csv.bytes[0] -eq 0xEF -and $csv.bytes[1] -eq 0xBB -and $csv.bytes[2] -eq 0xBF
$listed = Api 'GET' "/api/exams/$e1/results" $adm.token $null
Check 'CSV: UTF-8 with BOM, text/csv, attachment, Azerbaijani headers' ($csv.status -eq 200 -and $bom -and $csv.type -like 'text/csv*' -and $csv.disposition -like 'attachment*' -and $lines[0] -like 'Sessiya ID,Ad Soyad,E-poçt,Cəhd,*Risk səviyyəsi,İştirakçı kodu') "$($csv.type); $($csv.disposition); $($lines[0])"
Check 'CSV: rows = results total (same default filters)' (($lines.Count - 1) -eq $listed.data.totalElements -and [int]$csv.total -eq $listed.data.totalElements -and $listed.data.totalElements -eq 4) "csv $($lines.Count - 1), X-Total-Count $($csv.total), api $($listed.data.totalElements)"
$dLine = $lines | Where-Object { $_ -like "*$($pD.email)*" }
Check "CSV: a cell starting with = gets a leading apostrophe" ($dLine -like "*,'=SUM(1+1) Dd$stamp,*") $dLine
$parsed = $text.TrimStart([char]0xFEFF) | ConvertFrom-Csv
$parsedIds = (@($parsed | ForEach-Object { $_.'Sessiya ID' }) | Sort-Object) -join ','
$listedIds = (Sessions '') -join ','
Check 'CSV: reads back as a table with the same sessions (comma separated, quoted where needed)' ($parsedIds -eq $listedIds) "$(@($parsed).Count) rows, columns $(@($parsed[0].PSObject.Properties.Name).Count)"
$critical = Download "/api/exams/$e1/results/export?riskLevel=CRITICAL" $adm.token
Check 'CSV: the filters apply (riskLevel=CRITICAL -> 1 row)' ($critical.total -eq '1' -and (@([Text.Encoding]::UTF8.GetString($critical.bytes) -split "`r`n" | Where-Object { $_ -ne '' }).Count -eq 2))
$audit = Sql 'exam_db' "select actor_id||'|'||details from group_audit_log where action='RESULTS_EXPORTED' and details like 'examId=$e1 %' order by id"
Check 'CSV: every export leaves an audit row (who, exam, rows)' (@($audit).Count -eq 2 -and @($audit)[0] -like "$($adm.id)|examId=$e1 rows=4*") (@($audit) -join ' / ')
$own = Download "/api/exams/$e5/results/export" $insZ.token
$ownLines = @([Text.Encoding]::UTF8.GetString($own.bytes) -split "`r`n" | Where-Object { $_ -ne '' })
Check 'CSV: participant 403, instructor outside the audience 404, owner of another exam allowed' ((Download "/api/exams/$e1/results/export" $pA.token).status -eq 403 -and (Download "/api/exams/$e1/results/export" $insY.token).status -eq 404 -and $own.status -eq 200)
Check 'CSV: a session still in progress (empty submit time) is exported whole, the stream is not cut' ($own.status -eq 200 -and -not $own.broken -and ($ownLines.Count - 1) -eq [int]$own.total -and ($ownLines | Where-Object { $_ -like '*,IN_PROGRESS,*' })) "rows $($ownLines.Count - 1) / X-Total-Count $($own.total); broken=$($own.broken)"

# ---------- 5. grading overview ----------
$pendingSql = Sql 'exam_db' @"
select s.exam_id||'|'||count(*)||'|'||sum((select count(*) from user_answers x where x.session_id=s.id and x.needs_manual_review))
from exam_sessions s join exams e on e.id=s.exam_id and e.active where s.status='COMPLETED' and s.grading_status='PENDING' group by s.exam_id order by min(s.submitted_at), s.exam_id
"@
$pendingApi = @(); $page = 0
do { $p = Api 'GET' "/api/grading/pending-exams?page=$page&size=50" $adm.token $null; $pendingApi += @($p.data.content); $page++ } while ($p.data.hasNext)
$apiText = ($pendingApi | ForEach-Object { "$($_.examId)|$($_.pendingSessionCount)|$($_.pendingAnswerCount)" }) -join ';'
Check 'pending exams (admin): every exam with the same counts and order as SQL' ($apiText -eq (@($pendingSql) -join ';')) "$($pendingApi.Count) exams"
$e1Row = $pendingApi | Where-Object { $_.examId -eq $e1 }
Check 'pending exams: E1 has 2 sessions and 2 answers waiting (A and B), oldestPendingAt in UTC' ($e1Row.pendingSessionCount -eq 2 -and $e1Row.pendingAnswerCount -eq 2 -and $e1Row.oldestPendingAt -match 'Z$') ($e1Row | ConvertTo-Json -Compress)
$countSql = Sql 'exam_db' "select count(*)||'|'||coalesce(sum((select count(*) from user_answers x where x.session_id=s.id and x.needs_manual_review)),0) from exam_sessions s join exams e on e.id=s.exam_id and e.active where s.status='COMPLETED' and s.grading_status='PENDING'"
$count = Api 'GET' '/api/grading/pending-count' $adm.token $null
Check 'pending count (admin) equals SQL' ("$($count.data.sessions)|$($count.data.answers)" -eq $countSql) "$($count.data.sessions)|$($count.data.answers) / $countSql"
Write-Output "waiting for E5's minute to run out and the scheduler (up to 3 minutes)..."
$adm.token = (Login $adminEmail $adminPassword).data.accessToken
for ($i = 0; $i -lt 36; $i++) { if ((Sql 'exam_db' "select status from exam_sessions where session_id='$($s5.data.sessionId)'") -eq 'COMPLETED') { break }; Start-Sleep -Seconds 5 }
$expired = (Api 'GET' "/api/exams/$e5/results?bestOnly=false" $insZ.token $null).data.content | Select-Object -First 1
Check 'results: the session that ran out is AUTO_EXPIRED (submittedAt >= its end time)' ($expired.submitKind -eq 'AUTO_EXPIRED') "$($expired.status) $($expired.submittedAt) $($expired.submitKind)"
Start-Sleep -Seconds 11
$zExams = @((Api 'GET' '/api/grading/pending-exams' $insZ.token $null).data.content)
$zCount = (Api 'GET' '/api/grading/pending-count' $insZ.token $null).data
Check 'pending (instructor z): only the own exam E5, 1 session / 1 answer' ($zExams.Count -eq 1 -and $zExams[0].examId -eq $e5 -and $zCount.sessions -eq 1 -and $zCount.answers -eq 1) "$($zExams.Count) exams; $($zCount.sessions)|$($zCount.answers)"
$yCount = (Api 'GET' '/api/grading/pending-count' $insY.token $null).data
Check 'pending (instructor y, no exams): nothing, never the figures of others' ($yCount.sessions -eq 0 -and @((Api 'GET' '/api/grading/pending-exams' $insY.token $null).data.content).Count -eq 0)
Check 'pending: participant 403' ((Api 'GET' '/api/grading/pending-count' $pA.token $null).status -eq 403 -and (Api 'GET' '/api/grading/pending-exams' $pA.token $null).status -eq 403)
$before = (Api 'GET' '/api/grading/pending-count' $adm.token $null).data
$grading = Api 'GET' "/api/exams/session/$($sA.data.sessionId)/grading" $adm.token $null
$open = @($grading.data.answers)[0]
$graded = Api 'PUT' "/api/exams/session/$($sA.data.sessionId)/grades" $adm.token (ConvertTo-Json -Depth 4 -InputObject @(@{ answerId = $open.answerId; points = 1; expectedVersion = $open.gradeVersion }))
$soon = (Api 'GET' '/api/grading/pending-count' $adm.token $null).data
Start-Sleep -Seconds 11
$after = (Api 'GET' '/api/grading/pending-count' $adm.token $null).data
Check 'pending count: cached at most 10 s; after a grade it drops by one' ($graded.status -eq 200 -and $after.sessions -eq ($before.sessions - 1) -and $after.answers -eq ($before.answers - 1)) "before $($before.sessions)|$($before.answers), right after $($soon.sessions)|$($soon.answers), 11 s later $($after.sessions)|$($after.answers)"

# ---------- 6. audit list ----------
$adm.token = (Login $adminEmail $adminPassword).data.accessToken
$unlock = Api 'POST' "/auth/admin/users/$($pD.id)/unlock" $adm.token $null
$auditAll = Api 'GET' '/api/admin/audit?size=50' $adm.token $null
$groupLog = [int](Sql 'exam_db' 'select count(*) from group_audit_log')
$adminLog = [int](Sql 'exam_auth' 'select count(*) from admin_audit_log')
Check 'audit: total = rows of both audit tables; newest first; size capped at 50' ($auditAll.data.totalElements -eq ($groupLog + $adminLog) -and @($auditAll.data.content).Count -eq 50 -and (Api 'GET' '/api/admin/audit?size=500' $adm.token $null).data.size -eq 50) "api $($auditAll.data.totalElements) = $groupLog + $adminLog; unlock answered $($unlock.status)"
$times = @(@($auditAll.data.content) | ForEach-Object { $_.createdAt })
Check 'audit: sorted by createdAt desc across both sources' ((($times | Sort-Object -Descending) -join ',') -eq ($times -join ','))
$auth = Api 'GET' '/api/admin/audit?source=AUTH_SERVICE&size=50' $adm.token $null
Check 'audit: auth-service rows (accounts) are in the list with USER targets' ($auth.data.totalElements -eq $adminLog -and @($auth.data.content | Where-Object { $_.source -ne 'AUTH_SERVICE' }).Count -eq 0 -and (@($auth.data.content)[0].targetType -eq 'USER')) "$($auth.data.totalElements) rows; newest $(@($auth.data.content)[0].action) on $(@($auth.data.content)[0].targetUserName)"
$mine = Api 'GET' "/api/admin/audit?q=$T&size=50" $adm.token $null
$created = @($mine.data.content | Where-Object { $_.action -eq 'GROUP_CREATED' })
Check 'audit: q finds this run''s groups, with GROUP target, actor name and details' ($created.Count -eq 3 -and $created[0].targetType -eq 'GROUP' -and $created[0].actorName -and $created[0].details -like "name=$T-g*") "$($mine.data.totalElements) rows"
$byAction = Api 'GET' '/api/admin/audit?action=RESULTS_EXPORTED&size=50' $adm.token $null
Check 'audit: action filter; an export row targets the EXAM' (@($byAction.data.content | Where-Object { $_.action -ne 'RESULTS_EXPORTED' }).Count -eq 0 -and (@($byAction.data.content)[0].targetType -eq 'EXAM')) "$($byAction.data.totalElements) rows, target $(@($byAction.data.content)[0].targetId)"
$byActor = Api 'GET' "/api/admin/audit?actorId=$($pC.id)&size=50" $adm.token $null
Check 'audit: actor filter (C asked to join through the link)' ($byActor.data.totalElements -ge 1 -and @($byActor.data.content | Where-Object { $_.actorId -ne $pC.id }).Count -eq 0) "$($byActor.data.totalElements) rows: $(@($byActor.data.content | ForEach-Object { $_.action }) -join ',')"
$from = [DateTime]::UtcNow.AddMinutes(-30).ToString('yyyy-MM-ddTHH:mm:ssZ'); $to = [DateTime]::UtcNow.AddMinutes(1).ToString('yyyy-MM-ddTHH:mm:ssZ')
$window = Api 'GET' "/api/admin/audit?from=$from&to=$to&size=50" $adm.token $null
$windowSql = [int](Sql 'exam_db' "select count(*) from group_audit_log where created_at >= '$($from.TrimEnd('Z'))' and created_at < '$($to.TrimEnd('Z'))'") + [int](Sql 'exam_auth' "select count(*) from admin_audit_log where created_at >= '$($from.TrimEnd('Z'))' and created_at < '$($to.TrimEnd('Z'))'")
Check 'audit: from / to (UTC) equal SQL' ($window.data.totalElements -eq $windowSql) "$($window.data.totalElements) / $windowSql"
# Nothing secret in any row: every page of the whole list
$leak = @(); $page = 0; $seen = 0
do {
    $p = Api 'GET' "/api/admin/audit?page=$page&size=50" $adm.token $null
    foreach ($row in @($p.data.content)) {
        $seen++
        $textRow = ($row | ConvertTo-Json -Compress)
        if ($joinCode -and $textRow.Contains($joinCode)) { $leak += "join code in $($row.id)" }
        foreach ($otp in $script:otps) { if ($textRow -match "(?<!\d)$otp(?!\d)") { $leak += "otp in $($row.id)" } }
        if ($textRow -match '(?i)(password|otp|token)=') { $leak += "secret key in $($row.id)" }
    }
    $page++
} while ($p.data.hasNext -and $page -lt 200)
Check 'audit: no join code, no OTP of this run and no secret key=value anywhere in the list' ($leak.Count -eq 0 -and $seen -eq $auditAll.data.totalElements) "$seen rows read; $($script:otps.Count) OTPs and 1 join code looked for; $($leak -join ', ')"
Check 'audit: participant and instructor 403; deep page and bad input 400' ((Api 'GET' '/api/admin/audit' $pA.token $null).status -eq 403 -and (Api 'GET' '/api/admin/audit' $insZ.token $null).status -eq 403 -and (Api 'GET' '/api/admin/audit?page=1000&size=50' $adm.token $null).status -eq 400 -and (Api 'GET' '/api/admin/audit?actorId=nope' $adm.token $null).status -eq 400 -and (Api 'GET' '/api/admin/audit?from=yesterday' $adm.token $null).status -eq 400)

# ---------- 7. start before the start time ----------
$e6Row = Api 'POST' "/api/exams/$e6/publish" $adm.token $null
$early = Begin $e6 $pA.token
$avail = Api 'GET' '/api/exams/available?size=50' $pA.token $null
$e6Avail = @($avail.data.content) | Where-Object { $_.id -eq $e6 }
Check 'start time: published E6 (start in 2 h) cannot be started before its start (400 EXAM_NOT_ACTIVE)' ($early.status -eq 400 -and $early.code -eq 'EXAM_NOT_ACTIVE') "publish -> $($e6Row.data.status); start -> $($early.status) $($early.code)"
Check 'start time: E6 is listed in /available with its startTime and canStart=false (coming soon)' ($e6Avail -and $e6Avail.canStart -eq $false -and $e6Avail.startTime -match 'Z$') "$($e6Avail.status) $($e6Avail.startTime) canStart=$($e6Avail.canStart) remainingToday=$($e6Avail.remainingAttemptsToday)"
Check 'start time: no session was opened by the early start' ((Sql 'exam_db' "select count(*) from exam_sessions where exam_id=$e6") -eq '0')
Check 'start time vs group: outside the audience C gets 404 (group check comes first, as for every exam)' ((Begin $e6 $pC.token).code -eq 'EXAM_NOT_FOUND')
$again = Begin $e1 $pA.token
Check 'start time vs daily limit: inside the window the attempt rules apply as before (E1 again allowed: 2nd of 3)' ($again.status -eq 200) "E1 2nd start -> $($again.status)"
$null = Finish $again.data.sessionId $pA.token

# ---------- 8. permissions ----------
Check 'permissions: participant 403 on group list, group exams, member search' ((Api 'GET' '/api/admin/groups' $pA.token $null).status -eq 403 -and (Api 'GET' "/api/admin/groups/$g1/exams" $pA.token $null).status -eq 403 -and (Api 'GET' "/api/admin/groups/$g1/members?q=Nb7" $pA.token $null).status -eq 403)
# B8: an INSTRUCTOR manages the own groups; a group of someone else answers like a missing one (404)
$zGroups = Api 'GET' '/api/admin/groups?size=50' $insZ.token $null
$zIds = @(@($zGroups.data.content) | ForEach-Object { $_.id })
$yGroups = Api 'GET' '/api/admin/groups?size=50' $insY.token $null
Check 'permissions: instructor z lists only the own group on /api/admin/groups, none of the admin' ($zGroups.status -eq 200 -and $zGroups.data.totalElements -eq 1 -and ($zIds -join ',') -eq "$gz" -and $yGroups.status -eq 200 -and $yGroups.data.totalElements -eq 0) "z: status $($zGroups.status), ids $($zIds -join ','); y: status $($yGroups.status), total $($yGroups.data.totalElements)"
Check 'permissions: a group of someone else is 404 for an instructor (admin g1 for z, gz for y), the own one 200' ((Api 'GET' "/api/admin/groups/$g1" $insZ.token $null).status -eq 404 -and (Api 'GET' "/api/admin/groups/$g1/members" $insZ.token $null).status -eq 404 -and (Api 'GET' "/api/admin/groups/$gz" $insY.token $null).status -eq 404 -and (Api 'GET' "/api/admin/groups/$gz" $insZ.token $null).status -eq 200)
Check 'permissions: instructor results of an exam outside the audience 404, of a PUBLIC exam of someone else 403 EXAM_NOT_OWNER' ((Api 'GET' "/api/exams/$e1/results" $insY.token $null).status -eq 404 -and (Api 'GET' "/api/exams/$e2/results" $insY.token $null).code -eq 'EXAM_NOT_OWNER')

# ---------- 9. export limit (422) ----------
if (-not $SkipExportLimit) {
    $adm.token = (Login $adminEmail $adminPassword).data.accessToken
    Write-Output 'restarting exam-service with EXAM_RESULTS_EXPORT_MAX_ROWS=3 for the 422 check...'
    $env:EXAM_RESULTS_EXPORT_MAX_ROWS = '3'
    RestartExamService
    Remove-Item Env:EXAM_RESULTS_EXPORT_MAX_ROWS
    $limit = docker exec exam-exam-service printenv EXAM_RESULTS_EXPORT_MAX_ROWS
    $tooMany = Api 'GET' "/api/exams/$e1/results/export?bestOnly=false" $adm.token $null
    $fits = Download "/api/exams/$e1/results/export?riskLevel=CRITICAL" $adm.token
    Check 'CSV: more rows than the limit is 422 RESULTS_EXPORT_TOO_LARGE before anything is sent; within it 200' ($limit -eq '3' -and $tooMany.status -eq 422 -and $tooMany.code -eq 'RESULTS_EXPORT_TOO_LARGE' -and $fits.status -eq 200) "limit $limit; 5 rows -> $($tooMany.status) $($tooMany.code); 1 row -> $($fits.status)"
    Write-Output 'restarting exam-service with the default limit (50 000)...'
    RestartExamService
    Check 'CSV: back at the default limit of 50 000' ((docker exec exam-exam-service printenv EXAM_RESULTS_EXPORT_MAX_ROWS) -eq '50000')
}
} finally {
    # The test accounts get the participant role back, whatever happened above: no test account stays INSTRUCTOR
    $given = @($insZ.email, $insY.email) | Where-Object { $_ -like '*@x.test' }
    if ($given) {
        $back = Sql 'exam_auth' "update users set role='STUDENT' where role <> 'STUDENT' and email in ('$($given -join "','")')"
        Write-Output "roles given back: $back"
    }
}

$failed = @($script:results | Where-Object { -not $_.ok })
Write-Output ''
Write-Output "$($script:results.Count - $failed.Count) / $($script:results.Count) passed"
Write-Output "requests repeated after a 429 of the gateway: $($script:throttled)"
Write-Output "data: groups $g1, $g2, $g3, $gz (z); exams e1 $e1, e2 $e2, e3 $e3, e5 $e5, e6 $e6 (e4 $e4 deleted); stamp $stamp"
