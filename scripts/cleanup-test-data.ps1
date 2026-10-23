<#
.SYNOPSIS
    Removes the data of automated tests (accounts @x.test, exams and groups named "T-...") and
    everything attached to it. DRY RUN unless -Execute is given.

.DESCRIPTION
    The rules are in the CONFIG section below. What is test data:
      - users whose email ends with @x.test (never an ADMIN, never an address of $ProtectedEmails
        or ADMIN_EMAIL of .env)
      - exams whose title starts with "T-", groups whose name starts with "T-"
    and what hangs on them: sessions (of a test exam, or of a test user on any exam), answers,
    fraud events, media play counts, audiences, memberships, join links and requests, invites
    (of a test group, or to an @x.test address), group audit rows (of a test group, by or about a
    test user, or about a test exam), questions with their options and media links, media rows
    owned by test users or used only by test exams, admin audit rows by or about test users,
    refresh tokens and one-time codes of test users. B13a: guest participants (guest_participants)
    whose email ends with @x.test, with their refresh tokens, profile tokens, codes and audit rows;
    only the guest tables that exist. Nothing else is touched; the dry run lists what is protected.

    Dry run (default): prints per table how many rows would be deleted and how many stay, and the
    protected data. Nothing is changed (it only reads; the id lists live in temporary tables of
    its own session).

    -Execute: asks for the confirmation word (typed by hand; on the live databases a parameter is
    not accepted), takes a backup with scripts\backup.ps1, then deletes in an order that keeps
    every foreign key valid, in batches of -BatchSize rows, each batch its own transaction. A run
    that stops half way can simply be started again: it finds what is left. Users are deleted
    last, so their rows in exam_db are always found by a second run.

    Media FILES in the storage (SeaweedFS) are never deleted: the storage keys of the media rows
    that are deleted are written to a list in the report folder.

.EXAMPLE
    .\scripts\cleanup-test-data.ps1                      # dry run on the live databases
    .\scripts\cleanup-test-data.ps1 -Execute             # asks for the word, backs up, deletes
    .\scripts\cleanup-test-data.ps1 -AuthDb copy_auth -ExamDb copy_db -Execute -ConfirmationWord ...   # a copy
#>
[CmdletBinding()]
param(
    [switch]$Execute,
    # Only for databases other than the live ones (a restored copy); live runs ask on the console
    [string]$ConfirmationWord,
    [string]$Container = 'exam-postgres',
    [string]$DbUser = 'postgres',
    [string]$AuthDb = 'exam_auth',
    [string]$ExamDb = 'exam_db',
    [int]$BatchSize = 2000,
    # A copy needs no backup of the live system; refused on the live databases
    [switch]$SkipBackup,
    [string]$ReportDir = ''
)

# ======================= CONFIG: what is test data =======================
$TestUserEmailLike = '%@x.test'      # SQL LIKE on the lower-case email
$TestExamTitleRegex = '^T-'          # PostgreSQL regular expression on exams.title
$TestGroupNameRegex = '^T-'          # PostgreSQL regular expression on user_groups.name
$ProtectedEmails = @()               # always kept, in addition to ADMIN_EMAIL of .env and every ADMIN
$Word = 'TEST-MELUMATINI-SIL'        # the confirmation word of -Execute
# ==========================================================================

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$live = ($AuthDb -eq 'exam_auth' -or $ExamDb -eq 'exam_db')
$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
if (-not $ReportDir) { $ReportDir = Join-Path ([IO.Path]::GetTempPath()) "cleanup-test-data-$stamp" }
New-Item -ItemType Directory -Force -Path $ReportDir | Out-Null

function Get-Setting([string]$Name) {
    $value = [Environment]::GetEnvironmentVariable($Name)
    if ($value) { return $value }
    $envFile = Join-Path $repoRoot '.env'
    if (Test-Path $envFile) {
        foreach ($line in Get-Content $envFile) { if ($line -match "^\s*$Name\s*=\s*(.+?)\s*$") { return $Matches[1].Trim('"').Trim("'") } }
    }
    return $null
}
function Literal([string]$value) { "'" + $value.Replace("'", "''") + "'" }

