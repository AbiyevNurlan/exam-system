# B6 step 1 live: rate limit buckets per route and address. Creates t-b6-<stamp>-*@x.test accounts.
$ErrorActionPreference = 'Continue'
$GW = 'http://127.0.0.1:8080'
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

# 1. Empty the verification code budget of this address
$otp = @()
for ($i = 0; $i -lt 30; $i++) { $otp += (Post '/auth/verify-otp' @{ email = "t-b6-$stamp-nobody@x.test"; code = '000000' }).status }
$first429 = [array]::IndexOf($otp, 429)
Check 'verify-otp: its own budget runs out (429 after ~10 requests of 5 tokens)' ($first429 -ge 5 -and $first429 -le 12) "statuses: $(($otp | Group-Object | ForEach-Object { "$($_.Name)x$($_.Count)" }) -join ' ')"

# 2. Registration and sign-in from the same address are not refused
$reg = Post '/auth/register' @{ email = "t-b6-$stamp-first@x.test"; password = 'Tt9!' + [Guid]::NewGuid().ToString('N').Substring(0, 12); firstName = 'Bsix'; lastName = 'First' }
$login = Post '/auth/login' @{ email = "t-b6-$stamp-first@x.test"; password = 'wrong-password-1' }
$otpAgain = Post '/auth/verify-otp' @{ email = "t-b6-$stamp-nobody@x.test"; code = '000000' }
Check 'with the code budget empty: register 201 and login 401 (not 429); verify-otp itself still 429' ($reg.status -eq 201 -and $login.status -eq 401 -and $otpAgain.status -eq 429) "register $($reg.status), login $($login.status), verify-otp $($otpAgain.status)"
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
