# Live verification of feat/groups-bulk (B5), part 2: the join link, the join requests, the CSV
# pending invite after registration, and the two checks part 1 reported (both with their parts
# printed, so a failure says which one it is). Uses only the data it creates itself.
# Admin credentials are read from F:\exam-system\.env into variables only; nothing secret is printed.
$ErrorActionPreference = 'Continue'
[Threading.Thread]::CurrentThread.CurrentCulture = [Globalization.CultureInfo]::InvariantCulture
Set-Location F:\exam-system
Add-Type -AssemblyName System.Net.Http
$GW = 'http://127.0.0.1:8080'
. "$PSScriptRoot\_common.ps1"
Wait-Gateway $GW
$MAIL = 'http://127.0.0.1:8025'
$stamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString('x')
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
# One rate-limit bucket per client IP covers all /auth routes, so a drained budget needs a wait
function AuthCall($path, $body) {
    for ($try = 0; $try -lt 10; $try++) {
        $r = Api 'POST' $path $null $body
        if ($r.status -ne 429) { return $r }
        Start-Sleep -Seconds 6
    }
    return $r
}
function RegisterOnly($name) {
    $email = "be-b5j-$name-$stamp@x.test"
    $r = AuthCall '/auth/register' @{ email = $email; password = $password; firstName = "Jb5${stamp}$name"; lastName = "L$name$stamp" }
    if ($r.status -ne 201) { throw "register $name answered $($r.status)" }
    return @{ email = $email; name = $name }
}
function Verify($account) {
    $v = AuthCall '/auth/verify-otp' @{ email = $account.email; code = (MailedCode $account.email) }
    if ($v.status -ne 200) { throw "verify $($account.name) answered $($v.status)" }
    $account.id = $v.data.user.id
    $account.token = $v.data.accessToken
    return $account
}
function Register($name) { Verify (RegisterOnly $name) }
function Login($email, $pw) { (AuthCall '/auth/login' @{ email = $email; password = $pw }) }

function ParallelJoin($code, $tokens) {
    $clients = @(); $tasks = @()
    foreach ($token in $tokens) {
        $client = New-Object System.Net.Http.HttpClient
        $client.DefaultRequestHeaders.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $token)
        $clients += $client
        $tasks += $client.PostAsync("$GW/api/join/$code", (New-Object System.Net.Http.StringContent('', [Text.Encoding]::UTF8, 'application/json')))
    }
    try { [Threading.Tasks.Task]::WaitAll([Threading.Tasks.Task[]]$tasks) } catch { }
    $out = foreach ($task in $tasks) {
        if ($task.IsFaulted) { [pscustomobject]@{ status = 0; body = '' } }
        else { [pscustomobject]@{ status = [int]$task.Result.StatusCode; body = $task.Result.Content.ReadAsStringAsync().Result } }
    }
    $clients | ForEach-Object { $_.Dispose() }
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

# ---------- the join link ----------
$groupJoin = Api 'POST' '/api/admin/groups' $adm.token @{ name = "T-b5-$stamp-join"; description = 'B5 join link' }
$groupApprove = Api 'POST' '/api/admin/groups' $adm.token @{ name = "T-b5-$stamp-approve"; description = 'B5 join with approval' }
$groupLimit = Api 'POST' '/api/admin/groups' $adm.token @{ name = "T-b5-$stamp-limit"; description = 'B5 join with max uses' }
$groupCsv = Api 'POST' '/api/admin/groups' $adm.token @{ name = "T-b5-$stamp-csv"; description = 'B5 csv invite' }
$idJoin = $groupJoin.data.id; $idApprove = $groupApprove.data.id; $idLimit = $groupLimit.data.id; $idCsv = $groupCsv.data.id
Check 'four groups created for the join tests' ($groupJoin.status -eq 201 -and $groupApprove.status -eq 201 -and $groupLimit.status -eq 201 -and $groupCsv.status -eq 201) "$idJoin, $idApprove, $idLimit, $idCsv"

