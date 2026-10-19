# Live verification of fix/b8-hardening (B8): group ownership for INSTRUCTOR, the limits of an INSTRUCTOR
# outside the groups, GET /api/join/requests/mine and the rejection cooldown. Against the development
# stack (Mailpit for the codes). Creates T-b8-<stamp>-* groups and exams and be-b8-*-<stamp>@x.test
# accounts; one of them is INSTRUCTOR for the run and STUDENT again in the finally block.
# Admin credentials are read from F:\exam-system\.env into variables only; nothing secret is printed.
$ErrorActionPreference = 'Continue'
[Threading.Thread]::CurrentThread.CurrentCulture = [Globalization.CultureInfo]::InvariantCulture
Set-Location F:\exam-system
$GW = 'http://127.0.0.1:8080'
. "$PSScriptRoot\_common.ps1"
Wait-Gateway $GW
$MAIL = 'http://127.0.0.1:8025'
$stamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString('x')
$T = "T-b8-$stamp"
# A group id that does not exist (PowerShell names ignore case: no other variable may be called $missing)
$MISSING = 99999999
$script:results = @()

function Check($name, $ok, $detail = '') {
    $script:results += [pscustomobject]@{ name = $name; ok = [bool]$ok }
    $line = $(if ($ok) { 'PASS' } else { 'FAIL' }) + "  $name"
    if ($detail -ne '') { $line += "  ($detail)" }
    Write-Output $line
}
# A finding that is reported, not judged (known behaviour)
function Note($name, $detail) { Write-Output "NOTE  $name  ($detail)" }
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
        if (-not $resp) { return @{ status = 0; raw = $_.Exception.Message; json = $null; data = $null; code = $null; message = $null } }
        $status = [int]$resp.StatusCode
        $stream = $resp.GetResponseStream()
        if ($stream.CanSeek) { $stream.Position = 0 }
        $raw = (New-Object IO.StreamReader($stream)).ReadToEnd()
    }
    $json = $null
    if ($raw) { try { $json = $raw | ConvertFrom-Json } catch { $json = $null } }
    $data = $json; $code = $null; $message = $null
    if ($json -and ($json.PSObject.Properties.Name -contains 'data')) { $data = $json.data }
    if ($json -and $json.error) { $code = $json.error.code; $message = $json.message }
    return @{ status = $status; raw = $raw; json = $json; data = $data; code = $code; message = $message }
}
function Sql($db, $query) { docker exec exam-postgres psql -U postgres -d $db -Atc $query }
# "404 GROUP_NOT_FOUND"-style text of an answer, without ids (the message names the id that was asked for)
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
# One rate-limit bucket per route and client address: a drained budget needs a wait
function AuthCall($path, $body) {
    for ($try = 0; $try -lt 10; $try++) {
        $r = Api 'POST' $path $null $body
        if ($r.status -ne 429) { return $r }
        Start-Sleep -Seconds 6
    }
    return $r
}
function Register($name) {
    $email = "be-b8-$name-$stamp@x.test"
    $r = AuthCall '/auth/register' @{ email = $email; password = $password; firstName = "Nb8${stamp}$name"; lastName = "L$name" }
    if ($r.status -ne 201) { throw "register $name answered $($r.status)" }
    $v = AuthCall '/auth/verify-otp' @{ email = $email; code = (MailedCode $email) }
    if ($v.status -ne 200) { throw "verify $name answered $($v.status)" }
    return @{ email = $email; name = $name; id = $v.data.user.id; token = $v.data.accessToken }
}
function Login($email, $pw) { (AuthCall '/auth/login' @{ email = $email; password = $pw }) }
function ExamBody($title, $groupIds) {
    $body = @{ title = $title; description = 'B8 verification'; durationMinutes = 30
               startTime = [DateTime]::UtcNow.AddDays(1).ToString('yyyy-MM-ddTHH:mm:ss'); endTime = [DateTime]::UtcNow.AddDays(2).ToString('yyyy-MM-ddTHH:mm:ss')
               shuffleQuestions = $false; shuffleOptions = $false; showResultsImmediately = $true; maxAttempts = 1; passingScore = 50
               questions = @(@{ questionText = "$T q1: 1 + 1 = ?"; questionType = 'MULTIPLE_CHOICE'; points = 1; orderIndex = 0; allowMultipleAnswers = $false
                                options = @(@{ optionText = '2'; isCorrect = $true; orderIndex = 0 }, @{ optionText = '3'; isCorrect = $false; orderIndex = 1 }) }) }
    if ($null -ne $groupIds) { $body['accessType'] = 'GROUP'; $body['groupIds'] = @($groupIds) }
    return $body
}
function Import($groupId, $token, $csvText) {
    $csvFile = Join-Path $env:TEMP "t-b8-$stamp.csv"; $out = Join-Path $env:TEMP "t-b8-$stamp-import.json"
    Set-Content -Path $csvFile -Value $csvText -Encoding ASCII
    $status = & curl.exe -s -o $out -w '%{http_code}' -X POST "$GW/api/admin/groups/$groupId/members/import" -H "Authorization: Bearer $token" -F "file=@$csvFile"
    $json = $null; try { $json = Get-Content $out -Raw | ConvertFrom-Json } catch { }
    Remove-Item $csvFile, $out -ErrorAction SilentlyContinue
    return @{ status = [int]$status; code = $json.error.code }
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
$p1 = Register 'p1'; $p2 = Register 'p2'; $p3 = Register 'p3'; $p4 = Register 'p4'
# Every role given below is taken back in the finally block before the summary
try {
# Roles cannot be assigned through the API: set on this test account only, then sign in again for a token with the role
Sql 'exam_auth' "update users set role='INSTRUCTOR' where email='$($ins.email)'" | Out-Null
$ins.token = (Login $ins.email $password).data.accessToken
Write-Output "run $stamp; instructor be-b8-ins-$stamp@x.test, participants p1..p4"

# The admin's group: one member (p3), an approval link, one open request (p2)
$ga = Api 'POST' '/api/admin/groups' $adm.token @{ name = "$T-admin"; description = 'B8 admin group' }
$gaId = $ga.data.id
$null = Api 'POST' "/api/admin/groups/$gaId/members" $adm.token @{ userId = $p3.id }
$gaLink = Api 'POST' "/api/groups/$gaId/join-link" $adm.token @{ requiresApproval = $true }
$p2Ask = Api 'POST' "/api/join/$($gaLink.data.code)" $p2.token $null
$gaRequest = Sql 'exam_db' "select id from group_join_requests where group_id=$gaId and user_id='$($p2.id)' and status='PENDING'"
Check 'setup: admin group with 1 member, an approval link and 1 open request' ($ga.status -eq 201 -and (Sql 'exam_db' "select count(*) from user_group_members where group_id=$gaId") -eq '1' -and $gaLink.status -eq 200 -and $p2Ask.data.status -eq 'PENDING' -and $gaRequest) "group $gaId, link $($gaLink.status), request $gaRequest"
$gaBefore = Sql 'exam_db' "select g.name||'|'||coalesce(g.description,'')||'|'||g.active||'|'||(select count(*) from user_group_members where group_id=g.id)||'|'||(select code||':'||enabled||':'||requires_approval from group_join_links where group_id=g.id)||'|'||(select string_agg(id||':'||status, ',' order by id) from group_join_requests where group_id=g.id) from user_groups g where g.id=$gaId"

# ---------- 1. the admin's group for an INSTRUCTOR: every call 404, the same answer as a group that does not exist ----------
$calls = [ordered]@{
    'GET group'                 = @('GET', "/api/admin/groups/{id}", $null)
    'PUT group'                 = @('PUT', "/api/admin/groups/{id}", @{ name = "$T-renamed"; description = 'x' })
    'DELETE group'              = @('DELETE', "/api/admin/groups/{id}", $null)
    'GET members'               = @('GET', "/api/admin/groups/{id}/members", $null)
    'POST member'               = @('POST', "/api/admin/groups/{id}/members", @{ userId = $p1.id })
    'DELETE member'             = @('DELETE', "/api/admin/groups/{id}/members/$($p3.id)", $null)
    'GET group exams'           = @('GET', "/api/admin/groups/{id}/exams", $null)
    'GET invites'               = @('GET', "/api/admin/groups/{id}/invites", $null)
    'GET join link'             = @('GET', "/api/groups/{id}/join-link", $null)
    'POST join link'            = @('POST', "/api/groups/{id}/join-link", @{})
    'PATCH join link'           = @('PATCH', "/api/groups/{id}/join-link", @{ enabled = $false })
    'GET join requests'         = @('GET', "/api/groups/{id}/join-requests", $null)
    'approve requests (bulk)'   = @('POST', "/api/groups/{id}/join-requests/approve", @{ requestIds = @([long]$gaRequest) })
    'reject requests (bulk)'    = @('POST', "/api/groups/{id}/join-requests/reject", @{ requestIds = @([long]$gaRequest) })
    'approve one request'       = @('POST', "/api/groups/{id}/join-requests/$gaRequest/approve", $null)
    'reject one request'        = @('POST', "/api/groups/{id}/join-requests/$gaRequest/reject", $null)
    'bulk-by-ids'               = @('POST', "/api/groups/{id}/members/bulk-by-ids", @{ userIds = @($p1.id) })
}
foreach ($name in $calls.Keys) {
    $c = $calls[$name]
    $foreign = Api $c[0] ($c[1].Replace('{id}', "$gaId")) $ins.token $c[2]
    $absent = Api $c[0] ($c[1].Replace('{id}', "$MISSING")) $ins.token $c[2]
    Check "instructor on the admin's group, ${name}: 404 like a missing group" ($foreign.status -eq 404 -and (Answer $foreign) -eq (Answer $absent)) "admin group $(Answer $foreign); missing group $(Answer $absent)"
}
$imp = Import $gaId $ins.token "email`n$($p1.email)"
$impMissing = Import $MISSING $ins.token "email`n$($p1.email)"
Check "instructor on the admin's group, CSV import: 404 like a missing group" ($imp.status -eq 404 -and "$($imp.status) $($imp.code)" -eq "$($impMissing.status) $($impMissing.code)") "admin group $($imp.status) $($imp.code); missing group $($impMissing.status) $($impMissing.code)"
$gaAfter = Sql 'exam_db' "select g.name||'|'||coalesce(g.description,'')||'|'||g.active||'|'||(select count(*) from user_group_members where group_id=g.id)||'|'||(select code||':'||enabled||':'||requires_approval from group_join_links where group_id=g.id)||'|'||(select string_agg(id||':'||status, ',' order by id) from group_join_requests where group_id=g.id) from user_groups g where g.id=$gaId"
Check "the admin's group is unchanged after all of that, and no audit row names the instructor on it" ($gaAfter -eq $gaBefore -and (Sql 'exam_db' "select count(*) from group_audit_log where group_id=$gaId and actor_id='$($ins.id)'") -eq '0' -and (Sql 'exam_db' "select count(*) from pending_group_invites where group_id=$gaId") -eq '0')
$insList = Api 'GET' "/api/admin/groups?name=$T&size=50" $ins.token $null
$insPicker = Api 'GET' "/api/groups?name=$T&size=50" $ins.token $null
$admPicker = Api 'GET' "/api/groups?name=$T&size=50" $adm.token $null
Check "the instructor's group list and audience picker do not show the admin's group (the admin's picker does)" ($insList.status -eq 200 -and -not (@($insList.data.content) | Where-Object { $_.id -eq $gaId }) -and $insPicker.status -eq 200 -and -not (@($insPicker.data.content) | Where-Object { $_.id -eq $gaId }) -and (@($admPicker.data.content) | Where-Object { $_.id -eq $gaId })) "instructor list $($insList.data.totalElements), picker $($insPicker.data.totalElements); admin picker $($admPicker.data.totalElements)"

# ---------- 2. the instructor's own group ----------
$own = Api 'POST' '/api/admin/groups' $ins.token @{ name = "$T-own"; description = 'B8 instructor group' }
$ownId = $own.data.id
Check 'instructor creates a group: 201, owner and creator are the instructor' ($own.status -eq 201 -and (Sql 'exam_db' "select (owner_id = created_by and owner_id = '$($ins.id)') from user_groups where id=$ownId") -eq 't') "status $($own.status) $($own.code), group $ownId"
$addP1 = Api 'POST' "/api/admin/groups/$ownId/members" $ins.token @{ userId = $p1.id }
$addP2 = Api 'POST' "/api/admin/groups/$ownId/members" $ins.token @{ email = $p2.email }
$members = Api 'GET' "/api/admin/groups/$ownId/members" $ins.token $null
Check 'instructor adds members to the own group (by id and by email) and lists them' ($addP1.status -in 200, 201 -and $addP2.status -in 200, 201 -and $members.status -eq 200 -and $members.data.totalElements -eq 2) "add $($addP1.status)/$($addP2.status), members $($members.data.totalElements)"
$renamed = Api 'PUT' "/api/admin/groups/$ownId" $ins.token @{ name = "$T-own"; description = 'B8 instructor group, edited' }
Check 'instructor edits the own group: 200' ($renamed.status -eq 200 -and $renamed.data.description -eq 'B8 instructor group, edited') "status $($renamed.status) $($renamed.code)"
$ownList = Api 'GET' "/api/admin/groups?name=$T&size=50" $ins.token $null
Check "the instructor's group list holds the own group" (@($ownList.data.content) | Where-Object { $_.id -eq $ownId }) "total $($ownList.data.totalElements)"

# Exam audience: the own group is accepted; the admin's group gets the answer of a group that does not exist
$ownExam = Api 'POST' '/api/exams' $ins.token (ExamBody "$T-own-exam" @($ownId))
Check 'instructor creates a GROUP exam with the own group: 201' ($ownExam.status -eq 201 -and (Sql 'exam_db' "select string_agg(group_id::text, ',') from exam_audience where exam_id=$($ownExam.data.id)") -eq "$ownId") "status $($ownExam.status) $($ownExam.code), exam $($ownExam.data.id)"
$withAdmin = Api 'POST' '/api/exams' $ins.token (ExamBody "$T-foreign-exam" @($gaId))
$withMissing = Api 'POST' '/api/exams' $ins.token (ExamBody "$T-missing-exam" @($MISSING))
$withBoth = Api 'POST' '/api/exams' $ins.token (ExamBody "$T-both-exam" @($ownId, $gaId))
Check "instructor creates an exam for the admin's group: refused exactly like a group that does not exist" ($withAdmin.status -eq 400 -and "$(Answer $withAdmin) $($withAdmin.message)" -eq "$(Answer $withMissing) $($withMissing.message)" -and (Answer $withBoth) -eq (Answer $withAdmin) -and (Sql 'exam_db' "select count(*) from exams where title like '$T-%-exam' and title <> '$T-own-exam'") -eq '0') "admin group: $(Answer $withAdmin) '$($withAdmin.message)'; missing group: $(Answer $withMissing) '$($withMissing.message)'; own + admin: $(Answer $withBoth)"
$putAdmin = Api 'PUT' "/api/exams/$($ownExam.data.id)/audience" $ins.token @{ accessType = 'GROUP'; groupIds = @($ownId, $gaId) }
$putMissing = Api 'PUT' "/api/exams/$($ownExam.data.id)/audience" $ins.token @{ accessType = 'GROUP'; groupIds = @($ownId, $MISSING) }
Check "PUT audience with the admin's group: the same refusal as a missing group, the audience stays the own group" ($putAdmin.status -eq 400 -and (Answer $putAdmin) -eq (Answer $putMissing) -and (Sql 'exam_db' "select string_agg(group_id::text, ',') from exam_audience where exam_id=$($ownExam.data.id)") -eq "$ownId") "admin group $(Answer $putAdmin); missing group $(Answer $putMissing)"
$preview = Api 'POST' '/api/exams/audience/preview' $ins.token @{ groupIds = @($ownId, $gaId, $MISSING) }
$unknown = @($preview.data.unknownGroupIds | ForEach-Object { [long]$_ } | Sort-Object)
Check "audience preview: the admin's group is listed as unknown like a missing one, its members are not counted" ($preview.status -eq 200 -and ($unknown -join ',') -eq "$gaId,$MISSING" -and $preview.data.distinctEligible -eq 2 -and -not (@($preview.data.perGroup) | Where-Object { $_.groupId -eq $gaId })) "unknown $($unknown -join ','), distinct $($preview.data.distinctEligible), groups $((@($preview.data.perGroup) | ForEach-Object { $_.groupId }) -join ',')"

# ---------- 3. an INSTRUCTOR outside the groups: the admin paths are 403 ----------
$outside = [ordered]@{
    'GET /api/admin/audit'                    = @('GET', '/api/admin/audit', $null)
    'GET /api/admin/participants'             = @('GET', '/api/admin/participants', $null)
    'GET /api/admin/participants/summary'     = @('GET', '/api/admin/participants/summary', $null)
    'bulk-by-filter on the own group'         = @('POST', "/api/groups/$ownId/members/bulk-by-filter", @{ filter = @{ q = 'be-b8' }; dryRun = $true })
    "bulk-by-filter on the admin's group"     = @('POST', "/api/groups/$gaId/members/bulk-by-filter", @{ filter = @{ q = 'be-b8' }; dryRun = $true })
    'GET /auth/admin/users'                   = @('GET', '/auth/admin/users', $null)
    'POST /auth/admin/users/{id}/unlock'      = @('POST', "/auth/admin/users/$($p1.id)/unlock", $null)
    'POST /auth/admin/users/{id}/verify-email' = @('POST', "/auth/admin/users/$($p1.id)/verify-email", $null)
    'GET /api/analytics/overview'             = @('GET', '/api/analytics/overview', $null)
}
foreach ($name in $outside.Keys) {
    $c = $outside[$name]
    $r = Api $c[0] $c[1] $ins.token $c[2]
    Check "instructor outside the groups, ${name}: 403" ($r.status -eq 403) (Answer $r)
}
Check 'bulk-by-filter by the instructor added nobody' ((Sql 'exam_db' "select count(*) from user_group_members where group_id=$ownId") -eq '2' -and (Sql 'exam_db' "select count(*) from user_group_members where group_id=$gaId") -eq '1')

# ---------- 4. GET /api/join/requests/mine and the rejection cooldown ----------
$ownLink = Api 'POST' "/api/groups/$ownId/join-link" $ins.token @{ requiresApproval = $true }
$ownCode = $ownLink.data.code
$p3Ask = Api 'POST' "/api/join/$ownCode" $p3.token $null
$p4Ask = Api 'POST' "/api/join/$ownCode" $p4.token $null
Check 'instructor: own approval link; p3 and p4 ask to join (PENDING)' ($ownLink.status -eq 200 -and $p3Ask.data.status -eq 'PENDING' -and $p4Ask.data.status -eq 'PENDING') "link $($ownLink.status), p3 $($p3Ask.data.status), p4 $($p4Ask.data.status)"
$p3Mine = Api 'GET' '/api/join/requests/mine' $p3.token $null
$p3Rows = @($p3Mine.data.content)
$p3Db = Sql 'exam_db' "select string_agg(id::text, ',' order by id) from group_join_requests where user_id='$($p3.id)'"
$othersIds = @(Sql 'exam_db' "select id from group_join_requests where user_id <> '$($p3.id)' and group_id in ($ownId, $gaId)")
Check 'mine (p3): exactly the own request, PENDING, with the group name; nothing of p4 or p2' ($p3Mine.status -eq 200 -and (($p3Rows | ForEach-Object { $_.id } | Sort-Object) -join ',') -eq $p3Db -and $p3Rows.Count -eq 1 -and $p3Rows[0].groupName -eq "$T-own" -and $p3Rows[0].status -eq 'PENDING' -and -not ($p3Rows | Where-Object { "$($_.id)" -in $othersIds })) "status $($p3Mine.status); ids $(($p3Rows | ForEach-Object { $_.id }) -join ','), database $p3Db"
$p2Mine = Api 'GET' '/api/join/requests/mine' $p2.token $null
Check "mine (p2): only the request to the admin's group" ($p2Mine.status -eq 200 -and $p2Mine.data.totalElements -eq 1 -and @($p2Mine.data.content)[0].groupId -eq $gaId -and @($p2Mine.data.content)[0].status -eq 'PENDING') "total $($p2Mine.data.totalElements), group $(@($p2Mine.data.content)[0].groupId)"
Check 'mine without a token: 401; a participant without requests: empty page' ((Api 'GET' '/api/join/requests/mine' $null $null).status -eq 401 -and (Api 'GET' '/api/join/requests/mine' $p1.token $null).data.totalElements -eq 0)
$p3Req = $p3Rows[0].id; $p4Req = Sql 'exam_db' "select id from group_join_requests where group_id=$ownId and user_id='$($p4.id)'"
$reject = Api 'POST' "/api/groups/$ownId/join-requests/$p3Req/reject" $ins.token $null
$approve = Api 'POST' "/api/groups/$ownId/join-requests/$p4Req/approve" $ins.token $null
Check 'instructor rejects p3 and approves p4 on the own group' ($reject.status -eq 200 -and $approve.status -eq 200 -and (Sql 'exam_db' "select count(*) from user_group_members where group_id=$ownId and user_id='$($p4.id)'") -eq '1') "reject $($reject.status), approve $($approve.status)"
$usesBefore = Sql 'exam_db' "select uses from group_join_links where group_id=$ownId"
$again = Api 'POST' "/api/join/$ownCode" $p3.token $null
$againAt = if ($again.json.error.details.canRequestAgainAt) { [DateTime]::Parse($again.json.error.details.canRequestAgainAt).ToUniversalTime() } else { $null }
$hoursAhead = if ($againAt) { ($againAt - [DateTime]::UtcNow).TotalHours } else { 0 }
Check 'p3 asks again within 24 hours: 409 JOIN_REQUEST_COOLDOWN, canRequestAgainAt about 24 h ahead, no new request, no use counted' ($again.status -eq 409 -and $again.code -eq 'JOIN_REQUEST_COOLDOWN' -and $hoursAhead -gt 23.5 -and $hoursAhead -le 24.01 -and (Sql 'exam_db' "select count(*) from group_join_requests where group_id=$ownId and user_id='$($p3.id)'") -eq '1' -and (Sql 'exam_db' "select uses from group_join_links where group_id=$ownId") -eq $usesBefore) "$(Answer $again), canRequestAgainAt $($again.json.error.details.canRequestAgainAt) ($([Math]::Round($hoursAhead, 2)) h)"
$p3After = @((Api 'GET' '/api/join/requests/mine' $p3.token $null).data.content)[0]
$p4After = @((Api 'GET' '/api/join/requests/mine' $p4.token $null).data.content)[0]
Check 'mine after the decisions: p3 REJECTED with decidedAt and canRequestAgainAt, p4 APPROVED without it; who decided is not shown' ($p3After.status -eq 'REJECTED' -and $p3After.decidedAt -match 'Z$' -and $p3After.canRequestAgainAt -match 'Z$' -and $p4After.status -eq 'APPROVED' -and -not $p4After.canRequestAgainAt -and -not ($p3After.PSObject.Properties.Name -contains 'decidedBy')) "p3 $($p3After.status) $($p3After.canRequestAgainAt); p4 $($p4After.status); fields $($p3After.PSObject.Properties.Name -join ',')"

# ---------- 5. known: a group name of someone else is refused with 409 (names are unique over all groups) ----------
$sameName = Api 'POST' '/api/admin/groups' $ins.token @{ name = "$T-admin" }
Note "instructor creates a group with the name of the admin's group (known small leak: the name exists)" "$(Answer $sameName): $($sameName.message)"
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
Write-Output "stamp=$stamp; groups admin=$gaId own=$ownId; accounts be-b8-*-$stamp@x.test"
