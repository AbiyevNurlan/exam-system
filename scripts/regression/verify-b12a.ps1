# Live verification of feat/b12a-exam-structure-history (B12a): exams made of blocks (create with blocks and the
# older flat questions, validation, totalMinutes), updates in place (question and option ids kept, a question moved
# to another block, removal only without sessions: B9 EXAM_HAS_SESSIONS kept), the history of every change
# (EXAM_CREATED, answer key and points as sensitive rows of one save, META_CHANGED of B10 PATCH /meta, PUBLISHED,
# CLONED_FROM, DELETE_ATTEMPTED; the database refuses UPDATE and DELETE of a history row), the published version
# with its SHA-256, the readiness check and the publish gate (EXAM_NOT_READY, FEATURE_NOT_AVAILABLE), cloning,
# block results hidden from the participant but shown to the administrator, and who may read the history.
# Development stack only; never against the public mode: it refuses to run while the edge of the public mode runs,
# because both modes share one database. Creates T-b12a-<stamp>-* exams and be-b12a-<stamp>-*@x.test accounts
# (registered, verified by SQL, no mail needed); the instructor account is STUDENT again in the finally block.
# Admin credentials are read from F:\exam-system\.env into variables only; nothing secret is printed.
$ErrorActionPreference = 'Continue'
[Threading.Thread]::CurrentThread.CurrentCulture = [Globalization.CultureInfo]::InvariantCulture
Set-Location F:\exam-system
$GW = 'http://127.0.0.1:8080'
. "$PSScriptRoot\_common.ps1"
$stamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString('x')
$Q = "be-b12a-$stamp"
$T = "T-b12a-$stamp"
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
        # Always UTF-8 from the raw bytes: without a charset in the content type PowerShell 5.1 decodes the body
        # as ISO-8859-1, and Azerbaijani letters ('Əsas blok', '(nüsxə)') would never match
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
function AuthCall($path, $body) {
    for ($try = 0; $try -lt 10; $try++) {
        $r = Api 'POST' $path $null $body
        if ($r.status -ne 429) { return $r }
        Start-Sleep -Seconds 6
    }
    return $r
}
function Login($email, $pw) { (AuthCall '/auth/login' @{ email = $email; password = $pw }) }
# A registered account, verified by SQL (no mail server needed)
function Account($name) {
    $email = "$Q-$name@x.test"
    $r = AuthCall '/auth/register' @{ email = $email; password = $password; firstName = "B12a$name"; lastName = 'Testov' }
    if ($r.status -ne 201) { throw "register $name answered $($r.status)" }
    Sql 'exam_auth' "update users set email_verified=true where email='$email'" | Out-Null
    return @{ email = $email }
}
function Utc([DateTime]$t) { $t.ToString('yyyy-MM-ddTHH:mm:ss') }
function Mc($text, [int]$points = 1) {
    @{ questionText = $text; questionType = 'MULTIPLE_CHOICE'; points = $points; orderIndex = 0; allowMultipleAnswers = $false
       options = @(@{ optionText = 'A'; isCorrect = $true; orderIndex = 0 }, @{ optionText = 'B'; isCorrect = $false; orderIndex = 1 }) }
}
function Block($title, $questions, $extra = @{}) {
    $b = @{ title = $title; instructions = "$title instructions"; freeNavigation = $true; shuffleQuestions = $false
            showResultToParticipant = $true; breakAfterMinutes = 0; questions = $questions }
    foreach ($k in $extra.Keys) { $b[$k] = $extra[$k] }
    return $b
}
function ExamBody($title, $blocks, $extra = @{}) {
    $now = [DateTime]::UtcNow
    $b = @{ title = "$T $title"; description = 'B12a verification'; durationMinutes = 30
            startTime = (Utc $now.AddMinutes(5)); endTime = (Utc $now.AddDays(1))
            shuffleQuestions = $false; shuffleOptions = $false; showResultsImmediately = $true; maxAttempts = 3; passingScore = 50
            accessType = 'PUBLIC'; blocks = $blocks }
    foreach ($k in $extra.Keys) { $b[$k] = $extra[$k] }
    return $b
}
function ExamHistory($examId, $query = '') { Api 'GET' "/api/exams/$examId/history?size=100&$query" $adm.token $null }
function Actions($r) { @($r.data.content | ForEach-Object { $_.action }) }