$noLink = Api 'GET' "/api/groups/$idJoin/join-link" $adm.token $null
Check 'a group without a link: 404 JOIN_LINK_NOT_FOUND' ($noLink.status -eq 404 -and $noLink.code -eq 'JOIN_LINK_NOT_FOUND') "status $($noLink.status) $($noLink.code)"
$link = Api 'POST' "/api/groups/$idJoin/join-link" $adm.token @{}
$code = $link.data.code
$linkKeys = 'groupId', 'code', 'enabled', 'requiresApproval', 'expiresAt', 'maxUses', 'uses', 'pendingRequests', 'usable', 'createdAt', 'updatedAt'
Check 'a link is created: 10 characters, no 0, O, 1 or I, enabled, 0 uses' ($link.status -eq 200 -and $code.Length -eq 10 -and $code -match '^[A-HJ-NP-Z2-9]{10}$' -and $link.data.enabled -eq $true -and $link.data.uses -eq 0 -and $link.data.usable -eq $true -and $link.data.requiresApproval -eq $false -and -not ($linkKeys | Where-Object { $_ -notin $link.data.PSObject.Properties.Name })) "status $($link.status) $($link.code), code length $($code.Length)"
Check 'the code is not written to the audit log' ((Sql 'exam_db' "select count(*) from group_audit_log where group_id=$idJoin and details like '%$code%'") -eq '0' -and (Sql 'exam_db' "select count(*) from group_audit_log where group_id=$idJoin and action='LINK_CREATED'") -eq '1')
$info = Api 'GET' "/api/join/$code" $null $null
Check 'GET /api/join/{code} without a token: the group name and nothing else' ($info.status -eq 200 -and $info.data.groupName -eq "T-b5-$stamp-join" -and $info.data.requiresApproval -eq $false -and (($info.data.PSObject.Properties.Name | Sort-Object) -join ',') -eq 'groupName,requiresApproval') "status $($info.status); fields $(($info.data.PSObject.Properties.Name) -join ',')"
Check 'an unknown code, a code of the wrong shape and lower case all answer the same' ((Api 'GET' '/api/join/ABCDEFGHJK' $null $null).code -eq 'JOIN_CODE_INVALID' -and (Api 'GET' '/api/join/short' $null $null).code -eq 'JOIN_CODE_INVALID' -and (Api 'GET' "/api/join/$($code.ToLower())" $null $null).status -eq 200)

# registration -> OTP -> login -> join (the frontend keeps the code and calls join after verifying)
$joiner = RegisterOnly 'a'
$beforeVerify = Login $joiner.email $password
$unverifiedToken = $null
$tokenFromRegister = $null
Check 'before the email is verified there is no token and login answers 403 EMAIL_NOT_VERIFIED' ($beforeVerify.status -eq 403 -and $beforeVerify.code -eq 'EMAIL_NOT_VERIFIED') "login $($beforeVerify.status) $($beforeVerify.code)"
$joiner = Verify $joiner
$joined = Api 'POST' "/api/join/$code" $joiner.token $null
$joinedAgain = Api 'POST' "/api/join/$code" $joiner.token $null
$linkAfter = Api 'GET' "/api/groups/$idJoin/join-link" $adm.token $null
Check 'registration -> OTP -> join: MEMBER, source LINK, one use counted' ($joined.status -eq 200 -and $joined.data.status -eq 'MEMBER' -and $joined.data.changed -eq $true -and $joined.data.groupName -eq "T-b5-$stamp-join" -and (Sql 'exam_db' "select source from user_group_members where group_id=$idJoin and user_id='$($joiner.id)'") -eq 'LINK' -and $linkAfter.data.uses -eq 1) "status $($joined.status) $($joined.code), uses $($linkAfter.data.uses)"
Check 'joining again: 200 MEMBER, changed false, still one member and one use' ($joinedAgain.status -eq 200 -and $joinedAgain.data.status -eq 'MEMBER' -and $joinedAgain.data.changed -eq $false -and (Sql 'exam_db' "select count(*) from user_group_members where group_id=$idJoin") -eq '1' -and (Api 'GET' "/api/groups/$idJoin/join-link" $adm.token $null).data.uses -eq 1)
Check 'one JOIN_VIA_LINK audit row names the participant' ((Sql 'exam_db' "select count(*) from group_audit_log where group_id=$idJoin and action='JOIN_VIA_LINK' and target_user_id='$($joiner.id)'") -eq '1')

