# Live verification of feat/b13b-paid-exams (B13b): paid open exams with the sandbox provider, the whole way:
# catalog -> order -> sandbox payment -> signed notification -> entitlement -> exam start -> result -> refund refused
# and accepted. Against the DEVELOPMENT stack only, with PAID_ACCESS_ENABLED=true (auth-service and exam-service) and
# PAYMENTS_SANDBOX_ALLOWED=true (exam-service) in .env; Mailpit for the guests' codes. Creates a T-b13b-<stamp> exam
# and be-b13b-*-<stamp>@x.test guests (scripts\cleanup-test-data.ps1 removes them, orders and payments included).
# Admin credentials are read from F:\exam-system\.env into variables only; nothing secret is printed.
# Refuses to run while the public site is open or on a database with real accounts (Wait-Gateway, Assert-SafeTarget).
$ErrorActionPreference = 'Continue'
[Threading.Thread]::CurrentThread.CurrentCulture = [Globalization.CultureInfo]::InvariantCulture
Set-Location F:\exam-system
$GW = 'http://127.0.0.1:8080'
. "$PSScriptRoot\_common.ps1"
Wait-Gateway $GW
$MAIL = 'http://127.0.0.1:8025'
$stamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString('x')
$T = "T-b13b-$stamp"
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
function Call($method, $path, $token, $body) {
    for ($try = 0; $try -lt 10; $try++) {
        $r = Api $method $path $token $body
        if ($r.status -ne 429) { return $r }
        Start-Sleep -Seconds 6
    }
    return $r
}
function Answer($r) { "$($r.status) $($r.code)" }
function Utc($time) { $time.ToString('yyyy-MM-ddTHH:mm:ss') }
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
# A guest of B13a: email, code, profile; the access token
function NewGuest($name) {
    $email = "be-b13b-$name-$stamp@x.test"
    Call 'POST' '/auth/guest/otp/request' $null @{ email = $email } | Out-Null
    $verify = Call 'POST' '/auth/guest/otp/verify' $null @{ email = $email; code = (MailedCode $email) }
    $made = Call 'POST' '/auth/guest/profile' $null @{ profileToken = $verify.data.profileToken; firstName = 'Nb13b'; lastName = "Q$name";
                                                      phone = '050 123 45 67'; consent = $true; consentVersion = '2026-10' }
    if ($made.status -ne 200) { throw "guest $name answered $(Answer $made)" }
    return @{ email = $email; token = $made.data.accessToken; id = $made.data.guest.id; code = $made.data.guest.participantCode }
}
# The sandbox's own payment page: "Uğurlu ödəniş" or "Uğursuz ödəniş"
function SandboxPay($checkoutUrl, $result) {
    $path = $checkoutUrl -replace '^https?://[^/]+', ''
    $page = Invoke-WebRequest -Uri "$GW$path" -UseBasicParsing -TimeoutSec 30
    $complete = ($path -replace '\?sig=', '/complete?sig=') + "&result=$result"
    $done = Invoke-WebRequest -Uri "$GW$complete" -Method POST -UseBasicParsing -TimeoutSec 30
    return @{ page = [int]$page.StatusCode; done = [int]$done.StatusCode; text = [Text.Encoding]::UTF8.GetString($page.RawContentStream.ToArray()) }
}

# ---------- switches ----------
$feature = Call 'GET' '/api/guest/feature' $null $null
if (-not $feature.data.paidAccessEnabled) { throw 'PAID_ACCESS_ENABLED is false on this stack: set it (and PAYMENTS_SANDBOX_ALLOWED) for the development stack first' }

$adminEmail = $null; $adminPassword = $null
foreach ($line in Get-Content F:\exam-system\.env) {
    if ($line -match '^ADMIN_EMAIL=(.*)$') { $adminEmail = $Matches[1].Trim() }
    if ($line -match '^ADMIN_PASSWORD=(.*)$') { $adminPassword = $Matches[1].Trim() }
}
$login = Call 'POST' '/auth/login' $null @{ email = $adminEmail; password = $adminPassword }
if ($login.status -ne 200) { throw "admin login answered $($login.status)" }
$admin = $login.data.accessToken

