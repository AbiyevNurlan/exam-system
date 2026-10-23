# Live verification of feat/b13a-guest-identity (B13a): the guest participants of paid open exams. Against the
# DEVELOPMENT stack only (Mailpit for the codes) with PAID_ACCESS_ENABLED=true in .env for auth-service. Creates
# be-b13a-*-<stamp>@x.test guests and one be-b13a-acc-<stamp>@x.test account (removed by scripts\cleanup-test-data.ps1,
# guests included). Admin credentials are read from F:\exam-system\.env into variables only; nothing secret is printed.
#
# Refuses to run while the public site is open (container exam-cloudflared running): the checks create data and
# block a guest. With PAID_ACCESS_ENABLED=false it only checks that every guest endpoint answers 404 FEATURE_DISABLED.
$ErrorActionPreference = 'Continue'
[Threading.Thread]::CurrentThread.CurrentCulture = [Globalization.CultureInfo]::InvariantCulture
Set-Location F:\exam-system

$tunnel = docker ps --filter 'name=^exam-cloudflared$' --filter 'status=running' --format '{{.Names}}' 2>$null
if ($tunnel) { throw 'REFUSED: the public site is open (exam-cloudflared is running). Run this only on the development stack.' }

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
function Note($name, $detail) { Write-Output "NOTE  $name  ($detail)" }
function Api($method, $path, $token, $body) {
    $headers = @{}
    if ($token) { $headers['Authorization'] = "Bearer $token" }
    $params = @{ Uri = "$GW$path"; Method = $method; Headers = $headers; UseBasicParsing = $true; TimeoutSec = 60 }
    if ($null -ne $body) {
        $json = if ($body -is [string]) { $body } else { $body | ConvertTo-Json -Depth 10 }
        # The body as UTF-8 bytes with the charset named: names like Məmmədova arrive as they are
        $params['Body'] = [Text.Encoding]::UTF8.GetBytes($json); $params['ContentType'] = 'application/json; charset=utf-8'
    }
    try {
        $r = Invoke-WebRequest @params
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
# The newest code mailed to the address (Mailpit), after a given number of earlier mails
function MailedCode($email, $earlier = 0) {
    for ($i = 0; $i -lt 40; $i++) {
        $found = Invoke-RestMethod "$MAIL/api/v1/search?query=to:$email"
        if ($found.messages_count -gt $earlier) {
            $message = Invoke-RestMethod "$MAIL/api/v1/message/$($found.messages[0].ID)"
            if ($message.Text -match '(?<!\d)(\d{6})(?!\d)') { return $Matches[1] }
        }
        Start-Sleep -Milliseconds 500
    }
    throw "no mail for $email"
}
# One rate-limit bucket per route and client address: a drained budget needs a wait
function Call($method, $path, $token, $body) {
    for ($try = 0; $try -lt 10; $try++) {
        $r = Api $method $path $token $body
        if ($r.status -ne 429) { return $r }
        Start-Sleep -Seconds 6
    }
    return $r
}

# ---------- the switch ----------
$feature = Call 'GET' '/api/guest/feature' $null $null
Check 'GET /api/guest/feature answers 200 without a token' ($feature.status -eq 200) (Answer $feature)
$enabled = [bool]$feature.data.paidAccessEnabled

$adminEmail = $null; $adminPassword = $null
foreach ($line in Get-Content F:\exam-system\.env) {
    if ($line -match '^ADMIN_EMAIL=(.*)$') { $adminEmail = $Matches[1].Trim() }
    if ($line -match '^ADMIN_PASSWORD=(.*)$') { $adminPassword = $Matches[1].Trim() }
}
$adminLogin = Call 'POST' '/auth/login' $null @{ email = $adminEmail; password = $adminPassword }
if ($adminLogin.status -ne 200) { throw "admin login answered $($adminLogin.status)" }
$admin = $adminLogin.data.accessToken

if (-not $enabled) {
    Note 'PAID_ACCESS_ENABLED is false' 'only the switch is checked; set PAID_ACCESS_ENABLED=true for auth-service on the development stack for the rest'
    foreach ($probe in @(@('POST', '/auth/guest/otp/request', $null, @{ email = "be-b13a-off-$stamp@x.test" }),
                         @('POST', '/auth/guest/otp/verify', $null, @{ email = "be-b13a-off-$stamp@x.test"; code = '123456' }),
                         @('GET', '/api/guest/me', $null, $null),
                         @('GET', '/api/admin/guests', $admin, $null))) {
        $r = Call $probe[0] $probe[1] $probe[2] $probe[3]
        Check "off: $($probe[0]) $($probe[1]) is 404 FEATURE_DISABLED" ($r.status -eq 404 -and $r.code -eq 'FEATURE_DISABLED') (Answer $r)
    }
} else {
    # ---------- a new guest ----------
    $email = "be-b13a-new-$stamp@x.test"
    $request = Call 'POST' '/auth/guest/otp/request' $null @{ email = $email }
    Check 'a code is asked for (200)' ($request.status -eq 200) (Answer $request)
    $unknown = Call 'POST' '/auth/guest/otp/request' $null @{ email = "be-b13a-nobody-$stamp@x.test" }
    Check 'the same answer for any address' ($unknown.status -eq 200 -and $unknown.json.message -eq $request.json.message) (Answer $unknown)
    $code = MailedCode $email
    $verify = Call 'POST' '/auth/guest/otp/verify' $null @{ email = $email; code = $code }
    Check 'a new guest: PROFILE_REQUIRED with a profile token' ($verify.status -eq 200 -and $verify.data.status -eq 'PROFILE_REQUIRED' -and $verify.data.profileToken) (Answer $verify)
    $profileToken = $verify.data.profileToken
    $profileBody = @{ profileToken = $profileToken; firstName = 'Aysel'; lastName = 'Məmmədova'; phone = '050 123 45 67'; consent = $true; consentVersion = '2026-10' }
    $made = Call 'POST' '/auth/guest/profile' $null $profileBody
    Check 'the profile makes the guest and signs in' ($made.status -eq 200 -and $made.data.status -eq 'SIGNED_IN' -and $made.data.accessToken) (Answer $made)
    $guest = $made.data.guest
    Check 'a 7-digit participant code, the phone in E.164, the name as written' ("$($guest.participantCode)" -match '^\d{7}$' -and $guest.phone -eq '+994501234567' -and $guest.lastName -eq 'Məmmədova') "$($guest.participantCode)"
    $again = Call 'POST' '/auth/guest/profile' $null $profileBody
    Check 'the same profile token again: the same guest' ($again.status -eq 200 -and $again.data.guest.id -eq $guest.id) (Answer $again)
    $guestToken = $made.data.accessToken
    $me = Call 'GET' '/api/guest/me' $guestToken $null
    Check 'GET /api/guest/me' ($me.status -eq 200 -and $me.data.participantCode -eq $guest.participantCode -and $me.data.email -eq $email) (Answer $me)
    $patch = Call 'PATCH' '/api/guest/me' $guestToken @{ firstName = 'Leyla'; email = 'other@x.test' }
    Check 'PATCH /api/guest/me changes the name, never the email' ($patch.status -eq 200 -and $patch.data.firstName -eq 'Leyla' -and $patch.data.email -eq $email) (Answer $patch)

    # ---------- the guest token on the accounts' endpoints ----------
    foreach ($probe in @(@('GET', '/auth/me'), @('PUT', '/auth/profile'), @('GET', '/api/exams/available'), @('GET', '/api/me/stats'),
                         @('GET', '/api/me/profile'), @('GET', '/api/admin/groups'), @('GET', '/api/admin/participants'),
                         @('GET', '/api/admin/guests'), @('GET', '/api/results/my'), @('GET', '/api/monitoring/sessions'))) {
        $r = Call $probe[0] $probe[1] $guestToken $null
        Check "guest token: $($probe[0]) $($probe[1]) refused" ($r.status -eq 401 -or $r.status -eq 403) (Answer $r)
    }

    # ---------- an account's email, an account's token ----------
    $accountEmail = "be-b13a-acc-$stamp@x.test"
    $password = 'Tt9!' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
    $reg = Call 'POST' '/auth/register' $null @{ email = $accountEmail; password = $password; firstName = 'Nb13a'; lastName = 'Account' }
    $verified = Call 'POST' '/auth/verify-otp' $null @{ email = $accountEmail; code = (MailedCode $accountEmail) }
    Check 'an account is registered as before (201, then verified)' ($reg.status -eq 201 -and $verified.status -eq 200) "$(Answer $reg) / $(Answer $verified)"
    $studentToken = $verified.data.accessToken
    $collisionAsk = Call 'POST' '/auth/guest/otp/request' $null @{ email = $accountEmail }
    Check "an account's email: the same answer to the code request" ($collisionAsk.status -eq 200 -and $collisionAsk.json.message -eq $request.json.message) (Answer $collisionAsk)
    $collision = Call 'POST' '/auth/guest/otp/verify' $null @{ email = $accountEmail; code = (MailedCode $accountEmail 1) }
    Check "an account's email: 409 EMAIL_BELONGS_TO_ACCOUNT after the code" ($collision.status -eq 409 -and $collision.code -eq 'EMAIL_BELONGS_TO_ACCOUNT') (Answer $collision)
    $studentOnGuest = Call 'GET' '/api/guest/me' $studentToken $null
    Check 'a STUDENT token on /api/guest/me is 403' ($studentOnGuest.status -eq 403) (Answer $studentOnGuest)
    $adminOnGuest = Call 'GET' '/api/guest/me' $admin $null
    Check 'an ADMIN token on /api/guest/me is 403' ($adminOnGuest.status -eq 403) (Answer $adminOnGuest)
    $studentMe = Call 'GET' '/auth/me' $studentToken $null
    Check 'the account signs in and reads /auth/me as before' ($studentMe.status -eq 200) (Answer $studentMe)

    # ---------- wrong codes ----------
    $wrongEmail = "be-b13a-wrong-$stamp@x.test"
    Call 'POST' '/auth/guest/otp/request' $null @{ email = $wrongEmail } | Out-Null
    $right = MailedCode $wrongEmail
    $wrong = if ($right -eq '000000') { '111111' } else { '000000' }
    $answers = @(1..5 | ForEach-Object { (Call 'POST' '/auth/guest/otp/verify' $null @{ email = $wrongEmail; code = $wrong }).code })
    $burned = Call 'POST' '/auth/guest/otp/verify' $null @{ email = $wrongEmail; code = $right }
    Check 'five wrong codes burn the code' (($answers | Where-Object { $_ -ne 'OTP_INVALID' }).Count -eq 0 -and $burned.code -eq 'OTP_INVALID') "$($answers -join ',') then $(Answer $burned)"
    $cooldown = Call 'POST' '/auth/guest/otp/request' $null @{ email = $wrongEmail }
    $mails = (Invoke-RestMethod "$MAIL/api/v1/search?query=to:$wrongEmail").messages_count
    Check 'a second code within 60 s is not sent, the answer is the same' ($cooldown.status -eq 200 -and $mails -eq 1) "$mails mail(s)"

    # ---------- refresh ----------
    $refreshed = Call 'POST' '/auth/guest/refresh' $null @{ refreshToken = $made.data.refreshToken }
    Check 'refresh hands out new tokens' ($refreshed.status -eq 200 -and $refreshed.data.refreshToken -ne $made.data.refreshToken) (Answer $refreshed)
    $reused = Call 'POST' '/auth/guest/refresh' $null @{ refreshToken = $made.data.refreshToken }
    Check 'a used refresh token is refused' ($reused.status -eq 401 -and $reused.code -eq 'REFRESH_TOKEN_INVALID') (Answer $reused)

    # ---------- the admin ----------
    $found = Call 'GET' "/api/admin/guests?q=$($guest.participantCode)" $admin $null
    Check 'the admin finds the guest by the 7-digit code' ($found.status -eq 200 -and $found.data.totalElements -eq 1 -and $found.data.content[0].id -eq $guest.id) (Answer $found)
    $sorted = Call 'GET' '/api/admin/guests?sortBy=lastName&dir=asc' $admin $null
    $badSort = Call 'GET' '/api/admin/guests?sortBy=password_hash' $admin $null
    Check 'sortBy is a whitelist' ($sorted.status -eq 200 -and $badSort.status -eq 400) "$(Answer $sorted) / $(Answer $badSort)"
    $card = Call 'GET' "/api/admin/guests/$($guest.id)" $admin $null
    Check 'the card of the guest' ($card.status -eq 200 -and $card.data.participantCode -eq $guest.participantCode -and $card.data.phone -eq '+994501234567') (Answer $card)
    $noReason = Call 'PATCH' "/api/admin/guests/$($guest.id)/block" $admin @{ blocked = $true }
    Check 'a block without a reason is 400' ($noReason.status -eq 400) (Answer $noReason)
    $block = Call 'PATCH' "/api/admin/guests/$($guest.id)/block" $admin @{ blocked = $true; reason = 'B13a yoxlaması' }
    Check 'the admin blocks the guest' ($block.status -eq 200 -and $block.data.blocked -eq $true) (Answer $block)
    $blockedMe = Call 'GET' '/api/guest/me' $refreshed.data.accessToken $null
    $blockedRefresh = Call 'POST' '/auth/guest/refresh' $null @{ refreshToken = $refreshed.data.refreshToken }
    Check 'a blocked guest: /api/guest/me 403 GUEST_BLOCKED, refresh refused' ($blockedMe.code -eq 'GUEST_BLOCKED' -and ($blockedRefresh.status -eq 401 -or $blockedRefresh.status -eq 403)) "$(Answer $blockedMe) / $(Answer $blockedRefresh)"
    $studentList = Call 'GET' '/api/admin/guests' $studentToken $null
    Check 'a STUDENT token on /api/admin/guests is 403' ($studentList.status -eq 403) (Answer $studentList)
    $unblock = Call 'PATCH' "/api/admin/guests/$($guest.id)/block" $admin @{ blocked = $false; reason = 'Yoxlama bitdi' }
    Check 'the admin unblocks the guest' ($unblock.status -eq 200 -and $unblock.data.blocked -eq $false) (Answer $unblock)

    # ---------- the same guest again, only with the email and a code (after the 60 s cooldown) ----------
    Write-Output 'Waiting 61 s for the cooldown of the first address ...'
    Start-Sleep -Seconds 61
    Call 'POST' '/auth/guest/otp/request' $null @{ email = $email } | Out-Null
    $back = Call 'POST' '/auth/guest/otp/verify' $null @{ email = $email; code = (MailedCode $email 1) }
    Check 'back with the email and a code: the same guest and the same ID' ($back.status -eq 200 -and $back.data.status -eq 'SIGNED_IN' -and $back.data.guest.id -eq $guest.id -and $back.data.guest.participantCode -eq $guest.participantCode) (Answer $back)

    # ---------- what is stored ----------
    $plain = Sql 'exam_auth' "SELECT count(*) FROM guest_email_otp WHERE code_hash IN ('$code', '$right')"
    Check 'codes are stored only as HMAC' ($plain -eq '0') "$plain plain codes"
    $audit = Sql 'exam_auth' "SELECT string_agg(DISTINCT action, ',' ORDER BY action) FROM guest_audit_log WHERE target_guest_id = '$($guest.id)'"
    Check 'the audit has every step of the guest' ($audit -match 'GUEST_BLOCKED' -and $audit -match 'GUEST_CREATED' -and $audit -match 'GUEST_LOGIN' -and $audit -match 'GUEST_OTP_VERIFIED' -and $audit -match 'GUEST_UNBLOCKED' -and $audit -match 'GUEST_UPDATED') $audit
    $fullEmails = Sql 'exam_auth' "SELECT count(*) FROM guest_audit_log WHERE email_masked LIKE '%be-b13a%'"
    Check 'the audit keeps emails masked' ($fullEmails -eq '0') "$fullEmails full addresses"
    $codes = Sql 'exam_auth' "SELECT count(*) FROM (SELECT participant_code FROM users UNION ALL SELECT participant_code FROM guest_participants) c GROUP BY participant_code HAVING count(*) > 1"
    Check 'no code is both an account''s and a guest''s' ([string]::IsNullOrWhiteSpace("$codes")) "$codes"
    Note 'test data' "guests and the account be-b13a-*-$stamp@x.test stay; scripts\cleanup-test-data.ps1 lists them (dry run)"
}

$failed = @($script:results | Where-Object { -not $_.ok })
Write-Output ''
Write-Output ("SUMMARY: {0} checks, {1} failed" -f $script:results.Count, $failed.Count)
if ($failed.Count -gt 0) { $failed | ForEach-Object { Write-Output "  FAIL  $($_.name)" }; exit 1 }
exit 0
