# Live verification of feat/b13-teacher-isolation (B13): teacher accounts made by ADMIN (POST/GET/PATCH
# /api/admin/instructors, reset-password: a temporary password shown once, a change forced before anything else,
# ending after 7 days, tokens ended at once on deactivation and reset, audit without passwords), the isolation of
# two teachers (another teacher's exam, group, results are the same 404 as a missing id; lists hold only the own
# rows; PUBLIC audience for ADMIN only), the own audit trail, the start flags of /api/me/stats and the teacher limits.
# Development stack only; never against the public mode: it refuses to run while the edge of the public mode runs,
# because both modes share one database. Creates T-b13-<stamp>-* exams and groups and be-b13-<stamp>-*@x.test
# accounts (the teachers through the new API, the participant registered and verified by SQL, no mail needed).
# Admin credentials are read from F:\exam-system\.env into variables only; no password or token is printed.
$ErrorActionPreference = 'Continue'
[Threading.Thread]::CurrentThread.CurrentCulture = [Globalization.CultureInfo]::InvariantCulture
Set-Location F:\exam-system
$GW = 'http://127.0.0.1:8080'
. "$PSScriptRoot\_common.ps1"
$stamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString('x')
$Q = "be-b13-$stamp"
$T = "T-b13-$stamp"
$script:results = @()