# ---------- setup ----------
$adminEmail = $null; $adminPassword = $null
foreach ($line in Get-Content F:\exam-system\.env) {
    if ($line -match '^ADMIN_EMAIL=(.*)$') { $adminEmail = $Matches[1].Trim() }
    if ($line -match '^ADMIN_PASSWORD=(.*)$') { $adminPassword = $Matches[1].Trim() }
}
$mainLogin = Login $adminEmail $adminPassword
if ($mainLogin.status -ne 200) { throw "admin login answered $($mainLogin.status)" }
$adm = @{ id = $mainLogin.data.user.id; token = $mainLogin.data.accessToken }
# The login answer has no participant code; GET /auth/me has it (B10)
$adm.code = (Api 'GET' '/auth/me' $adm.token $null).data.participantCode
$stu = Account 'stu'
$ins = Account 'ins'
$stu.token = (Login $stu.email $password).data.accessToken
Write-Output "run $stamp; exams $T *, accounts $Q-*@x.test"

# Every role given below is taken back in the finally block before the summary
try {
Sql 'exam_auth' "update users set role='INSTRUCTOR' where email='$($ins.email)'" | Out-Null
$ins.token = (Login $ins.email $password).data.accessToken

# ---------- 1. blocks: create, the older flat format, validation ----------
$created = Api 'POST' '/api/exams' $adm.token (ExamBody 'blocks' @(
    (Block 'Riyaziyyat' @((Mc 'q1'), (Mc 'q2'), (Mc 'q3'))),
    (Block 'Məntiq' @((Mc 'q4'), (Mc 'q5')) @{ showResultToParticipant = $false })) @{ showBlockBreakdown = $true })
$exam = $created.data
Check 'create with two blocks: 201, blocks in order with their questions' ($created.status -eq 201 -and @($exam.blocks).Count -eq 2 -and
    @($exam.blocks)[0].title -eq 'Riyaziyyat' -and @($exam.blocks)[0].questionCount -eq 3 -and @($exam.blocks)[1].questionCount -eq 2) (Answer $created)
Check 'EXAM_TIMER: totalMinutes = durationMinutes' ($exam.timingMode -eq 'EXAM_TIMER' -and $exam.totalMinutes -eq 30) "$($exam.timingMode) $($exam.totalMinutes)"
$flat = Api 'POST' '/api/exams' $adm.token @{ title = "$T flat"; description = 'older request'; durationMinutes = 20
    startTime = (Utc ([DateTime]::UtcNow.AddMinutes(5))); endTime = (Utc ([DateTime]::UtcNow.AddDays(1)))
    showResultsImmediately = $true; maxAttempts = 1; passingScore = 50; questions = @((Mc 'flat 1'), (Mc 'flat 2')) }
Check 'older request without blocks: one block "Əsas blok" with every question' ($flat.status -eq 201 -and @($flat.data.blocks).Count -eq 1 -and
    @($flat.data.blocks)[0].title -eq 'Əsas blok' -and @($flat.data.blocks)[0].questionCount -eq 2) (Answer $flat)

$noLimit = Api 'POST' '/api/exams' $adm.token (ExamBody 'no limit' @((Block 'A' @((Mc 'x')) @{ timeLimitMinutes = 10 }), (Block 'B' @((Mc 'y')))) @{ timingMode = 'BLOCK_TIMERS' })
Check 'BLOCK_TIMERS with a block without a time limit: 400' ($noLimit.status -eq 400) (Answer $noLimit)
$many = Api 'POST' '/api/exams' $adm.token (ExamBody 'many' @(1..21 | ForEach-Object { Block "B$_" @((Mc "q$_")) }))
Check '21 blocks: 400' ($many.status -eq 400) (Answer $many)
$now = [DateTime]::UtcNow
$tooLong = Api 'POST' '/api/exams' $adm.token (ExamBody 'too long' @((Block 'A' @((Mc 'x')) @{ timeLimitMinutes = 50; breakAfterMinutes = 10 }), (Block 'B' @((Mc 'y')) @{ timeLimitMinutes = 50 })) @{
    timingMode = 'BLOCK_TIMERS'; startTime = (Utc $now.AddMinutes(5)); endTime = (Utc $now.AddMinutes(65)) })
Check 'block times + breaks longer than the window: 400 EXAM_DURATION_EXCEEDS_WINDOW' ($tooLong.status -eq 400 -and $tooLong.code -eq 'EXAM_DURATION_EXCEEDS_WINDOW') (Answer $tooLong)

# ---------- 2. update in place ----------
$full = (Api 'GET' "/api/exams/$($exam.id)" $adm.token $null).data
$questions = @($full.questions)
$ids = @($questions | ForEach-Object { [long]$_.id })
$optionIds = @($questions[1].options | ForEach-Object { [long]$_.id })
$blocks = @($full.blocks)
function QuestionBody($q, $changes = @{}) {
    $b = @{ id = $q.id; questionText = $q.questionText; questionType = $q.questionType; points = $q.points; orderIndex = $q.orderIndex
            allowMultipleAnswers = $false; options = @($q.options | ForEach-Object { @{ id = $_.id; optionText = $_.optionText; isCorrect = [bool]$_.isCorrect; orderIndex = $_.orderIndex } }) }
    foreach ($k in $changes.Keys) { $b[$k] = $changes[$k] }
    return $b
}
$byBlock = @{}
foreach ($q in $questions) { if (-not $byBlock[[string]$q.blockId]) { $byBlock[[string]$q.blockId] = @() }; $byBlock[[string]$q.blockId] += $q }
$first = @($byBlock[[string]$blocks[0].id]); $second = @($byBlock[[string]$blocks[1].id])
# q1: 2 points; q2: the right answer B instead of A; q3 moves to the second block; a new question in the first block.
# Points and answer key on different questions: one question with several kinds of change is one QUESTION_UPDATED
# row naming them all (ExamDiff), only a single kind gets its own action (POINTS_CHANGED, CORRECT_ANSWER_CHANGED)
$q1 = QuestionBody $first[0] @{ points = 2 }
$q2 = QuestionBody $first[1] @{ options = @(
    @{ id = $first[1].options[0].id; optionText = 'A'; isCorrect = $false; orderIndex = 0 },
    @{ id = $first[1].options[1].id; optionText = 'B'; isCorrect = $true; orderIndex = 1 }) }
$update = ExamBody 'blocks' @(
    @{ id = $blocks[0].id; title = 'Riyaziyyat'; freeNavigation = $true; shuffleQuestions = $false; showResultToParticipant = $true; breakAfterMinutes = 0
       questions = @($q1, $q2, (Mc 'q6 new')) },
    @{ id = $blocks[1].id; title = 'Məntiq'; freeNavigation = $true; shuffleQuestions = $false; showResultToParticipant = $false; breakAfterMinutes = 0
       questions = @(@($second | ForEach-Object { QuestionBody $_ }) + @(QuestionBody $first[2])) }) @{ id = $exam.id; showBlockBreakdown = $true }
$put = Api 'PUT' "/api/exams/$($exam.id)" $adm.token $update
$after = (Api 'GET' "/api/exams/$($exam.id)" $adm.token $null).data
$afterIds = @($after.questions | ForEach-Object { [long]$_.id })
Check 'PUT: 200, the five questions keep their ids' ($put.status -eq 200 -and @($ids | Where-Object { $afterIds -contains $_ }).Count -eq 5 -and $afterIds.Count -eq 6) (Answer $put)
$q2After = @($after.questions | Where-Object { [long]$_.id -eq [long]$first[1].id })[0]
Check 'the options of the edited question keep their ids' ((@($q2After.options | ForEach-Object { [long]$_.id }) -join ',') -eq ($optionIds -join ','))
$moved = @($after.questions | Where-Object { [long]$_.id -eq [long]$first[2].id })[0]
Check 'q3 moved to the second block, same id' ([long]$moved.blockId -eq [long]$blocks[1].id)

# ---------- 3. history ----------
$h = ExamHistory $exam.id
$acts = Actions $h
Check 'history: EXAM_CREATED, CORRECT_ANSWER_CHANGED, POINTS_CHANGED, QUESTION_ADDED, QUESTION_MOVED' ($h.status -eq 200 -and
    ($acts -contains 'EXAM_CREATED') -and ($acts -contains 'CORRECT_ANSWER_CHANGED') -and ($acts -contains 'POINTS_CHANGED') -and
    ($acts -contains 'QUESTION_ADDED') -and ($acts -contains 'QUESTION_MOVED')) ($acts -join ',')
$saveRows = @($h.data.content | Where-Object { $_.action -ne 'EXAM_CREATED' })
Check 'the rows of one save share one saveId' (@($saveRows | ForEach-Object { $_.saveId } | Select-Object -Unique).Count -eq 1) "$($saveRows.Count) rows"
$key = @($h.data.content | Where-Object { $_.action -eq 'CORRECT_ANSWER_CHANGED' })[0]
Check 'answer key row: sensitive, actor name and 7-digit code, question number 2' ([bool]$key.sensitive -and $key.actor.code -eq $adm.code -and
    $key.summaryParams.questionNumber -eq 2) "$($key.summaryKey) $($key.actor.code) q$($key.summaryParams.questionNumber)"
$detail = Api 'GET' "/api/exams/$($exam.id)/history/$($key.id)" $adm.token $null
Check 'history/{id}: before and after' ($detail.status -eq 200 -and $detail.raw -match 'correct') (Answer $detail)
Check 'sensitiveOnly filter' (@((ExamHistory $exam.id 'sensitiveOnly=true').data.content | Where-Object { -not $_.sensitive }).Count -eq 0)
$u = Sql 'exam_db' "update exam_revisions set reason='x' where id=$($key.id)"
$d = Sql 'exam_db' "delete from exam_revisions where id=$($key.id)"
Check 'the database refuses UPDATE and DELETE of a history row' (("$u" -match 'append-only') -and ("$d" -match 'append-only')) "$u / $d"
$meta = Api 'PATCH' "/api/exams/$($exam.id)/meta" $adm.token @{ title = "$T blocks renamed" }
Check 'B10 PATCH /meta: 200 and a META_CHANGED row' ($meta.status -eq 200 -and ((Actions (ExamHistory $exam.id)) -contains 'META_CHANGED')) (Answer $meta)

# ---------- 4. readiness and the publish gate ----------
$empty = Api 'POST' '/api/exams' $adm.token (ExamBody 'empty block' @((Block 'A' @((Mc 'x'))), (Block 'B' @())))
$ready = Api 'GET' "/api/exams/$($empty.data.id)/readiness" $adm.token $null
Check 'readiness of an exam with an empty block: blocking EMPTY_BLOCK' ($ready.status -eq 200 -and (@($ready.data.blocking | ForEach-Object { $_.code }) -contains 'EMPTY_BLOCK')) (@($ready.data.blocking | ForEach-Object { $_.code }) -join ',')
$pub = Api 'POST' "/api/exams/$($empty.data.id)/publish" $adm.token @{}
Check 'publishing it: 409 EXAM_NOT_READY' ($pub.status -eq 409 -and $pub.code -eq 'EXAM_NOT_READY') (Answer $pub)
$timers = Api 'POST' '/api/exams' $adm.token (ExamBody 'timers' @((Block 'A' @((Mc 'x')) @{ timeLimitMinutes = 10 }), (Block 'B' @((Mc 'y')) @{ timeLimitMinutes = 10 })) @{ timingMode = 'BLOCK_TIMERS' })
$pub = Api 'POST' "/api/exams/$($timers.data.id)/publish" $adm.token @{}
Check 'BLOCK_TIMERS before B12b: 409 FEATURE_NOT_AVAILABLE' ($pub.status -eq 409 -and $pub.code -eq 'FEATURE_NOT_AVAILABLE') (Answer $pub)
$pub = Api 'POST' "/api/exams/$($exam.id)/publish" $adm.token @{}
Check 'a ready exam is published' ($pub.status -eq 200) (Answer $pub)
$versions = Api 'GET' "/api/exams/$($exam.id)/versions" $adm.token $null
$v1 = Api 'GET' "/api/exams/$($exam.id)/versions/1" $adm.token $null
Check 'version 1 with a SHA-256 that matches its snapshot' ($versions.status -eq 200 -and @($versions.data).Count -eq 1 -and
    "$($v1.data.sha256)" -match '^[0-9a-f]{64}$' -and $v1.data.hashValid -eq $true) "$($v1.data.sha256)"
Check 'PUBLISHED in the history' ((Actions (ExamHistory $exam.id)) -contains 'PUBLISHED')

# ---------- 5. block results: hidden from the participant, shown to the administrator ----------
# POST /api/exams refuses a start in the past (@Future), so the window is opened now, as New-ActiveExam does
$opened = Api 'POST' "/api/exams/$($exam.id)/reschedule" $adm.token @{ startTime = (Utc ([DateTime]::UtcNow.AddMinutes(-1))); endTime = (Utc ([DateTime]::UtcNow.AddDays(1))) }
Check 'the window is opened with reschedule' ($opened.status -eq 200) (Answer $opened)
$start =Api 'POST' "/api/exams/$($exam.id)/start" $stu.token @{}
$sid = $start.data.sessionId
foreach ($q in @($start.data.questions)) {
    $right = Sql 'exam_db' "select id from question_options where question_id=$($q.id) and is_correct limit 1"
    $null = Api 'POST' "/api/exams/session/$sid/answer" $stu.token @{ questionId = $q.id; selectedOptionIds = @([long]$right) }
}
$submit = Api 'POST' "/api/exams/session/$sid/submit" $stu.token @{}
$result = Api 'GET' "/api/exams/session/$sid/result" $stu.token $null
Check 'participant result: only the block that is shown' ($result.status -eq 200 -and @($result.data.blocks).Count -eq 1 -and
    @($result.data.blocks)[0].title -eq 'Riyaziyyat' -and $result.raw -notmatch 'Məntiq') (Answer $result)
$review = Api 'GET' "/api/exams/session/$sid/review" $adm.token $null
Check 'administrator review: both blocks' ($review.status -eq 200 -and @($review.data.blocks).Count -eq 2) (Answer $review)

# ---------- 6. B9 kept, delete attempt logged, clone ----------
$keep = ExamBody 'blocks' @(@{ id = $blocks[0].id; title = 'Riyaziyyat'; freeNavigation = $true; questions = @((QuestionBody $first[0])) }) @{ id = $exam.id }
$removal = Api 'PUT' "/api/exams/$($exam.id)" $adm.token $keep
Check 'removing questions of an exam with sessions: 409 EXAM_HAS_SESSIONS' ($removal.status -eq 409 -and $removal.code -eq 'EXAM_HAS_SESSIONS') (Answer $removal)
$del = Api 'DELETE' "/api/exams/$($exam.id)" $adm.token $null
Check 'delete of an exam with sessions refused, DELETE_ATTEMPTED written' ($del.status -ge 400 -and ((Actions (ExamHistory $exam.id)) -contains 'DELETE_ATTEMPTED')) (Answer $del)
$clone = Api 'POST' "/api/exams/$($exam.id)/clone" $adm.token @{}
Check 'clone: a DRAFT "(nüsxə)" with both blocks and new ids' ($clone.status -in 200, 201 -and $clone.data.status -eq 'DRAFT' -and
    "$($clone.data.title)" -like '*(nüsxə)' -and @($clone.data.blocks).Count -eq 2 -and [long]$clone.data.id -ne [long]$exam.id) (Answer $clone)
Check 'clone: CLONED_FROM in its history' ((Actions (ExamHistory $clone.data.id)) -contains 'CLONED_FROM')
Check 'clone: no sessions' ((Sql 'exam_db' "select count(*) from exam_sessions where exam_id=$($clone.data.id)") -eq '0')

# ---------- 7. who may read the history ----------
Check 'INSTRUCTOR, the exam of somebody else: 404' ((Api 'GET' "/api/exams/$($exam.id)/history" $ins.token $null).status -eq 404)
Check 'STUDENT: 403' ((Api 'GET' "/api/exams/$($exam.id)/history" $stu.token $null).status -eq 403)
$own = Api 'POST' '/api/exams' $ins.token (ExamBody 'own' @((Block 'A' @((Mc 'x')))))
Check 'INSTRUCTOR, own exam: history 200' ((Api 'GET' "/api/exams/$($own.data.id)/history" $ins.token $null).status -eq 200) (Answer $own)
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
Write-Output "stamp=$stamp; exams '$T *', accounts $Q-*@x.test"
Write-Output ''
Write-Output 'Test data (dry run, nothing is deleted):'
& powershell -NoProfile -ExecutionPolicy Bypass -File F:\exam-system\scripts\cleanup-test-data.ps1 | Select-Object -First 30