# ---------- a paid exam: starts in 4 minutes, sales until 1 minute before, 2 seats, refunds until the start ----------
$now = [DateTime]::UtcNow
$start = $now.AddMinutes(4)
$examBody = @{ title = "$T Riyaziyyat"; description = 'B13b verification'; durationMinutes = 20; startTime = (Utc $start); endTime = (Utc $start.AddHours(1))
               shuffleQuestions = $false; shuffleOptions = $false; showResultsImmediately = $true; maxAttempts = 1; passingScore = 50; accessType = 'PUBLIC'
               questions = @(@{ questionText = "${T}: 2 + 2 = ?"; questionType = 'MULTIPLE_CHOICE'; points = 1; orderIndex = 0; allowMultipleAnswers = $false
                                options = @(@{ optionText = '4'; isCorrect = $true; orderIndex = 0 }, @{ optionText = '5'; isCorrect = $false; orderIndex = 1 }) }) }
$exam = Call 'POST' '/api/exams' $admin $examBody
Check 'the exam is created' ($exam.status -eq 201) (Answer $exam)
$examId = $exam.data.id
$published = Call 'POST' "/api/exams/$examId/publish" $admin $null
Check 'the exam is published' ($published.status -eq 200) (Answer $published)
$settings = Call 'PUT' "/api/admin/paid-exams/$examId/settings" $admin @{ accessMode = 'PAID_OPEN'; priceMinor = 1500; currency = 'AZN'; seatLimit = 2
            salesOpenAt = (Utc $now.AddMinutes(-1)); salesCloseAt = (Utc $start.AddMinutes(-1)); refundDeadlineHours = 0; attemptsPerPurchase = 1 }
Check 'ADMIN makes it PAID_OPEN (15,00 AZN, 2 seats)' ($settings.status -eq 200 -and $settings.data.accessMode -eq 'PAID_OPEN') (Answer $settings)
$wrongWindow = Call 'PUT' "/api/admin/paid-exams/$examId/settings" $admin @{ accessMode = 'PAID_OPEN'; priceMinor = 1500; salesOpenAt = (Utc $now); salesCloseAt = (Utc $start.AddHours(2)) }
Check 'a sales window after the start is refused' ($wrongWindow.status -eq 400 -and $wrongWindow.code -eq 'PAID_SETTINGS_INVALID') (Answer $wrongWindow)

# ---------- the catalog ----------
$catalog = Call 'GET' '/api/public/paid-exams' $null $null
$item = $catalog.data.content | Where-Object { $_.id -eq $examId }
Check 'the catalog shows it without a token, price and seats' ($catalog.status -eq 200 -and $item.priceMinor -eq 1500 -and $item.seatsLeft -eq 2) (Answer $catalog)
Check 'the catalog shows no question or internal field' (-not ($catalog.raw -match '2 \+ 2|questions|createdBy|seatsTaken'))
$one = Call 'GET' "/api/public/paid-exams/$examId" $null $null
Check 'one exam of the catalog' ($one.status -eq 200 -and $one.data.title -eq "$T Riyaziyyat") (Answer $one)

# ---------- guest A: order, sandbox payment, entitlement ----------
$a = NewGuest 'a'
$order = Call 'POST' '/api/guest/orders' $a.token @{ examId = $examId }
Check 'guest A orders (PENDING, 1500 AZN, checkoutUrl)' ($order.status -eq 200 -and $order.data.status -eq 'PENDING' -and $order.data.amountMinor -eq 1500 -and $order.data.checkoutUrl) (Answer $order)
$again = Call 'POST' '/api/guest/orders' $a.token @{ examId = $examId }
Check 'the same order again (idempotent)' ($again.data.id -eq $order.data.id) (Answer $again)
$studentTry = Call 'GET' '/api/guest/orders' $admin $null
Check 'an account token on /api/guest/orders is 403' ($studentTry.status -eq 403) (Answer $studentTry)
$returnPage = Call 'GET' "/api/guest/orders/$($order.data.id)" $a.token $null
Check 'before the payment the status is PENDING (the browser decides nothing)' ($returnPage.data.status -eq 'PENDING') (Answer $returnPage)
$paid = SandboxPay $order.data.checkoutUrl 'success'
Check 'the sandbox page and its successful payment' ($paid.page -eq 200 -and $paid.done -eq 200 -and $paid.text -match 'Uğurlu ödəniş') "$($paid.page)/$($paid.done)"
$afterPay = Call 'GET' "/api/guest/orders/$($order.data.id)" $a.token $null
Check 'the signed notification made the order PAID' ($afterPay.data.status -eq 'PAID' -and $afterPay.data.paidAt) (Answer $afterPay)
$forged = Api 'POST' '/api/payments/webhook/sandbox' $null ('{"eventId":"forged-' + $stamp + '","type":"PAYMENT_SUCCEEDED","orderNo":"' + $order.data.orderNo + '","amountMinor":1500,"currency":"AZN"}')
Check 'a forged notification is 401 SIGNATURE_INVALID' ($forged.status -eq 401 -and $forged.code -eq 'SIGNATURE_INVALID') (Answer $forged)
$entitled = Call 'POST' '/api/guest/orders' $a.token @{ examId = $examId }
Check 'ordering again is 409 ALREADY_ENTITLED' ($entitled.status -eq 409 -and $entitled.code -eq 'ALREADY_ENTITLED') (Answer $entitled)

