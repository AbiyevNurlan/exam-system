# Live verification of feat/b11b-participant-sorting (B11b): GET /api/admin/participants with sortBy (code, firstName,
# lastName, email, registeredAt) and dir (asc, desc): Azerbaijani alphabetical order ignoring case and the spaces
# around a name, both directions, pages without a repeat or a gap, the whitelist (400 VALIDATION_ERROR), sorting
# together with the category, institution and 7-digit code filters, the older sort/order unchanged, INSTRUCTOR 403.
# Development stack only; never against the public mode: it refuses to run while the edge of the public mode runs,
# because both modes share one database. Creates be-b11b-<stamp>-NN@x.test accounts (registered, no mail needed);
# one of them is INSTRUCTOR for the run and STUDENT again in the finally block. No group or exam is created.
# Admin credentials are read from F:\exam-system\.env into variables only; nothing secret is printed.
$ErrorActionPreference = 'Continue'
[Threading.Thread]::CurrentThread.CurrentCulture = [Globalization.CultureInfo]::InvariantCulture
Set-Location F:\exam-system
$GW = 'http://127.0.0.1:8080'
. "$PSScriptRoot\_common.ps1"
$stamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString('x')
$Q = "be-b11b-$stamp"
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
$password = 'Tt9!' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
function AuthCall($path, $body) {
    for ($try = 0; $try -lt 10; $try++) {
        $r = Api 'POST' $path $null $body
        if ($r.status -ne 429) { return $r }
        Start-Sleep -Seconds 6
    }
    return $r
}
function Login($email, $pw) { (AuthCall '/auth/login' @{ email = $email; password = $pw }) }
# One page of this run's participants (the email prefix of the run as q)
function Page($query) { Api 'GET' "/api/admin/participants?q=$Q&size=50&$query" $adm.token $null }
function FirstNames($r) { (@($r.data.content) | ForEach-Object { "$($_.firstName)".Trim() }) -join ' ' }

# ---------- setup ----------
$adminEmail = $null; $adminPassword = $null
foreach ($line in Get-Content F:\exam-system\.env) {
    if ($line -match '^ADMIN_EMAIL=(.*)$') { $adminEmail = $Matches[1].Trim() }
    if ($line -match '^ADMIN_PASSWORD=(.*)$') { $adminPassword = $Matches[1].Trim() }
}
$mainLogin = Login $adminEmail $adminPassword
if ($mainLogin.status -ne 200) { throw "admin login answered $($mainLogin.status)" }
$adm = @{ id = $mainLogin.data.user.id; token = $mainLogin.data.accessToken }

# Registered in this order, so the participant codes grow in this order. "Əli" and "əli" are the same name
# ignoring case: their codes decide. "  Zaur" has spaces in front (registration keeps them, the sort trims).
$names = @('Zaur', 'Üzeyir', 'Şəkər', 'Ömər', 'İlqar', 'Işıq', 'Xanım', 'Həsən', 'Ğəni', 'Əli', 'Çingiz', 'Cavid',
    'Babək', 'Aysel', 'əli', '  Zəfər', 'Elçin', 'Kamal', 'Qasım', 'Lalə')
$expected = 'Aysel Babək Cavid Çingiz Elçin Əli əli Ğəni Həsən Xanım Işıq İlqar Kamal Qasım Lalə Ömər Şəkər Üzeyir Zaur Zəfər'
$i = 0
$accounts = @()
foreach ($n in $names) {
    $i++
    $email = ('{0}-{1:00}@x.test' -f $Q, $i)
    $r = AuthCall '/auth/register' @{ email = $email; password = $password; firstName = $n; lastName = 'Testov' }
    if ($r.status -ne 201) { throw "register $email answered $($r.status)" }
    $accounts += $email
}
$ins = @{ email = "$Q-ins@x.test" }
$r = AuthCall '/auth/register' @{ email = $ins.email; password = $password; firstName = 'Ins'; lastName = 'Testov' }
if ($r.status -ne 201) { throw "register ins answered $($r.status)" }
Write-Output "run $stamp; $($accounts.Count) participants $Q-NN@x.test, instructor $Q-ins@x.test"