# an account whose email is not verified cannot join
$unverified = RegisterOnly 'u'
Sql 'exam_auth' "update users set role='STUDENT' where email='$($unverified.email)'" | Out-Null
$unverifiedUser = Sql 'exam_auth' "select id from users where email='$($unverified.email)'"
# It has no token of its own (login is refused), so the admin's view is used to prove it is not a member
Check 'an unverified account has no token at all, so it cannot join' ((Login $unverified.email $password).status -eq 403 -and (Sql 'exam_db' "select count(*) from user_group_members where user_id='$unverifiedUser'") -eq '0')
# A token of a verified account whose address was taken back (verification removed) is refused by the endpoint
$probe = Register 'v'
Sql 'exam_auth' "update users set email_verified=false where email='$($probe.email)'" | Out-Null
$unverifiedJoin = Api 'POST' "/api/join/$code" $probe.token $null
Sql 'exam_auth' "update users set email_verified=true where email='$($probe.email)'" | Out-Null
Check 'a token whose account is not verified: 403 EMAIL_NOT_VERIFIED, no membership' ($unverifiedJoin.status -eq 403 -and $unverifiedJoin.code -eq 'EMAIL_NOT_VERIFIED' -and (Sql 'exam_db' "select count(*) from user_group_members where group_id=$idJoin and user_id='$($probe.id)'") -eq '0') "status $($unverifiedJoin.status) $($unverifiedJoin.code)"
$adminJoin = Api 'POST' "/api/join/$code" $adm.token $null
Check 'an ADMIN cannot join with a link: 403 JOIN_PARTICIPANTS_ONLY' ($adminJoin.status -eq 403 -and $adminJoin.code -eq 'JOIN_PARTICIPANTS_ONLY') "status $($adminJoin.status) $($adminJoin.code)"
Check 'joining without a token is 401' ((Api 'POST' "/api/join/$code" $null $null).status -eq 401)

