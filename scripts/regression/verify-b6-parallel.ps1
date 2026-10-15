# B6 step 5 live: two admins add the same filter to the same group at the same moment; the participant
# list while auth-service is stopped. Creates T-b6-<stamp>-par* groups and t-b6-adm2-<stamp>@x.test
# (ADMIN for the test only, STUDENT again at the end). Admin credentials from .env, never printed.
param([string]$Filter = 't-b6-6acfabc6', [int]$Rounds = 5)
$ErrorActionPreference = 'Continue'
Set-Location F:\exam-system
Add-Type -AssemblyName System.Net.Http
$GW = 'http://127.0.0.1:8080'
$stamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString('x')
$script:results = @()
function Check($name, $ok, $detail = '') { $script:results += [bool]$ok; Write-Output ("$(if ($ok) { 'PASS' } else { 'FAIL' })  $name" + $(if ($detail) { "  ($detail)" } else { '' })) }
function Setting($n) { ((Get-Content .env | Where-Object { $_ -match "^\s*$n\s*=" } | Select-Object -First 1) -replace "^\s*$n\s*=\s*", '').Trim('"').Trim("'") }
$client = New-Object System.Net.Http.HttpClient
$client.Timeout = [TimeSpan]::FromSeconds(120)
function Send($method, $path, $token, $body) {
    $req = New-Object System.Net.Http.HttpRequestMessage ([System.Net.Http.HttpMethod]::new($method)), "$GW$path"
    if ($token) { $req.Headers.Authorization = New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Bearer', $token) }
    if ($null -ne $body) { $req.Content = New-Object System.Net.Http.StringContent(($body | ConvertTo-Json -Depth 5 -Compress), [Text.Encoding]::UTF8, 'application/json') }
    return $client.SendAsync($req)
}
function Result($task) {
    $r = $task.Result
    $text = $r.Content.ReadAsStringAsync().Result
    $json = $null; try { $json = $text | ConvertFrom-Json } catch {}
    return @{ status = [int]$r.StatusCode; data = $json.data; code = $json.error.code; details = $json.error.details }
}
function Call($method, $path, $token, $body) { Result (Send $method $path $token $body) }
function Sql($q) { docker exec exam-postgres psql -U postgres -d exam_db -Atc $q }

$admin1 = (Call 'POST' '/auth/login' $null @{ email = (Setting 'ADMIN_EMAIL'); password = (Setting 'ADMIN_PASSWORD') }).data.accessToken
$pw = 'Tt9!' + [Guid]::NewGuid().ToString('N').Substring(0, 12)
$email2 = "t-b6-adm2-$stamp@x.test"
$reg = Call 'POST' '/auth/register' $null @{ email = $email2; password = $pw; firstName = 'Bsix'; lastName = 'Admin2' }
# The role given below is taken back in the finally block before the summary
try {
docker exec exam-postgres psql -U postgres -d exam_auth -Atc "update users set role='ADMIN', email_verified=true where email='$email2'" | Out-Null
$admin2 = (Call 'POST' '/auth/login' $null @{ email = $email2; password = $pw }).data.accessToken
Check 'setup: a second admin (test account) signed in' ($reg.status -eq 201 -and $admin2) "register $($reg.status)"

$matching = (Call 'GET' "/api/admin/participants?q=$Filter&page=0&size=1" $admin1 $null).data.totalElements
for ($round = 1; $round -le $Rounds; $round++) {
    $group = (Call 'POST' '/api/admin/groups' $admin1 @{ name = "T-b6-$stamp-par$round" }).data.id
    $dry1 = Call 'POST' "/api/groups/$group/members/bulk-by-filter" $admin1 @{ filter = @{ q = $Filter }; dryRun = $true }
    $dry2 = Call 'POST' "/api/groups/$group/members/bulk-by-filter" $admin2 @{ filter = @{ q = $Filter }; dryRun = $true }
    # Both real runs leave at the same moment
    $t1 = Send 'POST' "/api/groups/$group/members/bulk-by-filter" $admin1 @{ filter = @{ q = $Filter }; dryRun = $false; expectedCount = $dry1.data.willAdd }
    $t2 = Send 'POST' "/api/groups/$group/members/bulk-by-filter" $admin2 @{ filter = @{ q = $Filter }; dryRun = $false; expectedCount = $dry2.data.willAdd }
    [Threading.Tasks.Task]::WaitAll(@($t1, $t2))
    $r1 = Result $t1; $r2 = Result $t2
    $rows = [int](Sql "select count(*) from user_group_members where group_id=$group")
    $distinct = [int](Sql "select count(distinct user_id) from user_group_members where group_id=$group")
    $statuses = "$($r1.status)$(if ($r1.code) { " $($r1.code)" }) / $($r2.status)$(if ($r2.code) { " $($r2.code)" })"
    $added = "added $($r1.data.added)+$($r2.data.added)"
    Check "round ${round}: both at once -> no 500, members = matches ($matching), no duplicate" ($r1.status -lt 500 -and $r2.status -lt 500 -and $rows -eq $matching -and $distinct -eq $rows) "$statuses; $added; rows $rows distinct $distinct"
}

# The participant list while auth-service is stopped
$before = Call 'GET' '/api/admin/participants?page=0&size=1' $admin1 $null
cmd /c "docker stop exam-auth-service >nul"
$down = Call 'GET' '/api/admin/participants?page=0&size=1' $admin1 $null
Check 'auth-service stopped: GET /api/admin/participants -> 503 USER_DIRECTORY_UNAVAILABLE' ($before.status -eq 200 -and $down.status -eq 503 -and $down.code -eq 'USER_DIRECTORY_UNAVAILABLE') "before $($before.status); down $($down.status) $($down.code)"
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\up.ps1 auth-service | Out-Null
$back = $null
for ($i = 0; $i -lt 60; $i++) {
    Start-Sleep -Seconds 3
    $back = Call 'GET' '/api/admin/participants?page=0&size=1' $admin1 $null
    if ($back.status -eq 200) { break }
}
Check 'after scripts\up.ps1 auth-service the list answers again' ($back.status -eq 200) "status $($back.status) after $([Math]::Round(($i + 1) * 3)) s"
} finally {
    # The test account gets the participant role back, whatever happened above (cleanup-test-data.ps1 keeps every ADMIN)
    if ($email2 -like '*@x.test') {
        $given = docker exec exam-postgres psql -U postgres -d exam_auth -Atc "update users set role='STUDENT' where role <> 'STUDENT' and email='$email2'"
        Write-Output "roles given back: $given"
    }
}
Write-Output "stamp $stamp; groups T-b6-$stamp-par1..$Rounds; second admin $email2 is STUDENT again"
Write-Output "$(($script:results | Where-Object { $_ }).Count) / $($script:results.Count) passed"