if ((docker inspect -f '{{.State.Running}}' exam-edge 2>$null) -eq 'true') {
    Write-Output 'NOT RUN: the public mode is up (exam-edge runs) and shares the database: stop it first (.\scripts\go-public.ps1 stop)'
    exit 2
}
Wait-Gateway $GW

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
        $json = if ($body -is [string]) { $body } else { $body | ConvertTo-Json -Depth 12 }
        $params['Body'] = [Text.Encoding]::UTF8.GetBytes($json); $params['ContentType'] = 'application/json'
    }
    try {
        $r = Invoke-WebRequest @params
        # Always UTF-8 from the raw bytes (PowerShell 5.1 decodes a body without a charset as ISO-8859-1)
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
function Sql($db, $query) { docker exec exam-postgres psql -U postgres -d $db -Atc $query 2>&1 }
function Answer($r) { "$($r.status) $($r.code)" }
$password = 'Tt9!' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
function AuthCall($path, $body, $token = $null) {
    for ($try = 0; $try -lt 10; $try++) {
        $r = Api 'POST' $path $token $body
        if ($r.status -ne 429) { return $r }
        Start-Sleep -Seconds 6
    }
    return $r
}
function Login($email, $pw) { (AuthCall '/auth/login' @{ email = $email; password = $pw }) }
function Utc([DateTime]$t) { $t.ToString('yyyy-MM-ddTHH:mm:ss') }
function Mc($text) {
    @{ questionText = $text; questionType = 'MULTIPLE_CHOICE'; points = 1; orderIndex = 0; allowMultipleAnswers = $false
       options = @(@{ optionText = 'A'; isCorrect = $true; orderIndex = 0 }, @{ optionText = 'B'; isCorrect = $false; orderIndex = 1 }) }
}
function ExamBody($title) {
    $now = [DateTime]::UtcNow
    @{ title = "$T $title"; description = 'B13 verification'; durationMinutes = 30
       startTime = (Utc $now.AddMinutes(30)); endTime = (Utc $now.AddDays(1))
       shuffleQuestions = $false; shuffleOptions = $false; showResultsImmediately = $true; maxAttempts = 1; passingScore = 50
       blocks = @(@{ title = 'Blok'; questions = @((Mc 'q1')) }) }
}
# The instructor create call is limited to 2 a minute by the gateway: wait out a 429
function Admin($method, $path, $body) {
    for ($try = 0; $try -lt 8; $try++) {
        $r = Api $method $path $adm.token $body
        if ($r.status -ne 429) { return $r }
        Start-Sleep -Seconds 15
    }
    return $r
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
$stuEmail = "$Q-stu@x.test"
$reg = AuthCall '/auth/register' @{ email = $stuEmail; password = $password; firstName = 'B13stu'; lastName = 'Testov' }
if ($reg.status -ne 201) { throw "register answered $($reg.status)" }
Sql 'exam_auth' "update users set email_verified=true where email='$stuEmail'" | Out-Null
$stu = @{ token = (Login $stuEmail $password).data.accessToken }
Write-Output "run $stamp; exams and groups '$T *', accounts $Q-*@x.test"

# ---------- 1. teacher accounts by ADMIN ----------
$denied = Api 'POST' '/api/admin/instructors' $stu.token @{ firstName = 'X'; lastName = 'Y'; email = "$Q-no@x.test" }
Check 'a participant cannot create a teacher: 403' ($denied.status -eq 403) (Answer $denied)
$createdA = Admin 'POST' '/api/admin/instructors' @{ firstName = 'B13A'; lastName = 'Müəllim'; email = "$Q-ia@x.test" }
$createdB = Admin 'POST' '/api/admin/instructors' @{ firstName = 'B13B'; lastName = 'Müəllim'; email = "$Q-ib@x.test" }
$a = @{ id = $createdA.data.id; email = "$Q-ia@x.test"; temp = $createdA.data.temporaryPassword }
$b = @{ id = $createdB.data.id; email = "$Q-ib@x.test"; temp = $createdB.data.temporaryPassword }
Check 'ADMIN creates a teacher: 201, INSTRUCTOR, must change the password, a temporary password of 16 characters' (
    $createdA.status -eq 201 -and $createdA.data.role -eq 'INSTRUCTOR' -and $createdA.data.mustChangePassword -eq $true -and
    "$($a.temp)".Length -eq 16 -and $createdB.status -eq 201) "$(Answer $createdA) / $(Answer $createdB)"
$expires = [DateTime]$createdA.data.temporaryPasswordExpiresAt
Check 'the temporary password ends after 7 days' ([Math]::Abs(($expires - [DateTime]::UtcNow).TotalDays - 7) -lt 0.6 -or
    [Math]::Abs(($expires - [DateTime]::Now).TotalDays - 7) -lt 0.6) "$($createdA.data.temporaryPasswordExpiresAt)"
$again = Admin 'POST' '/api/admin/instructors' @{ firstName = 'B13A'; lastName = 'Again'; email = "$Q-ia@x.test" }
Check 'the same email again: 409 EMAIL_ALREADY_EXISTS' ($again.status -eq 409 -and $again.code -eq 'EMAIL_ALREADY_EXISTS') (Answer $again)
$list = Api 'GET' "/api/admin/instructors?q=$Q&size=50" $adm.token $null
$rows = @($list.data.content)
Check 'the list: both teachers, PASSWORD_CHANGE_REQUIRED, with their figures and no password' ($list.status -eq 200 -and $rows.Count -eq 2 -and
    @($rows | Where-Object { $_.status -eq 'PASSWORD_CHANGE_REQUIRED' -and $_.figuresAvailable -eq $true }).Count -eq 2 -and
    $list.raw -notmatch 'temporaryPassword"\s*:\s*"' -and $list.raw -notmatch [regex]::Escape("$($a.temp)")) "$(Answer $list), $($rows.Count) rows"
Check 'the list is for ADMIN only: a teacher gets 403' ((Api 'GET' '/api/admin/instructors' $stu.token $null).status -eq 403)
$audit = Sql 'exam_auth' "select count(*) from admin_audit_log where action='INSTRUCTOR_CREATED' and target_user_id in ('$($a.id)','$($b.id)')"
$leak = Sql 'exam_auth' "select count(*) from admin_audit_log where coalesce(details,'') like '%$($a.temp)%' or coalesce(details,'') like '%$($b.temp)%'"
Check 'INSTRUCTOR_CREATED audited twice, no temporary password in any row' ($audit -eq '2' -and $leak -eq '0') "created=$audit, leaks=$leak"

# ---------- 2. the temporary password ----------
$first = Login $a.email $a.temp
Check 'sign-in with the temporary password: 200, mustChangePassword' ($first.status -eq 200 -and $first.data.mustChangePassword -eq $true) (Answer $first)
$blocked = Api 'GET' '/api/admin/groups' $first.data.accessToken $null
Check 'with that token anything but the password change is 403 PASSWORD_CHANGE_REQUIRED' ($blocked.status -eq 403 -and $blocked.code -eq 'PASSWORD_CHANGE_REQUIRED') (Answer $blocked)
$newA = 'Nn8!' + [Guid]::NewGuid().ToString('N').Substring(0, 14)
$changed = AuthCall '/auth/change-password' @{ currentPassword = $a.temp; newPassword = $newA } $first.data.accessToken
Check 'the teacher sets an own password: 200' ($changed.status -eq 200) (Answer $changed)
$stale = Api 'GET' '/auth/me' $first.data.accessToken $null
Check 'the token of the temporary password ends at once: 401' ($stale.status -eq 401) (Answer $stale)
Check 'the temporary password no longer works' ((Login $a.email $a.temp).status -eq 401)
$loginA = Login $a.email $newA
$a.token = $loginA.data.accessToken
Check 'sign-in with the own password: 200, no change required' ($loginA.status -eq 200 -and $loginA.data.mustChangePassword -eq $false) (Answer $loginA)

# B: the temporary password has ended (moved back by SQL), then a reset by ADMIN
Sql 'exam_auth' "update users set temp_password_expires_at = now() - interval '1 minute' where id='$($b.id)'" | Out-Null
$ended = Login $b.email $b.temp
Check 'an ended temporary password: 401 TEMPORARY_PASSWORD_EXPIRED' ($ended.status -eq 401 -and $ended.code -eq 'TEMPORARY_PASSWORD_EXPIRED') (Answer $ended)
$reset = Admin 'POST' "/api/admin/instructors/$($b.id)/reset-password" @{}
$b.temp = $reset.data.temporaryPassword
Check 'reset by ADMIN: a new temporary password of 16 characters' ($reset.status -eq 200 -and "$($b.temp)".Length -eq 16) (Answer $reset)
Check 'PASSWORD_RESET audited without the password' ((Sql 'exam_auth' "select count(*) from admin_audit_log where action='INSTRUCTOR_PASSWORD_RESET' and target_user_id='$($b.id)' and coalesce(details,'') not like '%$($b.temp)%'") -eq '1')
$firstB = Login $b.email $b.temp
$newB = 'Nn8!' + [Guid]::NewGuid().ToString('N').Substring(0, 14)
AuthCall '/auth/change-password' @{ currentPassword = $b.temp; newPassword = $newB } $firstB.data.accessToken | Out-Null
$b.token = (Login $b.email $newB).data.accessToken
Check 'B signs in after the reset and the change' ($null -ne $b.token)

# ---------- 3. a new teacher's start flags ----------
$flags = Api 'GET' '/api/me/stats' $b.token $null
Check 'a new teacher: hasGroups, hasExams, hasMembers all false' ($flags.status -eq 200 -and $flags.data.hasGroups -eq $false -and
    $flags.data.hasExams -eq $false -and $flags.data.hasMembers -eq $false) (Answer $flags)

# ---------- 4. two teachers ----------
$groupA = Api 'POST' '/api/admin/groups' $a.token @{ name = "$T A"; description = 'B13' }
$groupB = Api 'POST' '/api/admin/groups' $b.token @{ name = "$T B"; description = 'B13' }
$sameName = Api 'POST' '/api/admin/groups' $b.token @{ name = "$T A"; description = 'the name of the other teacher' }
Check 'each teacher makes a group; the same name for another owner is allowed' ($groupA.status -eq 201 -and $groupB.status -eq 201 -and $sameName.status -eq 201) "$(Answer $groupA) / $(Answer $groupB) / $(Answer $sameName)"
$examA = Api 'POST' '/api/exams' $a.token (ExamBody 'A')
$examB = Api 'POST' '/api/exams' $b.token (ExamBody 'B')
Check 'a teacher''s new exam is for groups (not PUBLIC)' ($examA.status -eq 201 -and $examA.data.accessType -eq 'GROUP') "$(Answer $examA) $($examA.data.accessType)"
$public = Api 'PUT' "/api/exams/$($examA.data.id)/audience" $a.token @{ accessType = 'PUBLIC'; groupIds = @() }
Check 'PUBLIC audience is for ADMIN only: 403 PUBLIC_EXAM_ADMIN_ONLY' ($public.status -eq 403 -and $public.code -eq 'PUBLIC_EXAM_ADMIN_ONLY') (Answer $public)
$aud = Api 'PUT' "/api/exams/$($examA.data.id)/audience" $a.token @{ accessType = 'GROUP'; groupIds = @($groupA.data.id) }
Check 'A gives the own exam to the own group' ($aud.status -eq 200) (Answer $aud)

$missing = 987654321
foreach ($probe in @(
    @('read the exam', 'GET', "/api/exams/$($examA.data.id)", "/api/exams/$missing", $null),
    @('change the exam', 'PATCH', "/api/exams/$($examA.data.id)/meta", "/api/exams/$missing/meta", @{ title = "$T taken" }),
    @('results', 'GET', "/api/exams/$($examA.data.id)/results", "/api/exams/$missing/results", $null),
    @('history', 'GET', "/api/exams/$($examA.data.id)/history", "/api/exams/$missing/history", $null),
    @('copy', 'POST', "/api/exams/$($examA.data.id)/clone", "/api/exams/$missing/clone", @{}),
    @('delete the exam', 'DELETE', "/api/exams/$($examA.data.id)", "/api/exams/$missing", $null),
    @('read the group', 'GET', "/api/admin/groups/$($groupA.data.id)", "/api/admin/groups/$missing", $null),
    @('members', 'GET', "/api/admin/groups/$($groupA.data.id)/members", "/api/admin/groups/$missing/members", $null),
    @('add a member', 'POST', "/api/admin/groups/$($groupA.data.id)/members", "/api/admin/groups/$missing/members", @{ email = "$Q-x@x.test" }),
    @('the join link', 'GET', "/api/groups/$($groupA.data.id)/join-link", "/api/groups/$missing/join-link", $null),
    @('delete the group', 'DELETE', "/api/admin/groups/$($groupA.data.id)", "/api/admin/groups/$missing", $null))) {
    $onA = Api $probe[1] $probe[2] $b.token $probe[4]
    $onMissing = Api $probe[1] $probe[3] $b.token $probe[4]
    Check "B on A: $($probe[0]) is the same 404 as a missing id" ($onA.status -eq 404 -and (Answer $onA) -eq (Answer $onMissing)) "$(Answer $onA) / $(Answer $onMissing)"
}
Check 'nothing of A changed' ((Sql 'exam_db' "select count(*) from exams where id=$($examA.data.id) and active and title='$T A'") -eq '1' -and
    (Sql 'exam_db' "select count(*) from user_groups where id=$($groupA.data.id) and active") -eq '1')
$groupsOfB = Api 'GET' "/api/admin/groups?name=$T&size=100" $b.token $null
Check 'B''s group list: only B''s groups' ($groupsOfB.status -eq 200 -and @($groupsOfB.data.content | Where-Object { [long]$_.id -eq [long]$groupA.data.id }).Count -eq 0 -and
    @($groupsOfB.data.content).Count -eq 2) "$(@($groupsOfB.data.content).Count) groups"
$examsOfB = Api 'GET' "/api/exams/manage?search=$T&size=100" $b.token $null
Check 'B''s exam list: only B''s exam' ($examsOfB.status -eq 200 -and @($examsOfB.data.content).Count -eq 1 -and [long]@($examsOfB.data.content)[0].id -eq [long]$examB.data.id) "$(@($examsOfB.data.content).Count) exams"
$examsOfAdmin = Api 'GET' "/api/exams/manage?search=$T&size=100" $adm.token $null
Check 'ADMIN sees the exams of both' (@($examsOfAdmin.data.content).Count -eq 2)

# ---------- 5. members and participants ----------
$known = Api 'POST' "/api/admin/groups/$($groupA.data.id)/members" $a.token @{ email = $stuEmail }
$unknown = Api 'POST' "/api/admin/groups/$($groupA.data.id)/members" $a.token @{ email = "$Q-nobody@x.test" }
Check 'a teacher adding by email gets the same answer for an account and for an unknown address' ($known.status -eq $unknown.status -and
    $known.data.status -eq 'INVITED' -and $unknown.data.status -eq 'INVITED' -and $null -eq $known.data.userId) "$(Answer $known) $($known.data.status) / $(Answer $unknown) $($unknown.data.status)"
$stuId = Sql 'exam_auth' "select id from users where email='$stuEmail'"
Sql 'exam_db' "insert into user_group_members (group_id, user_id, source, added_by) values ($($groupA.data.id), '$stuId', 'ADMIN', '$($adm.id)') on conflict do nothing" | Out-Null
$partsA = Api 'GET' '/api/admin/participants?size=100' $a.token $null
$partsB = Api 'GET' '/api/admin/participants?size=100' $b.token $null
Check 'A''s participants: exactly A''s member, without blocked or lock state' ($partsA.status -eq 200 -and @($partsA.data.content).Count -eq 1 -and
    @($partsA.data.content)[0].id -eq $stuId) "$(@($partsA.data.content).Count) rows"
Check 'B''s participants: none (A''s member is not B''s)' ($partsB.status -eq 200 -and @($partsB.data.content).Count -eq 0) "$(@($partsB.data.content).Count) rows"
$card = Api 'GET' "/api/admin/participants/$stuId" $b.token $null
Check 'B cannot open the card of A''s member: 404' ($card.status -eq 404) (Answer $card)
$adminFilter = Api 'GET' '/api/admin/participants?blocked=true' $a.token $null
Check 'the admin filters (blocked) are 400 for a teacher' ($adminFilter.status -eq 400) (Answer $adminFilter)

# ---------- 6. the own audit trail ----------
$trailA = Api 'GET' '/api/admin/audit?size=100' $a.token $null
$actors = @($trailA.data.content | ForEach-Object { $_.actorId } | Select-Object -Unique)
Check 'a teacher reads the own audit trail: only the own actions' ($trailA.status -eq 200 -and @($trailA.data.content).Count -gt 0 -and
    $actors.Count -eq 1 -and $actors[0] -eq $a.id) "$(Answer $trailA), $(@($trailA.data.content).Count) rows"
$trailOther = Api 'GET' "/api/admin/audit?actorId=$($a.id)&size=100" $b.token $null
Check 'B asking for A''s actions gets an empty page' ($trailOther.status -eq 200 -and @($trailOther.data.content).Count -eq 0) (Answer $trailOther)
Check 'no row of a teacher''s trail carries an ip address or a user agent' ($trailA.raw -notmatch '"(ip|ipAddress|userAgent)"\s*:\s*"[^"]')

# ---------- 7. limits ----------
Check 'a teacher cannot read the limits: 403' ((Api 'GET' "/api/admin/instructor-limits/$($a.id)" $a.token $null).status -eq 403)
$setLimit = Api 'PUT' "/api/admin/instructor-limits/$($a.id)" $adm.token @{ maxGroups = 1 }
Check 'ADMIN sets A''s group limit to 1' ($setLimit.status -eq 200 -and $setLimit.data.maxGroups -eq 1 -and $setLimit.data.usedGroups -eq 1) (Answer $setLimit)
$second = Api 'POST' '/api/admin/groups' $a.token @{ name = "$T A2"; description = 'over the limit' }
Check 'a second group over the limit: 409 TEACHER_LIMIT_REACHED, nothing written' ($second.status -eq 409 -and $second.code -eq 'TEACHER_LIMIT_REACHED' -and
    (Sql 'exam_db' "select count(*) from user_groups where name='$T A2'") -eq '0') (Answer $second)
Check 'INSTRUCTOR_LIMITS_CHANGED audited' ((Sql 'exam_db' "select count(*) from group_audit_log where action='INSTRUCTOR_LIMITS_CHANGED' and target_user_id='$($a.id)'") -eq '1')
$flagsA = Api 'GET' '/api/me/stats' $a.token $null
Check 'A''s start flags: a group, an exam and a member' ($flagsA.data.hasGroups -eq $true -and $flagsA.data.hasExams -eq $true -and $flagsA.data.hasMembers -eq $true)

# ---------- 8. deactivation ----------
$off = Admin 'PATCH' "/api/admin/instructors/$($b.id)" @{ active = $false }
Check 'ADMIN deactivates B: 200' ($off.status -eq 200 -and $off.data.active -eq $false) (Answer $off)
$after = Api 'GET' '/api/admin/groups' $b.token $null
Check 'B''s token ends at once: 401' ($after.status -eq 401) (Answer $after)
Check 'B can no longer sign in' ((Login $b.email $newB).status -in 401, 403)
Check 'INSTRUCTOR_DEACTIVATED audited' ((Sql 'exam_auth' "select count(*) from admin_audit_log where action='INSTRUCTOR_DEACTIVATED' and target_user_id='$($b.id)'") -eq '1')

$failed = @($script:results | Where-Object { -not $_.ok })
Write-Output ''
Write-Output "TOTAL $($script:results.Count)  PASS $($script:results.Count - $failed.Count)  FAIL $($failed.Count)"
Write-Output "stamp=$stamp; exams and groups '$T *', accounts $Q-*@x.test"
Write-Output ''
Write-Output 'Test data (dry run, nothing is deleted):'
& powershell -NoProfile -ExecutionPolicy Bypass -File F:\exam-system\scripts\cleanup-test-data.ps1 | Select-Object -First 30