# switched off, expired and used up all answer the same way
$off = Api 'PATCH' "/api/groups/$idJoin/join-link" $adm.token @{ enabled = $false }
$offInfo = Api 'GET' "/api/join/$code" $null $null
$offJoin = Api 'POST' "/api/join/$code" $probe.token $null
Check 'switched off: the same JOIN_CODE_INVALID for info and for joining, and usable false' ($off.data.enabled -eq $false -and $off.data.usable -eq $false -and $offInfo.status -eq 404 -and $offInfo.code -eq 'JOIN_CODE_INVALID' -and $offJoin.status -eq 404 -and $offJoin.code -eq 'JOIN_CODE_INVALID') "info $($offInfo.code), join $($offJoin.code)"
Check 'a member of the group gets the same answer while the link is off (nothing leaks)' ((Api 'POST' "/api/join/$code" $joiner.token $null).code -eq 'JOIN_CODE_INVALID')
Api 'PATCH' "/api/groups/$idJoin/join-link" $adm.token @{ enabled = $true } | Out-Null
$expired = Api 'PATCH' "/api/groups/$idJoin/join-link" $adm.token @{ expiresAt = [DateTime]::UtcNow.AddHours(1).ToString('yyyy-MM-ddTHH:mm:ss') }
Sql 'exam_db' "update group_join_links set expires_at = LOCALTIMESTAMP - interval '1 minute' where group_id = $idJoin" | Out-Null
Check 'expired: the same answer; an end time in the past is refused when set through the API' ((Api 'GET' "/api/join/$code" $null $null).code -eq 'JOIN_CODE_INVALID' -and (Api 'POST' "/api/join/$code" $probe.token $null).code -eq 'JOIN_CODE_INVALID' -and (Api 'PATCH' "/api/groups/$idJoin/join-link" $adm.token @{ expiresAt = [DateTime]::UtcNow.AddHours(-1).ToString('yyyy-MM-ddTHH:mm:ss') }).status -eq 400) "expiresAt set to $($expired.data.expiresAt)"
$regenerated = Api 'POST' "/api/groups/$idJoin/join-link" $adm.token @{ expiresAt = $null }
Check 'creating it again: a new code, the old one stops working, uses back to 0, LINK_REGENERATED audited' ($regenerated.data.code -ne $code -and $regenerated.data.uses -eq 0 -and $regenerated.data.usable -eq $true -and $null -eq $regenerated.data.expiresAt -and (Api 'GET' "/api/join/$code" $null $null).code -eq 'JOIN_CODE_INVALID' -and (Api 'GET' "/api/join/$($regenerated.data.code)" $null $null).status -eq 200 -and (Sql 'exam_db' "select count(*) from group_audit_log where group_id=$idJoin and action='LINK_REGENERATED'") -eq '1')
Check 'the member added through the old code is still a member' ((Sql 'exam_db' "select count(*) from user_group_members where group_id=$idJoin and user_id='$($joiner.id)'") -eq '1')
Check 'the link endpoints: STUDENT 403, no token 401' ((Api 'GET' "/api/groups/$idJoin/join-link" $joiner.token $null).status -eq 403 -and (Api 'POST' "/api/groups/$idJoin/join-link" $joiner.token @{}).status -eq 403 -and (Api 'PATCH' "/api/groups/$idJoin/join-link" $null @{ enabled = $false }).status -eq 401)

# ---------- max_uses: ten at once, exactly three get in ----------
$limitLink = Api 'POST' "/api/groups/$idLimit/join-link" $adm.token @{ maxUses = 3 }
$limitCode = $limitLink.data.code
$racers = @()
foreach ($n in 1..10) { $racers += (Register "r$n") }
$race = @(ParallelJoin $limitCode ($racers | ForEach-Object { $_.token }))
$members = [int](Sql 'exam_db' "select count(*) from user_group_members where group_id=$idLimit")
$uses = [int](Sql 'exam_db' "select uses from group_join_links where group_id=$idLimit")
$ok = @($race | Where-Object { $_.status -eq 200 }).Count
$refused = @($race | Where-Object { $_.status -eq 404 -and $_.body -match 'JOIN_CODE_INVALID' }).Count
Check 'maxUses=3 with ten parallel joins: exactly 3 members, 3 uses, 3 x 200 and 7 x JOIN_CODE_INVALID' ($members -eq 3 -and $uses -eq 3 -and $ok -eq 3 -and $refused -eq 7) "members $members, uses $uses, statuses $(($race | Group-Object status | ForEach-Object { "$($_.Name)x$($_.Count)" }) -join ' ')"
Check 'the link is no longer usable and the eleventh participant is refused as well' ((Api 'GET' "/api/groups/$idLimit/join-link" $adm.token $null).data.usable -eq $false -and (Api 'POST' "/api/join/$limitCode" $probe.token $null).code -eq 'JOIN_CODE_INVALID')
Check 'the three who got in are members with source LINK, and each has an audit row' ((Sql 'exam_db' "select count(*) from user_group_members where group_id=$idLimit and source='LINK'") -eq '3' -and (Sql 'exam_db' "select count(*) from group_audit_log where group_id=$idLimit and action='JOIN_VIA_LINK'") -eq '3')
$raisedLimit = Api 'PATCH' "/api/groups/$idLimit/join-link" $adm.token @{ maxUses = 5 }
$fourth = Api 'POST' "/api/join/$limitCode" $probe.token $null
Check 'raising maxUses lets the next participant in (4 members, 4 uses)' ($raisedLimit.data.maxUses -eq 5 -and $fourth.data.status -eq 'MEMBER' -and (Sql 'exam_db' "select count(*)||'/'||(select uses from group_join_links where group_id=$idLimit) from user_group_members where group_id=$idLimit") -eq '4/4')
$removedLimit = Api 'PATCH' "/api/groups/$idLimit/join-link" $adm.token @{ maxUses = $null }
Check 'maxUses can be removed (unlimited) and 0 is refused' ($null -eq $removedLimit.data.maxUses -and $removedLimit.data.usable -eq $true -and (Api 'PATCH' "/api/groups/$idLimit/join-link" $adm.token @{ maxUses = 0 }).status -eq 400)