# Runs a psql script (stdin) in the database container; returns the output lines; stops on any error
function Invoke-Psql([string]$Database, [string]$Script, [string]$What) {
    $tmp = [IO.Path]::GetTempFileName()
    try {
        [IO.File]::WriteAllText($tmp, $Script, (New-Object Text.UTF8Encoding($false)))
        $out = cmd /c "docker exec -i $Container psql -U $DbUser -d $Database -v ON_ERROR_STOP=1 -At -q < `"$tmp`" 2>&1"
        if ($LASTEXITCODE -ne 0) { throw "$What failed: $($out -join ' | ')" }
        return @($out)
    } finally { Remove-Item $tmp -Force }
}

# Refused before anything is read
if ($Execute -and $live -and $SkipBackup) { throw 'REFUSED: -SkipBackup is not accepted on the live databases' }
if ($Execute -and $live -and $ConfirmationWord) { throw 'REFUSED: on the live databases the confirmation word is typed by hand, not passed' }

$adminEmail = Get-Setting 'ADMIN_EMAIL'
$protected =@($ProtectedEmails + @($adminEmail) | Where-Object { $_ } | ForEach-Object { $_.Trim().ToLowerInvariant() } | Select-Object -Unique)
$protectedList = if ($protected.Count) { ($protected | ForEach-Object { Literal $_ }) -join ', ' } else { "''" }
$userIdsFile = "/tmp/cleanup_test_users_$stamp.csv"

# ---------- The test users (exam_auth), written to a file in the container for exam_db ----------
$testUserWhere = "lower(email) LIKE $(Literal $TestUserEmailLike) AND role <> 'ADMIN' AND lower(email) NOT IN ($protectedList)"
Invoke-Psql $AuthDb "\copy (SELECT id FROM users WHERE $testUserWhere) TO '$userIdsFile'" 'Listing the test users' | Out-Null

# ---------- exam_db: the id sets of this run (temporary tables of the session) ----------
$examSets = @"
CREATE TEMP TABLE tu (id uuid PRIMARY KEY);
\copy tu FROM '$userIdsFile'
CREATE TEMP TABLE te AS SELECT id FROM exams WHERE title ~ $(Literal $TestExamTitleRegex);
CREATE TEMP TABLE tg AS SELECT id FROM user_groups WHERE name ~ $(Literal $TestGroupNameRegex);
CREATE TEMP TABLE tq AS SELECT id FROM questions WHERE exam_id IN (SELECT id FROM te);
CREATE TEMP TABLE ts AS SELECT id, session_id FROM exam_sessions WHERE exam_id IN (SELECT id FROM te) OR user_id IN (SELECT id FROM tu);
-- Media of test users or of test exams, unless a question or option of another exam still uses it
CREATE TEMP TABLE tm AS
  SELECT m.id, m.storage_key FROM media_assets m
  WHERE (m.owner_id IN (SELECT id FROM tu)
         OR m.id IN (SELECT media_id FROM question_media WHERE question_id IN (SELECT id FROM tq))
         OR m.id IN (SELECT om.media_id FROM option_media om JOIN question_options o ON o.id = om.option_id WHERE o.question_id IN (SELECT id FROM tq)))
    AND NOT EXISTS (SELECT 1 FROM question_media qm WHERE qm.media_id = m.id AND qm.question_id NOT IN (SELECT id FROM tq))
    AND NOT EXISTS (SELECT 1 FROM option_media om JOIN question_options o ON o.id = om.option_id WHERE om.media_id = m.id AND o.question_id NOT IN (SELECT id FROM tq));
"@

# Table, condition: the order is the order of deletion (children before parents)
$examTables = @(
    # media_play_counts.session_id is the text session_id of exam_sessions, not its numeric id
    @('media_play_counts', 'session_id IN (SELECT session_id FROM ts) OR media_id IN (SELECT id FROM tm)'),
    @('user_answers', 'session_id IN (SELECT id FROM ts)'),
    @('session_fraud_events', 'session_id IN (SELECT id FROM ts)'),
    @('exam_sessions', 'id IN (SELECT id FROM ts)'),
    @('exam_audience', 'exam_id IN (SELECT id FROM te) OR group_id IN (SELECT id FROM tg)'),
    @('user_group_members', 'group_id IN (SELECT id FROM tg) OR user_id IN (SELECT id FROM tu)'),
    @('group_join_requests', 'group_id IN (SELECT id FROM tg) OR user_id IN (SELECT id FROM tu)'),
    @('group_join_links', 'group_id IN (SELECT id FROM tg)'),
    @('pending_group_invites', "group_id IN (SELECT id FROM tg) OR lower(email_normalized) LIKE $(Literal $TestUserEmailLike)"),
    @('group_audit_log', "group_id IN (SELECT id FROM tg) OR actor_id IN (SELECT id FROM tu) OR target_user_id IN (SELECT id FROM tu) OR (substring(details FROM 'examId=([0-9]+)'))::bigint IN (SELECT id FROM te)"),
    @('option_media', 'media_id IN (SELECT id FROM tm) OR option_id IN (SELECT id FROM question_options WHERE question_id IN (SELECT id FROM tq))'),
    @('question_media', 'media_id IN (SELECT id FROM tm) OR question_id IN (SELECT id FROM tq)'),
    @('question_options', 'question_id IN (SELECT id FROM tq)'),
    @('questions', 'id IN (SELECT id FROM tq)'),
    @('media_assets', 'id IN (SELECT id FROM tm)'),
    @('user_groups', 'id IN (SELECT id FROM tg)'),
    @('exams', 'id IN (SELECT id FROM te)')
)
# B13: the own limits of test teachers; the table exists from exam V19 on (an older database has none)
if ((Invoke-Psql $ExamDb "SELECT to_regclass('public.instructor_limits') IS NOT NULL;" 'Looking for instructor_limits') -contains 't') {
    $examTables = @(, @('instructor_limits', 'instructor_id IN (SELECT id FROM tu)')) + $examTables
}
$authTables = @(
    @('admin_audit_log', 'admin_user_id IN (SELECT id FROM tu) OR target_user_id IN (SELECT id FROM tu)'),
    @('refresh_tokens', 'user_id IN (SELECT id FROM tu)'),
    @('email_otp', 'user_id IN (SELECT id FROM tu)'),
    @('email_verification_tokens', 'user_id IN (SELECT id FROM tu)'),
    @('password_reset_tokens', 'user_id IN (SELECT id FROM tu)'),
    @('users', 'id IN (SELECT id FROM tu)')
)
$authSets = "CREATE TEMP TABLE tu AS SELECT id FROM users WHERE $testUserWhere;"

# B13a: guest participants (auth V9-V12) with an @x.test email, and what hangs on them; only the tables that exist
# (an older database has none). A guest is never an account, so ADMIN and the protected addresses do not apply, but a
# protected address is kept here too. Deleted before users, children before the guests.
$guestTablesPresent = @(Invoke-Psql $AuthDb "SELECT string_agg(t, ',') FROM unnest(ARRAY['guest_participants','guest_refresh_tokens','guest_email_otp','guest_profile_tokens','guest_audit_log']) t WHERE to_regclass('public.' || t) IS NOT NULL;" 'Looking for the guest tables')
$guestTables = @("$($guestTablesPresent -join '')".Split(',') | Where-Object { $_ })
if ($guestTables -contains 'guest_participants') {
    $testGuestWhere = "lower(email) LIKE $(Literal $TestUserEmailLike) AND lower(email) NOT IN ($protectedList)"
    $authSets += "`nCREATE TEMP TABLE tgu AS SELECT id FROM guest_participants WHERE $testGuestWhere;"
    $guestDeletes = @()
    if ($guestTables -contains 'guest_audit_log') {
        # Rows before the guest existed (a code asked for) carry only the masked email, which keeps the domain
        $guestDeletes += , @('guest_audit_log', "target_guest_id IN (SELECT id FROM tgu) OR actor_id IN (SELECT id FROM tgu) OR lower(email_masked) LIKE $(Literal $TestUserEmailLike)")
    }
    if ($guestTables -contains 'guest_refresh_tokens') { $guestDeletes += , @('guest_refresh_tokens', 'guest_id IN (SELECT id FROM tgu)') }
    if ($guestTables -contains 'guest_profile_tokens') {
        $guestDeletes += , @('guest_profile_tokens', "guest_id IN (SELECT id FROM tgu) OR (lower(email) LIKE $(Literal $TestUserEmailLike) AND lower(email) NOT IN ($protectedList))")
    }
    if ($guestTables -contains 'guest_email_otp') {
        $guestDeletes += , @('guest_email_otp', "lower(email) LIKE $(Literal $TestUserEmailLike) AND lower(email) NOT IN ($protectedList)")
    }
    $guestDeletes += , @('guest_participants', 'id IN (SELECT id FROM tgu)')
    # Before the accounts' rows: users stays last
    $authTables = $guestDeletes + $authTables
}

function Count-Script($tables) {
    ($tables | ForEach-Object { "SELECT '$($_[0])|' || (SELECT count(*) FROM $($_[0]) WHERE $($_[1])) || '|' || (SELECT count(*) FROM $($_[0]));" }) -join "`n"
}

function Show-Counts([string]$db, [string[]]$lines) {
    $rows = foreach ($line in $lines | Where-Object { $_ -match '^\w+\|\d+\|\d+$' }) {
        $p = $line.Split('|')
        [pscustomobject]@{ Database = $db; Table = $p[0]; Delete = [long]$p[1]; Keep = [long]$p[2] - [long]$p[1]; Total = [long]$p[2] }
    }
    return $rows
}

# ---------- Counts (dry run, and before an execution) ----------
$examLines = Invoke-Psql $ExamDb ($examSets + "`n" + (Count-Script $examTables) + @"

SELECT 'protected:exams-not-test|' || count(*) FROM exams WHERE id NOT IN (SELECT id FROM te);
SELECT 'protected:groups-not-test|' || count(*) FROM user_groups WHERE id NOT IN (SELECT id FROM tg);
SELECT 'protected:sessions-of-test-users-on-kept-exams|' || count(*) FROM exam_sessions WHERE user_id IN (SELECT id FROM tu) AND exam_id NOT IN (SELECT id FROM te);
SELECT 'protected:sessions-of-real-users-on-test-exams|' || count(*) FROM exam_sessions WHERE exam_id IN (SELECT id FROM te) AND user_id NOT IN (SELECT id FROM tu);
SELECT 'protected:media-shared-with-kept-exams|' || count(*) FROM media_assets m WHERE m.id NOT IN (SELECT id FROM tm) AND (m.id IN (SELECT media_id FROM question_media WHERE question_id IN (SELECT id FROM tq)) OR m.owner_id IN (SELECT id FROM tu));
SELECT 'kept-exam|' || id || '|' || title || '|' || status FROM exams WHERE id NOT IN (SELECT id FROM te) ORDER BY id;
SELECT 'kept-group|' || id || '|' || name FROM user_groups WHERE id NOT IN (SELECT id FROM tg) ORDER BY id;
SELECT 'media-file|' || storage_key FROM tm ORDER BY storage_key;
"@) 'Counting in exam_db'
$authLines = Invoke-Psql $AuthDb ($authSets + "`n" + (Count-Script $authTables) + @"

SELECT 'protected:users-not-test|' || count(*) FROM users WHERE id NOT IN (SELECT id FROM tu);
SELECT 'protected:admins|' || count(*) FROM users WHERE role = 'ADMIN';
SELECT 'protected:xtest-but-protected|' || count(*) FROM users WHERE lower(email) LIKE $(Literal $TestUserEmailLike) AND id NOT IN (SELECT id FROM tu);
SELECT 'kept-user-role|' || role || '|' || count(*) FROM users WHERE id NOT IN (SELECT id FROM tu) GROUP BY role ORDER BY role;
$(if ($guestTables -contains 'guest_participants') { "SELECT 'protected:guests-not-test|' || count(*) FROM guest_participants WHERE id NOT IN (SELECT id FROM tgu);" })
"@) 'Counting in exam_auth'

$counts = @(Show-Counts $ExamDb $examLines) + @(Show-Counts $AuthDb $authLines)
$mode = if ($Execute) { 'EXECUTE (counts before deleting)' } else { 'DRY RUN (nothing is deleted)' }
Write-Output "cleanup-test-data: $mode on $AuthDb + $ExamDb"
Write-Output "Rules: users email LIKE '$TestUserEmailLike' (not ADMIN, not protected), exams title ~ '$TestExamTitleRegex', groups name ~ '$TestGroupNameRegex'"
$counts | Format-Table Database, Table, Delete, Keep, Total -AutoSize | Out-String -Width 160 | Write-Output

$all = $examLines + $authLines
Write-Output 'Protected (never deleted):'
foreach ($line in $all | Where-Object { $_ -like 'protected:*' }) {
    $p = $line.Substring(10).Split('|'); Write-Output ("  {0,-48} {1}" -f $p[0], $p[1])
}
Write-Output '  Users kept by role:'
foreach ($line in $all | Where-Object { $_ -like 'kept-user-role|*' }) { $p = $line.Split('|'); Write-Output ("    {0,-12} {1}" -f $p[1], $p[2]) }
$keptExams = @($all | Where-Object { $_ -like 'kept-exam|*' })
Write-Output "  Exams kept: $($keptExams.Count)"
$keptExams | Select-Object -First 25 | ForEach-Object { $p = $_.Split('|'); Write-Output ("    #{0,-5} {1} ({2})" -f $p[1], $p[2], $p[3]) }
if ($keptExams.Count -gt 25) { Write-Output "    ... and $($keptExams.Count - 25) more (full list in the report folder)" }
$keptGroups = @($all | Where-Object { $_ -like 'kept-group|*' })
Write-Output "  Groups kept: $($keptGroups.Count)"
$keptGroups | Select-Object -First 25 | ForEach-Object { $p = $_.Split('|'); Write-Output ("    #{0,-5} {1}" -f $p[1], $p[2]) }

$mediaFiles = @($all | Where-Object { $_ -like 'media-file|*' } | ForEach-Object { $_.Substring(11) })
$keptExams | Set-Content (Join-Path $ReportDir 'kept-exams.txt') -Encoding UTF8
$keptGroups | Set-Content (Join-Path $ReportDir 'kept-groups.txt') -Encoding UTF8
$mediaFiles | Set-Content (Join-Path $ReportDir 'media-files-left-in-storage.txt') -Encoding UTF8
$counts | Export-Csv (Join-Path $ReportDir 'counts.csv') -NoTypeInformation -Encoding UTF8
Write-Output "Media files whose rows go (files stay in the storage, not deleted): $($mediaFiles.Count); list: $(Join-Path $ReportDir 'media-files-left-in-storage.txt')"
Write-Output "Report folder: $ReportDir"

if (-not $Execute) {
    Invoke-Psql $AuthDb "\! rm -f $userIdsFile" 'Removing the id list' | Out-Null
    Write-Output 'Dry run: nothing was deleted. Run again with -Execute to delete.'
    exit 0
}

# ---------- Execute ----------
if ($live) {
    if ($SkipBackup) { throw 'REFUSED: -SkipBackup is not accepted on the live databases' }
    if ($ConfirmationWord) { throw 'REFUSED: on the live databases the confirmation word is typed by hand, not passed' }
    $typed = Read-Host "Type $Word to delete the rows above from $AuthDb and $ExamDb"
} else {
    $typed = if ($ConfirmationWord) { $ConfirmationWord } else { Read-Host "Type $Word to delete the rows above from $AuthDb and $ExamDb" }
}
if ($typed -cne $Word) { Write-Output 'Not confirmed: nothing was deleted.'; exit 1 }

if (-not $SkipBackup) {
    Write-Output 'Backup first (scripts\backup.ps1) ...'
    & (Join-Path $PSScriptRoot 'backup.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'The backup failed: nothing was deleted' }
}

# One DO block per table: batches by ctid, each committed on its own
function Delete-Script($tables) {
    ($tables | ForEach-Object {
        $table = $_[0]; $where = $_[1]
@"
DO `$`$
DECLARE n bigint; total bigint := 0;
BEGIN
  LOOP
    DELETE FROM $table WHERE ctid IN (SELECT ctid FROM $table WHERE $where LIMIT $BatchSize);
    GET DIAGNOSTICS n = ROW_COUNT;
    total := total + n;
    COMMIT;
    EXIT WHEN n = 0;
  END LOOP;
  RAISE NOTICE 'deleted|$table|%', total;
END `$`$;
"@
    }) -join "`n"
}

$watch = [Diagnostics.Stopwatch]::StartNew()
$examOut = Invoke-Psql $ExamDb ($examSets + "`n" + "SET client_min_messages = notice;`n" + (Delete-Script $examTables)) 'Deleting in exam_db'
$authOut = Invoke-Psql $AuthDb ($authSets + "`n" + "SET client_min_messages = notice;`n" + (Delete-Script $authTables)) 'Deleting in exam_auth'
$watch.Stop()
Invoke-Psql $AuthDb "\! rm -f $userIdsFile" 'Removing the id list' | Out-Null
Write-Output "Deleted in $([Math]::Round($watch.Elapsed.TotalSeconds)) s:"
foreach ($line in ($examOut + $authOut) | Where-Object { $_ -match 'deleted\|' }) {
    $p = ($line -replace '^.*deleted\|', '').Split('|'); Write-Output ("  {0,-26} {1}" -f $p[0], $p[1])
}
Write-Output 'Run the dry run again to see what is left (it should be 0 everywhere).'
exit 0