# ---------- guest B: pays, asks for a refund before the start, the administrator refunds ----------
$b = NewGuest 'b'
$orderB = Call 'POST' '/api/guest/orders' $b.token @{ examId = $examId }
$payB = SandboxPay $orderB.data.checkoutUrl 'success'
$requestB = Call 'POST' "/api/guest/orders/$($orderB.data.id)/refund-request" $b.token $null
Check 'guest B asks for a refund before the start: REFUND_PENDING' ($requestB.status -eq 200 -and $requestB.data.status -eq 'REFUND_PENDING') (Answer $requestB)
$noReason = Call 'POST' "/api/admin/orders/$($orderB.data.id)/refund" $admin @{ reason = '' }
Check 'a refund without a reason is refused' ($noReason.status -eq 400) (Answer $noReason)
$refundB = Call 'POST' "/api/admin/orders/$($orderB.data.id)/refund" $admin @{ reason = 'Namizəd imtina etdi' }
Check 'the administrator refunds: REFUNDED' ($refundB.status -eq 200 -and $refundB.data -eq 'REFUNDED') (Answer $refundB)
$startB = Call 'POST' "/api/guest/exams/$examId/start" $b.token $null
Check 'a refunded guest cannot start: 403 NOT_ENTITLED' ($startB.status -eq 403 -and $startB.code -eq 'NOT_ENTITLED') (Answer $startB)

# ---------- guests C, D: a failed payment, then sold out ----------
$c = NewGuest 'c'
$orderC = Call 'POST' '/api/guest/orders' $c.token @{ examId = $examId }
$failC = SandboxPay $orderC.data.checkoutUrl 'failure'
$afterFail = Call 'GET' "/api/guest/orders/$($orderC.data.id)" $c.token $null
Check 'a failed payment: FAILED, the seat given back' ($afterFail.data.status -eq 'FAILED') (Answer $afterFail)
$orderC2 = Call 'POST' '/api/guest/orders' $c.token @{ examId = $examId }
$payC = SandboxPay $orderC2.data.checkoutUrl 'success'
$d = NewGuest 'd'
$soldOut = Call 'POST' '/api/guest/orders' $d.token @{ examId = $examId }
Check 'the third paid seat of two: 409 SOLD_OUT' ($soldOut.status -eq 409 -and $soldOut.code -eq 'SOLD_OUT') (Answer $soldOut)