# ---------- requires_approval ----------
$approveLink = Api 'POST' "/api/groups/$idApprove/join-link" $adm.token @{ requiresApproval = $true }
$approveCode = $approveLink.data.code
$asked = Api 'POST' "/api/join/$approveCode" $joiner.token $null
$askedAgain = Api 'POST' "/api/join/$approveCode" $joiner.token $null
$infoApprove = Api 'GET' "/api/join/$approveCode" $null $null
Check 'a link that needs approval: PENDING, no membership yet, the code says so' ($asked.status -eq 200 -and $asked.data.status -eq 'PENDING' -and $asked.data.changed -eq $true -and $askedAgain.data.status -eq 'PENDING' -and $askedAgain.data.changed -eq $false -and (Sql 'exam_db' "select count(*) from user_group_members where group_id=$idApprove") -eq '0' -and $infoApprove.data.requiresApproval -eq $true) "status $($asked.data.status), members $(Sql 'exam_db' "select count(*) from user_group_members where group_id=$idApprove")"
Check 'asking twice counts one use and leaves one open request' ((Sql 'exam_db' "select uses from group_join_links where group_id=$idApprove") -eq '1' -and (Sql 'exam_db' "select count(*) from group_join_requests where group_id=$idApprove and status='PENDING'") -eq '1')
$askers = @($racers[0], $racers[1], $racers[2], $racers[3])
foreach ($asker in $askers) { Api 'POST' "/api/join/$approveCode" $asker.token $null | Out-Null }
$requests = Api 'GET' "/api/groups/$idApprove/join-requests" $adm.token $null
$requestRow = @($requests.data.content)[0]
Check 'the open requests are listed oldest first, with name and email' ($requests.status -eq 200 -and $requests.data.totalElements -eq 5 -and $requestRow.userId -eq $joiner.id -and $requestRow.email -eq $joiner.email -and $requestRow.participantName -like "Jb5${stamp}a *" -and $requestRow.status -eq 'PENDING' -and $requestRow.createdAt -match 'Z$') "status $($requests.status), total $($requests.data.totalElements), first $($requestRow.email)"
Check 'the link reports how many requests are open' ((Api 'GET' "/api/groups/$idApprove/join-link" $adm.token $null).data.pendingRequests -eq 5)
$approvedOne = Api 'POST' "/api/groups/$idApprove/join-requests/$($requestRow.id)/approve" $adm.token $null
Check 'approving one: member with source LINK, request APPROVED, audit row' ($approvedOne.status -eq 200 -and $approvedOne.data.decided -eq 1 -and (Sql 'exam_db' "select source from user_group_members where group_id=$idApprove and user_id='$($joiner.id)'") -eq 'LINK' -and (Sql 'exam_db' "select status from group_join_requests where id=$($requestRow.id)") -eq 'APPROVED' -and (Sql 'exam_db' "select count(*) from group_audit_log where group_id=$idApprove and action='JOIN_APPROVED' and target_user_id='$($joiner.id)'") -eq '1')
Check 'approving it again: 404 JOIN_REQUEST_NOT_FOUND' ((Api 'POST' "/api/groups/$idApprove/join-requests/$($requestRow.id)/approve" $adm.token $null).status -eq 404)
$openIds = @((Api 'GET' "/api/groups/$idApprove/join-requests" $adm.token $null).data.content | ForEach-Object { $_.id })
$bulkApprove = Api 'POST' "/api/groups/$idApprove/join-requests/approve" $adm.token @{ requestIds = @($openIds[0], $openIds[1]) }
$bulkReject = Api 'POST' "/api/groups/$idApprove/join-requests/reject" $adm.token @{ requestIds = @($openIds[2], $openIds[3], 99999999) }
Check 'approving and rejecting in bulk: 2 approved, 2 rejected, the unknown id is skipped' ($bulkApprove.data.decided -eq 2 -and $bulkApprove.data.skipped -eq 0 -and $bulkReject.data.requested -eq 3 -and $bulkReject.data.decided -eq 2 -and $bulkReject.data.skipped -eq 1) "approve $($bulkApprove.data.decided)/$($bulkApprove.data.skipped), reject $($bulkReject.data.decided)/$($bulkReject.data.skipped)"
Check 'three members in all, two rejections, no open request left; audit rows for both decisions' ((Sql 'exam_db' "select count(*) from user_group_members where group_id=$idApprove") -eq '3' -and (Sql 'exam_db' "select count(*) from group_join_requests where group_id=$idApprove and status='REJECTED'") -eq '2' -and (Sql 'exam_db' "select count(*) from group_join_requests where group_id=$idApprove and status='PENDING'") -eq '0' -and (Sql 'exam_db' "select count(*) from group_audit_log where group_id=$idApprove and action='JOIN_REJECTED'") -eq '2')
$rejectedUser = Sql 'exam_db' "select user_id from group_join_requests where group_id=$idApprove and status='REJECTED' limit 1"
$rejectedAsker = $racers | Where-Object { $_.id -eq $rejectedUser }
# B8: after a rejection the same user may ask that group again only after the cooldown (default 24 hours)
$usesBefore = Sql 'exam_db' "select uses from group_join_links where group_id=$idApprove"
$askAgain = Api 'POST' "/api/join/$approveCode" $rejectedAsker.token $null
Check 'a rejected participant asking again at once: 409 JOIN_REQUEST_COOLDOWN with canRequestAgainAt, no new request, no use counted' ($askAgain.status -eq 409 -and $askAgain.code -eq 'JOIN_REQUEST_COOLDOWN' -and $askAgain.json.error.details.canRequestAgainAt -and (Sql 'exam_db' "select count(*) from group_join_requests where group_id=$idApprove and user_id='$rejectedUser'") -eq '1' -and (Sql 'exam_db' "select uses from group_join_links where group_id=$idApprove") -eq $usesBefore) "status $($askAgain.status) $($askAgain.code), canRequestAgainAt $($askAgain.json.error.details.canRequestAgainAt)"
Check 'the request lists can be filtered by status; an unknown status is 400' ((Api 'GET' "/api/groups/$idApprove/join-requests?status=APPROVED" $adm.token $null).data.totalElements -eq 3 -and (Api 'GET' "/api/groups/$idApprove/join-requests?status=REJECTED" $adm.token $null).data.totalElements -eq 2 -and (Api 'GET' "/api/groups/$idApprove/join-requests?status=NOPE" $adm.token $null).status -eq 400)
Check 'a member who asks again gets MEMBER, not a request' ((Api 'POST' "/api/join/$approveCode" $joiner.token $null).data.status -eq 'MEMBER')
Check '501 request ids is 400; an empty list is 400; the request endpoints are 403 for a STUDENT' ((Api 'POST' "/api/groups/$idApprove/join-requests/approve" $adm.token @{ requestIds = @(1..501) }).status -eq 400 -and (Api 'POST' "/api/groups/$idApprove/join-requests/approve" $adm.token @{ requestIds = @() }).status -eq 400 -and (Api 'GET' "/api/groups/$idApprove/join-requests" $joiner.token $null).status -eq 403)
$turnedOff = Api 'PATCH' "/api/groups/$idApprove/join-link" $adm.token @{ requiresApproval = $false }
$directNow = Api 'POST' "/api/join/$approveCode" $racers[4].token $null
Check 'switching approval off: the next participant becomes a member at once' ($turnedOff.data.requiresApproval -eq $false -and $directNow.data.status -eq 'MEMBER' -and (Sql 'exam_db' "select count(*) from user_group_members where group_id=$idApprove") -eq '4')

