# Live verification of feat/b11-participant-profile (B11): the participant profile (GET/PUT /api/me/profile),
# the admin's participant card (GET /api/admin/participants/{id}) with its access rules, the administrator's
# PATCH of a profile with its audit row, the category and institution filters, and the immutable participant code.
# Development stack only (Mailpit for the codes); never against the public mode: it refuses to run while the
# edge of the public mode runs, because both modes share one database. Creates T-b11-<stamp> groups and
# be-b11-*-<stamp>@x.test accounts; two of them are INSTRUCTOR for the run and STUDENT again in the finally block.
# Admin credentials are read from F:\exam-system\.env into variables only; nothing secret is printed.
$ErrorActionPreference = 'Continue'
[Threading.Thread]::CurrentThread.CurrentCulture = [Globalization.CultureInfo]::InvariantCulture
Set-Location F:\exam-system
$GW = 'http://127.0.0.1:8080'
. "$PSScriptRoot\_common.ps1"
Wait-Gateway $GW
$MAIL = 'http://127.0.0.1:8025'
$stamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString('x')
$T = "T-b11-$stamp"
$script:results = @()

if ((docker inspect -f '{{.State.Running}}' exam-edge 2>$null) -eq 'true') {
    Write-Output 'NOT RUN: the public mode is up (exam-edge runs) and shares the database: stop it first (.\scripts\go-public.ps1 stop)'
    exit 2
}

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
function Sql($db, $query) { docker exec exam-postgres psql -U postgres -d $db -Atc $query 2>&1 }
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
    $email = "be-b11-$name-$stamp@x.test"
    $r = AuthCall '/auth/register' @{ email = $email; password = $password; firstName = "Nb11${stamp}$name"; lastName = "L$name" }
    if ($r.status -ne 201) { throw "register $name answered $($r.status)" }
    $v = AuthCall '/auth/verify-otp' @{ email = $email; code = (MailedCode $email) }
    if ($v.status -ne 200) { throw "verify $name answered $($v.status)" }
    return @{ email = $email; name = $name; id = $v.data.user.id; token = $v.data.accessToken }
}
function Login($email, $pw) { (AuthCall '/auth/login' @{ email = $email; password = $pw }) }

# ---------- setup ----------
$adminEmail = $null; $adminPassword = $null
foreach ($line in Get-Content F:\exam-system\.env) {
    if ($line -match '^ADMIN_EMAIL=(.*)$') { $adminEmail = $Matches[1].Trim() }
    if ($line -match '^ADMIN_PASSWORD=(.*)$') { $adminPassword = $Matches[1].Trim() }
}
$mainLogin = Login $adminEmail $adminPassword
if ($mainLogin.status -ne 200) { throw "admin login answered $($mainLogin.status)" }
$adm = @{ id = $mainLogin.data.user.id; token = $mainLogin.data.accessToken }