# ---------- the administrator ----------
$list = Call 'GET' "/api/admin/orders?examId=$examId&sortBy=createdAt&dir=asc" $admin $null
Check 'the administrator lists the orders of the exam' ($list.status -eq 200 -and $list.data.totalElements -ge 4) (Answer $list)
$byCode = Call 'GET' "/api/admin/orders?code=$($a.code)" $admin $null
Check 'by the 7-digit code' ($byCode.data.content | Where-Object { $_.orderNo -eq $order.data.orderNo }) (Answer $byCode)
$detail = Call 'GET' "/api/admin/orders/$($order.data.id)" $admin $null
Check 'one order with its payment events' ($detail.status -eq 200 -and @($detail.data.events).Count -ge 1) (Answer $detail)
$csvFile = Join-Path $env:TEMP "t-b13b-$stamp.csv"
$csvStatus = & curl.exe -s -o $csvFile -w '%{http_code}' "$GW/api/admin/orders/export?examId=$examId" -H "Authorization: Bearer $admin"
$csvBytes = [IO.File]::ReadAllBytes($csvFile); Remove-Item $csvFile -ErrorAction SilentlyContinue
$csvText = [Text.Encoding]::UTF8.GetString($csvBytes)
Check 'the CSV: BOM, Azerbaijani headers, the participant code' ($csvStatus -eq '200' -and $csvBytes[0] -eq 0xEF -and $csvText -match 'Sifariş nömrəsi' -and $csvText -match "$($a.code)") $csvStatus
$summary = Call 'GET' "/api/admin/paid-exams/$examId/summary" $admin $null
Check 'the figures: 2 paid, 3000 revenue, 1 refunded' ($summary.data.paidCount -eq 2 -and $summary.data.revenueMinor -eq 3000 -and $summary.data.refundedCount -eq 1) (Answer $summary)
$events = Call 'GET' "/api/admin/guests/$($a.id)/events" $admin $null
Check "the guest's events show the order and the payment" ($events.status -eq 200 -and ($events.data.content.action -contains 'GUEST_ORDER_PAID')) (Answer $events)

# ---------- the exam: guest A starts when the window opens; the result; no refund after the start ----------
$wait = [int][Math]::Max(0, ($start - [DateTime]::UtcNow).TotalSeconds + 5)
Write-Output "Waiting $wait s for the exam to start ..."
Start-Sleep -Seconds $wait
$startA = Call 'POST' "/api/guest/exams/$examId/start" $a.token @{}
Check 'guest A starts the paid exam' ($startA.status -eq 200 -and $startA.data.sessionId) (Answer $startA)
$session = $startA.data.sessionId
$startAgain = Call 'POST' "/api/guest/exams/$examId/start" $a.token @{}
Check 'starting again gives the same session' ($startAgain.data.sessionId -eq $session) (Answer $startAgain)
$questions = Call 'GET' "/api/guest/sessions/$session/questions" $a.token $null
$q = @($questions.data)[0]
$right = @($q.options | Where-Object { $_.optionText -eq '4' })[0]
$answer = Call 'POST' "/api/guest/sessions/$session/answer" $a.token @{ questionId = $q.id; selectedOptionIds = @($right.id) }
$submit = Call 'POST' "/api/guest/sessions/$session/submit" $a.token $null
Check 'guest A answers and submits' ($answer.status -eq 200 -and $submit.status -eq 200) "$(Answer $answer) / $(Answer $submit)"
$result = Call 'GET' "/api/guest/results/$session" $a.token $null
Check 'the result of guest A' ($result.status -eq 200) (Answer $result)
$otherResult = Call 'GET' "/api/guest/results/$session" $c.token $null
Check "another guest's result is 404" ($otherResult.status -eq 404) (Answer $otherResult)
$examsPath = Call 'GET' "/api/exams/session/$session/result" $a.token $null
Check 'the accounts'' /api/exams paths stay closed to a guest' ($examsPath.status -eq 401 -or $examsPath.status -eq 403) (Answer $examsPath)
$lateRefund = Call 'POST' "/api/guest/orders/$($order.data.id)/refund-request" $a.token $null
Check 'no refund request after the start: 409 REFUND_NOT_ALLOWED' ($lateRefund.status -eq 409 -and $lateRefund.code -eq 'REFUND_NOT_ALLOWED') (Answer $lateRefund)
$noOverride = Call 'POST' "/api/admin/orders/$($order.data.id)/refund" $admin @{ reason = 'Yoxlama' }
Check 'the administrator without override: 409 ATTEMPT_STARTED' ($noOverride.status -eq 409 -and $noOverride.code -eq 'ATTEMPT_STARTED') (Answer $noOverride)

Note 'test data' "the exam $T and the guests be-b13b-*-$stamp@x.test stay; scripts\cleanup-test-data.ps1 lists them (dry run)"
$failed = @($script:results | Where-Object { -not $_.ok })
Write-Output ''
Write-Output ("SUMMARY: {0} checks, {1} failed" -f $script:results.Count, $failed.Count)
if ($failed.Count -gt 0) { $failed | ForEach-Object { Write-Output "  FAIL  $($_.name)" }; exit 1 }
exit 0