# ---------- CSV: a pending invite becomes a membership after registration ----------
$futureEmail = "be-b5j-csv-$stamp@x.test"
$csvFile = Join-Path $env:TEMP "t-b5-$stamp.csv"
Set-Content -Path $csvFile -Value "email`n$futureEmail`n$($joiner.email)" -Encoding ASCII
$curl = @('-s', '-o', "$env:TEMP\t-b5-import.json", '-w', '%{http_code}', '-X', 'POST', "$GW/api/admin/groups/$idCsv/members/import",
    '-H', "Authorization: Bearer $($adm.token)", '-F', "file=@$csvFile")
$importStatus = & curl.exe @curl
$import = Get-Content "$env:TEMP\t-b5-import.json" -Raw | ConvertFrom-Json
Check 'CSV import: the verified address becomes a member, the unknown one a pending invite' ($importStatus -eq '200' -and (Sql 'exam_db' "select count(*) from user_group_members where group_id=$idCsv and user_id='$($joiner.id)'") -eq '1' -and (Sql 'exam_db' "select count(*) from pending_group_invites where group_id=$idCsv and email_normalized='$futureEmail'") -eq '1') "status $importStatus, added $($import.data.added), invited $($import.data.invited)"
$csvAccount = RegisterOnly 'csv'
Check 'after registration, before the code is entered: still only an invite, no membership' ((Sql 'exam_db' "select count(*) from pending_group_invites where group_id=$idCsv and email_normalized='$futureEmail'") -eq '1')
$csvAccount = Verify $csvAccount
Start-Sleep -Seconds 2
Check 'once the email is verified the invite becomes a membership (source CSV) and the invite is gone' ((Sql 'exam_db' "select source from user_group_members where group_id=$idCsv and user_id='$($csvAccount.id)'") -eq 'CSV' -and (Sql 'exam_db' "select count(*) from pending_group_invites where group_id=$idCsv and email_normalized='$futureEmail'") -eq '0' -and (Sql 'exam_db' "select count(*) from group_audit_log where group_id=$idCsv and action='INVITE_APPLIED' and target_user_id='$($csvAccount.id)'") -eq '1')
Remove-Item $csvFile, "$env:TEMP\t-b5-import.json" -ErrorAction SilentlyContinue

