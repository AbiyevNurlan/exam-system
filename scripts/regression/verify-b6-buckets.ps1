# B6 step 1 live: rate limit buckets per route and address. Creates t-b6-<stamp>-*@x.test accounts.
$ErrorActionPreference = 'Continue'
$GW = 'http://127.0.0.1:8080'
. "$PSScriptRoot\_common.ps1"
Wait-Gateway $GW
$stamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString('x')
$script:results = @()
function Check($name, $ok, $detail = '') {
    $script:results += [bool]$ok
    $line = $(if ($ok) { 'PASS' } else { 'FAIL' }) + "  $name"
    if ($detail -ne '') { $line += "  ($detail)" }
    Write-Output $line
}
function Post($path, $body) {
    $request = [Net.HttpWebRequest]::Create("$GW$path")
    $request.Method = 'POST'; $request.ContentType = 'application/json'; $request.Timeout = 30000
    $bytes = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Compress))
    $request.ContentLength = $bytes.Length
    $s = $request.GetRequestStream(); $s.Write($bytes, 0, $bytes.Length); $s.Close()
    try { $r = $request.GetResponse() } catch [Net.WebException] { $r = $_.Exception.Response }
    if (-not $r) { return @{ status = 0 } }
    $status = [int]$r.StatusCode; $retry = $r.Headers['Retry-After']; $r.Close()
    return @{ status = $status; retry = $retry }
}
function Keys { docker exec exam-redis redis-cli --scan --pattern 'request_rate_limiter*tokens' }

# 1. Empty the verification code budget of this address. The route refills GATEWAY_OTP_REPLENISH tokens a second up to
# GATEWAY_OTP_BURST and a request takes 5 (refused ones take none), so the bucket is first given the time to be full
# again (other scripts and earlier runs leave it part-empty); then burst / 5 requests pass and the next one is refused.
function GatewaySetting($name, $default) {
    $v = "$(docker exec exam-api-gateway printenv $name 2>$null)".Trim()
    if ($v -match '^\d+$' -and [int]$v -gt 0) { return [int]$v } else { return $default }
}
$replenish = GatewaySetting 'GATEWAY_OTP_REPLENISH' 1
$burst = GatewaySetting 'GATEWAY_OTP_BURST' 50
$fill = [Math]::Min([int][Math]::Ceiling($burst / $replenish) + 2, 180)
Write-Output "NOTE  waiting $fill s for the verify-otp bucket to be full again (replenish $replenish/s, burst $burst)"
Start-Sleep -Seconds $fill
$passing = [int][Math]::Floor($burst / 5)
$otp = @()
for ($i = 0; $i -lt ($passing + 20); $i++) { $otp += (Post '/auth/verify-otp' @{ email = "t-b6-$stamp-nobody@x.test"; code = '000000' }).status }
$first429 = [array]::IndexOf($otp, 429)
# Requests take a few milliseconds each, so at most one or two extra tokens come back while the loop runs
Check "verify-otp: its own budget runs out (429 after ~$passing requests of 5 tokens)" ($first429 -ge ($passing - 1) -and $first429 -le ($passing + 2)) "first 429 at request $($first429 + 1); statuses: $(($otp | Group-Object | ForEach-Object { "$($_.Name)x$($_.Count)" }) -join ' ')"

# 2. Registration and sign-in from the same address are not refused
$reg = Post '/auth/register' @{ email = "t-b6-$stamp-first@x.test"; password = 'Tt9!' + [Guid]::NewGuid().ToString('N').Substring(0, 12); firstName = 'Bsix'; lastName = 'First' }
$login = Post '/auth/login' @{ email = "t-b6-$stamp-first@x.test"; password = 'wrong-password-1' }
# Register and login took about two seconds, long enough for one request (5 tokens) to be allowed again; a short
# series right after them must still run into the limit (the first may pass, the rest cannot)
$otpAgain = @(1..3 | ForEach-Object { (Post '/auth/verify-otp' @{ email = "t-b6-$stamp-nobody@x.test"; code = '000000' }).status })
Check 'with the code budget empty: register 201 and login 401 (not 429); verify-otp itself still limited (429 within 3 quick tries)' ($reg.status -eq 201 -and $login.status -eq 401 -and ($otpAgain -contains 429)) "register $($reg.status), login $($login.status), verify-otp $($otpAgain -join ' ')"
$keys = @(Keys)
Check 'Redis: one bucket per route and address' (($keys | Where-Object { $_ -match 'auth-otp:' }) -and ($keys | Where-Object { $_ -match 'auth-register:' }) -and ($keys | Where-Object { $_ -match 'auth-login:' })) ($keys -join ', ')

# 3. 300 registrations from one address within five minutes
$watch = [Diagnostics.Stopwatch]::StartNew()
$statuses = @()
for ($i = 1; $i -le 300; $i++) {
    $statuses += (Post '/auth/register' @{ email = ('t-b6-{0}-{1:000}@x.test' -f $stamp, $i); password = 'Tt9!' + [Guid]::NewGuid().ToString('N').Substring(0, 12); firstName = 'Bsix'; lastName = ('N{0:000}' -f $i) }).status
}
$watch.Stop()
$grouped = ($statuses | Group-Object | ForEach-Object { "$($_.Name)x$($_.Count)" }) -join ' '
Check '300 registrations from one address: all 201, no 429, within 5 minutes' (($statuses | Where-Object { $_ -ne 201 }).Count -eq 0 -and $watch.Elapsed.TotalMinutes -lt 5) "$grouped in $([Math]::Round($watch.Elapsed.TotalSeconds)) s"
$after = Post '/auth/verify-otp' @{ email = "t-b6-$stamp-nobody@x.test"; code = '000000' }
Check 'verify-otp of the same address is still limited after that' ($after.status -in 429, 400) "status $($after.status)"

Write-Output "stamp $stamp; accounts t-b6-$stamp-first and t-b6-$stamp-001..300 @x.test"
Write-Output "$(($script:results | Where-Object { $_ }).Count) / $($script:results.Count) passed"