# Every role given below is taken back in the finally block before the summary
try {
Sql 'exam_auth' "update users set role='INSTRUCTOR', email_verified=true where email='$($ins.email)'" | Out-Null
$ins.token = (Login $ins.email $password).data.accessToken

# ---------- 1. Azerbaijani order, both directions ----------
$asc = Page 'sortBy=firstName'
Check 'sortBy=firstName: Azerbaijani alphabet, case ignored, spaces trimmed' ($asc.status -eq 200 -and (FirstNames $asc) -ceq $expected) (FirstNames $asc)
$desc = Page 'sortBy=firstName&dir=desc'
$reversed = (@($expected -split ' ') | Select-Object -Last 100)
[array]::Reverse($reversed)
Check 'dir=desc: exactly the reverse (equal names by code, reversed too)' ($desc.status -eq 200 -and (FirstNames $desc) -ceq ($reversed -join ' ')) (FirstNames $desc)
$byLast = Page 'sortBy=lastName'
Check 'sortBy=lastName: equal last names by first name' ($byLast.status -eq 200 -and (FirstNames $byLast) -ceq $expected) (FirstNames $byLast)
$codes = @(@($asc.data.content) | Where-Object { "$($_.firstName)" -ieq 'əli' } | ForEach-Object { [int]$_.participantCode })
Check 'Əli and əli: the smaller participant code first' ($codes.Count -eq 2 -and $codes[0] -lt $codes[1]) ($codes -join ' < ')

# ---------- 2. the other keys ----------
$byCode = @((Page 'sortBy=code').data.content | ForEach-Object { [int]$_.participantCode })
$byCodeDesc = @((Page 'sortBy=code&dir=desc').data.content | ForEach-Object { [int]$_.participantCode })
Check 'sortBy=code asc and desc' ($byCode.Count -eq 20 -and (($byCode | Sort-Object) -join ',') -eq ($byCode -join ',') -and (($byCode | Sort-Object -Descending) -join ',') -eq ($byCodeDesc -join ',')) ($byCode -join ',')
$byEmail = @((Page 'sortBy=email').data.content | ForEach-Object { $_.email })
$emailOrder = (1..$names.Count | ForEach-Object { '{0}-{1:00}@x.test' -f $Q, $_ }) -join ','
Check 'sortBy=email: byte order of the address' (($byEmail -join ',') -eq $emailOrder) ($byEmail[0..2] -join ', ')
$byTime = @((Page 'sortBy=registeredAt').data.content | ForEach-Object { [DateTime]$_.registeredAt })
$byTimeDesc = @((Page 'sortBy=registeredAt&dir=desc').data.content | ForEach-Object { [DateTime]$_.registeredAt })
Check 'sortBy=registeredAt asc and desc' ($byTime.Count -eq 20 -and (($byTime | Sort-Object) -join ',') -eq ($byTime -join ',') -and (($byTime | Sort-Object -Descending) -join ',') -eq ($byTimeDesc -join ','))

# ---------- 3. pages without a repeat or a gap ----------
foreach ($order in 'sortBy=firstName', 'sortBy=firstName&dir=desc', 'sortBy=registeredAt&dir=desc') {
    $seen = @()
    for ($p = 0; $p -lt 8; $p++) {
        $seen += @((Api 'GET' "/api/admin/participants?q=$Q&size=3&page=$p&$order" $adm.token $null).data.content | ForEach-Object { $_.id })
    }
    $whole = @((Page $order).data.content | ForEach-Object { $_.id })
    Check "pages of 3 ($order): every participant once, in the order of one page of 50" ($seen.Count -eq 20 -and @($seen | Select-Object -Unique).Count -eq 20 -and ($seen -join ',') -eq ($whole -join ',')) "$($seen.Count) rows, $(@($seen | Select-Object -Unique).Count) different"
}

# ---------- 4. the whitelist ----------
foreach ($bad in 'sortBy=password_hash', 'sortBy=name', 'sortBy=last_name%3B%20DROP%20TABLE%20users', 'sortBy=code&dir=up') {
    $r = Page $bad
    Check "$bad`: 400 VALIDATION_ERROR" ($r.status -eq 400 -and $r.code -eq 'VALIDATION_ERROR') (Answer $r)
}
Check 'the users table is still there' ((Sql 'exam_auth' "select count(*) from users where email like '$Q-%'") -eq '21')

# ---------- 5. filters together with the order ----------
$ids = @{}
foreach ($row in @($asc.data.content)) { $ids["$($row.firstName)".Trim()] = $row }
foreach ($n in 'Şəkər', 'Cavid', 'İlqar') {
    $patched = Api 'PATCH' "/api/admin/participants/$($ids[$n].id)/profile" $adm.token @{ category = 'UNIVERSITY_STUDENT'; institution = "B11b $stamp Universiteti" }
    if ($patched.status -ne 200) { Write-Output "NOTE  profile of $n answered $(Answer $patched)" }
}
$filtered = Page "category=UNIVERSITY_STUDENT&institution=B11b%20$stamp&sortBy=firstName&dir=desc"
Check 'category + institution + sortBy=firstName desc' ($filtered.status -eq 200 -and (FirstNames $filtered) -ceq 'Şəkər İlqar Cavid') (FirstNames $filtered)
$code = $ids['Ömər'].participantCode
$one = Api 'GET' "/api/admin/participants?q=$code&sortBy=lastName&dir=desc" $adm.token $null
Check '7-digit code search + sortBy: that account' ($one.status -eq 200 -and @($one.data.content).Count -eq 1 -and @($one.data.content)[0].participantCode -eq $code) (Answer $one)

# ---------- 6. the older parameters and the roles ----------
$default = @((Page '').data.content | ForEach-Object { [DateTime]$_.registeredAt })
Check 'without sortBy: newest registrations first, as before' ((($default | Sort-Object -Descending) -join ',') -eq ($default -join ','))
$legacy = Page 'sort=name'
Check 'sort=name (older form): still 200, by last name' ($legacy.status -eq 200 -and (FirstNames $legacy) -ceq $expected) (Answer $legacy)
Check 'INSTRUCTOR: 403 with sortBy too' ((Api 'GET' "/api/admin/participants?sortBy=firstName" $ins.token $null).status -eq 403)
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
Write-Output "stamp=$stamp; accounts $Q-*@x.test"
Write-Output ''
Write-Output 'Test data (dry run, nothing is deleted):'
& powershell -NoProfile -ExecutionPolicy Bypass -File F:\exam-system\scripts\cleanup-test-data.ps1 | Select-Object -First 30