# ---------- the two checks part 1 reported, on the data of this run ----------
$byEmail = Api 'GET' "/api/admin/participants?q=$($joiner.email)" $adm.token $null
$emailRows = @($byEmail.data.content)
$firstEmail = if ($emailRows.Count) { $emailRows[0].email } else { '(none)' }
$firstId = if ($emailRows.Count) { $emailRows[0].id } else { $null }
$byId = Api 'GET' "/api/admin/participants?q=$firstId" $adm.token $null
Check 'q by full email finds one; q by user id finds one' ($byEmail.data.totalElements -eq 1 -and $firstEmail -eq $joiner.email -and $firstId -eq $joiner.id -and $byId.data.totalElements -eq 1 -and @($byId.data.content)[0].email -eq $firstEmail) "by email: total $($byEmail.data.totalElements), first $firstEmail; by id: total $($byId.data.totalElements), first $(if (@($byId.data.content).Count) { @($byId.data.content)[0].email } else { '(none)' })"
# An exam for the groups join (1 member) and approve (4 members, the joiner is in both); then one member of approve is removed
$groupExam = Api 'POST' '/api/exams' $adm.token @{ title = "T-b5-$stamp-group"; description = 'B5 audience preview'; durationMinutes = 30
    startTime = [DateTime]::UtcNow.AddDays(1).ToString('yyyy-MM-ddTHH:mm:ss'); endTime = [DateTime]::UtcNow.AddDays(2).ToString('yyyy-MM-ddTHH:mm:ss')
    shuffleQuestions = $false; shuffleOptions = $false; showResultsImmediately = $true; maxAttempts = 1; passingScore = 50
    accessType = 'GROUP'; groupIds = @($idJoin, $idApprove)
    questions = @(@{ questionText = 'T-b5 q1: 1 + 1 = ?'; questionType = 'MULTIPLE_CHOICE'; points = 1; orderIndex = 0; allowMultipleAnswers = $false
                     options = @(@{ optionText = '2'; isCorrect = $true; orderIndex = 0 }, @{ optionText = '3'; isCorrect = $false; orderIndex = 1 }) }) }