$p1 = Register 'p1'
$p2 = Register 'p2'
$ins = Register 'ins'
$other = Register 'other'
try {
Sql 'exam_auth' "update users set role='INSTRUCTOR' where email in ('$($ins.email)','$($other.email)')" | Out-Null
$ins.token = (Login $ins.email $password).data.accessToken
$other.token = (Login $other.email $password).data.accessToken
Write-Output "run $stamp; participants p1, p2; instructors ins (owner of the group of p1), other"

$group = Api 'POST' '/api/admin/groups' $ins.token @{ name = "$T group"; description = 'B11 verification' }
$null = Api 'POST' "/api/admin/groups/$($group.data.id)/members" $ins.token @{ userId = $p1.id }
Check 'setup: a group of ins with p1' ($group.status -in 200, 201 -and (Sql 'exam_db' "select count(*) from user_group_members where group_id=$($group.data.id) and user_id='$($p1.id)'") -eq '1') (Answer $group)

# ---------- 1. own profile ----------
$none = Api 'GET' '/api/me/profile' $p1.token $null
Check 'a new account has no profile (null) and gets the categories' ($none.status -eq 200 -and $null -eq $none.data.profile -and @($none.data.categories) -contains 'UNIVERSITY_STUDENT') (Answer $none)
Check 'GET /api/me/profile without a token: 401' ((Api 'GET' '/api/me/profile' $null $null).status -eq 401)
$saved = Api 'PUT' '/api/me/profile' $p1.token @{ category = ' university_student '; institution = '  <b>ADA</b> University  '; city = 'Baki<script>x</script>'; courseOrGrade = '2' }
Check 'PUT /api/me/profile: category upper case, text trimmed, HTML removed' ($saved.status -eq 200 -and $saved.data.category -eq 'UNIVERSITY_STUDENT' -and $saved.data.institution -eq 'ADA University' -and $saved.data.city -eq 'Baki x') "$(Answer $saved) $($saved.data.institution) / $($saved.data.city)"
Check 'the own change is audited with before and after' ((Sql 'exam_auth' "select count(*) from admin_audit_log where action='PROFILE_DETAILS_UPDATED' and admin_user_id='$($p1.id)' and target_user_id='$($p1.id)' and details like '%category: - -> ''UNIVERSITY_STUDENT''%'") -eq '1')
$bad = Api 'PUT' '/api/me/profile' $p1.token @{ category = 'ALIEN' }
Check 'an unknown category: 400 VALIDATION_ERROR naming the field' ($bad.status -eq 400 -and $bad.code -eq 'VALIDATION_ERROR' -and $bad.raw -match 'category') (Answer $bad)
$long = Api 'PUT' '/api/me/profile' $p1.token @{ category = 'OTHER'; city = ('a' * 101) }
Check 'a city of 101 characters: 400, nothing changed' ($long.status -eq 400 -and (Api 'GET' '/api/me/profile' $p1.token $null).data.profile.category -eq 'UNIVERSITY_STUDENT') (Answer $long)

# ---------- 2. participant card and who may see it ----------
$me = Api 'GET' '/auth/me' $p1.token $null
$card = Api 'GET' "/api/admin/participants/$($p1.id)" $adm.token $null
Check 'ADMIN: the card with code, profile, group and scope ALL' ($card.status -eq 200 -and $card.data.participantCode -eq $me.data.participantCode -and $card.data.profile.institution -eq 'ADA University' -and @($card.data.groups).groupId -contains $group.data.id -and $card.data.scope -eq 'ALL') (Answer $card)
Check 'the card has no hash, token or password field' ($card.raw -notmatch '(?i)"(password|passwordHash|password_hash|token|refreshToken|otp)"')
$legacy = Api 'GET' "/api/admin/participants/$($p2.id)" $adm.token $null
Check 'an account without a profile: profile null, no error' ($legacy.status -eq 200 -and $null -eq $legacy.data.profile) (Answer $legacy)
$own = Api 'GET' "/api/admin/participants/$($p1.id)" $ins.token $null
Check 'INSTRUCTOR owning a group of p1: the card with scope OWN and no events' ($own.status -eq 200 -and $own.data.scope -eq 'OWN' -and @($own.data.recentEvents).Count -eq 0) (Answer $own)
Check 'INSTRUCTOR without a group of p1: 404' ((Api 'GET' "/api/admin/participants/$($p1.id)" $other.token $null).status -eq 404)
Check 'INSTRUCTOR, a participant in none of the own groups: 404' ((Api 'GET' "/api/admin/participants/$($p2.id)" $ins.token $null).status -eq 404)
Check 'STUDENT: 403 for another card and for the own' ((Api 'GET' "/api/admin/participants/$($p1.id)" $p2.token $null).status -eq 403 -and (Api 'GET' "/api/admin/participants/$($p1.id)" $p1.token $null).status -eq 403)
Check 'an unknown id: 404' ((Api 'GET' "/api/admin/participants/$([Guid]::NewGuid())" $adm.token $null).status -eq 404)
$sessions = Api 'GET' "/api/admin/participants/$($p1.id)/sessions?page=0&size=500" $adm.token $null
Check 'the attempts are paged (size capped at 50)' ($sessions.status -eq 200 -and $sessions.data.size -eq 50) (Answer $sessions)
Check 'INSTRUCTOR cannot list participants or read the summary' ((Api 'GET' '/api/admin/participants' $ins.token $null).status -eq 403 -and (Api 'GET' '/api/admin/participants/summary' $ins.token $null).status -eq 403)

# ---------- 3. administrator's change ----------
$patch = Api 'PATCH' "/api/admin/participants/$($p1.id)/profile" $adm.token @{ city = 'Gence'; courseOrGrade = '' }
Check 'ADMIN PATCH: a given field changes, an empty one is cleared, the rest stays' ($patch.status -eq 200 -and $patch.data.city -eq 'Gence' -and $null -eq $patch.data.courseOrGrade -and $patch.data.institution -eq 'ADA University' -and $patch.data.updatedByAdmin) (Answer $patch)
Check 'audited with the administrator as actor and before/after' ((Sql 'exam_auth' "select count(*) from admin_audit_log where action='PARTICIPANT_PROFILE_UPDATED' and admin_user_id='$($adm.id)' and target_user_id='$($p1.id)' and details like '%city: ''Baki x'' -> ''Gence''%'") -eq '1')
$after = Api 'GET' "/api/admin/participants/$($p1.id)" $adm.token $null
$event = @($after.data.recentEvents) | Where-Object { $_.action -eq 'PARTICIPANT_PROFILE_UPDATED' } | Select-Object -First 1
Check 'the card lists the change with the actor and target codes' ($event -and $event.actorCode -and $event.targetUserCode -eq $me.data.participantCode -and $event.details -match 'Gence')
Check 'INSTRUCTOR and STUDENT cannot PATCH a profile: 403' ((Api 'PATCH' "/api/admin/participants/$($p1.id)/profile" $ins.token @{ city = 'X' }).status -eq 403 -and (Api 'PATCH' "/api/admin/participants/$($p1.id)/profile" $p1.token @{ city = 'X' }).status -eq 403)

# ---------- 4. filters ----------
$byCategory = Api 'GET' "/api/admin/participants?category=UNIVERSITY_STUDENT&q=be-b11&size=50" $adm.token $null
Check 'filter by category: p1, not p2' ($byCategory.status -eq 200 -and @($byCategory.data.content).email -contains $p1.email -and @($byCategory.data.content).email -notcontains $p2.email) (Answer $byCategory)
$byInstitution = Api 'GET' "/api/admin/participants?institution=ada%20uni&q=be-b11&size=50" $adm.token $null
Check 'filter by the start of the institution, ignoring case' ($byInstitution.status -eq 200 -and @($byInstitution.data.content).email -contains $p1.email) (Answer $byInstitution)
$byCode = Api 'GET' "/api/admin/participants?q=$($me.data.participantCode)" $adm.token $null
Check 'the 7-digit code search still finds p1' ($byCode.status -eq 200 -and @($byCode.data.content).email -contains $p1.email) (Answer $byCode)

# ---------- 5. the participant code cannot change ----------
$change = Sql 'exam_auth' "update users set participant_code = participant_code + 1 where id='$($p1.id)'"
Check 'the database refuses to change a participant code' ("$change" -match 'cannot be changed' -and (Sql 'exam_auth' "select participant_code from users where id='$($p1.id)'") -eq "$($me.data.participantCode)") "$change"
$codes = & powershell -NoProfile -ExecutionPolicy Bypass -File F:\exam-system\scripts\check-participant-codes.ps1
Check 'check-participant-codes.ps1: PASS' ($LASTEXITCODE -eq 0) (($codes | Select-Object -Last 1) -join '')
} finally {
    Sql 'exam_auth' "update users set role='STUDENT' where email in ('$($ins.email)','$($other.email)')" | Out-Null
}

$failed = @($script:results | Where-Object { -not $_.ok }).Count
Write-Output "TOTAL: $($script:results.Count - $failed) PASS, $failed FAIL"
Write-Output ''
Write-Output 'Test data of this run (dry run, nothing is deleted):'
& powershell -NoProfile -ExecutionPolicy Bypass -File F:\exam-system\scripts\cleanup-test-data.ps1 | Select-Object -First 30