$idExam = $groupExam.data.id
$removed = Api 'DELETE' "/api/admin/groups/$idApprove/members/$($racers[4].id)" $adm.token $null
$preview = Api 'GET' "/api/exams/$idExam/audience/preview?groupIds=$idJoin,$idApprove" $adm.token $null
$dbPerGroup = Sql 'exam_db' "select string_agg(c::text, ',' order by group_id) from (select group_id, count(*) c from user_group_members where group_id in ($idJoin,$idApprove) group by group_id) x"
$dbDistinct = Sql 'exam_db' "select count(distinct user_id) from user_group_members where group_id in ($idJoin,$idApprove)"
Check 'preview after a member was removed still agrees with the database' ($groupExam.status -eq 201 -and $removed.status -in 200, 204 -and $preview.status -eq 200 -and ((@($preview.data.perGroup) | Sort-Object groupId | ForEach-Object { $_.members }) -join ',') -eq $dbPerGroup -and "$($preview.data.distinctEligible)" -eq $dbDistinct -and $dbPerGroup -eq '1,3' -and $dbDistinct -eq '3') "exam $($groupExam.status) $($groupExam.code), remove $($removed.status); api $((@($preview.data.perGroup) | Sort-Object groupId | ForEach-Object { $_.members }) -join ','), distinct $($preview.data.distinctEligible); database $dbPerGroup / $dbDistinct"

# ---------- the groups a bulk-added participant has, and the list of a link group ----------
$joinerRow = @((Api 'GET' "/api/admin/participants?q=$($joiner.id)" $adm.token $null).data.content)[0]
$dbJoinerGroups = Sql 'exam_db' "select count(*) from user_group_members m join user_groups g on g.id=m.group_id and g.active=true where m.user_id='$($joiner.id)'"
Check 'the participant list shows every group of a participant who joined by link, by CSV and by approval' ("$($joinerRow.groupCount)" -eq $dbJoinerGroups -and $joinerRow.groupCount -ge 3 -and @($joinerRow.groups).Count -eq 3) "groupCount $($joinerRow.groupCount), database $dbJoinerGroups, shown $(@($joinerRow.groups).Count)"

$failed = @($script:results | Where-Object { -not $_.ok })
Write-Output ''
Write-Output "TOTAL $($script:results.Count)  PASS $($script:results.Count - $failed.Count)  FAIL $($failed.Count)"
Write-Output "stamp=$stamp; groups join=$idJoin approve=$idApprove limit=$idLimit csv=$idCsv; exam $idExam; accounts be-b5j-*-$stamp@x.test"
