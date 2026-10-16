<#
.SYNOPSIS
    Checks the participant codes of every account (read only) and reports PASS or FAIL.

.DESCRIPTION
    B10 gives every account a 7-digit participant code; B11 (auth-service V5) makes it impossible to change
    one and to give one twice. This script only reads (SELECT in one read-only transaction through
    docker exec in the database container) and checks:
      - every account has a code (no NULL);
      - every code has 7 digits (1000000-9999999);
      - no code is used twice;
      - the sequence is ahead of the codes in use and how many codes are left;
      - the two triggers of V5 are in place (when V5 is applied).
    Nothing is written, no secret is read or printed. Exit code 0 = PASS, 1 = FAIL, 2 = could not check.

.EXAMPLE
    .\scripts\check-participant-codes.ps1
    .\scripts\check-participant-codes.ps1 -Database exam_auth_copy
#>
[CmdletBinding()]
param(
    [string]$Container = 'exam-postgres',
    [string]$Database = 'exam_auth',
    [string]$DbUser = 'postgres'
)

$ErrorActionPreference = 'Continue'

$sql = @"
BEGIN TRANSACTION READ ONLY;
SELECT 'accounts', count(*) FROM users;
SELECT 'null_codes', count(*) FROM users WHERE participant_code IS NULL;
SELECT 'out_of_range', count(*) FROM users WHERE participant_code NOT BETWEEN 1000000 AND 9999999;
SELECT 'duplicates', count(*) FROM (SELECT participant_code FROM users WHERE participant_code IS NOT NULL GROUP BY participant_code HAVING count(*) > 1) d;
SELECT 'distinct_codes', count(DISTINCT participant_code) FROM users;
SELECT 'sequence_used', CASE WHEN is_called THEN last_value + 1 ELSE last_value END FROM participant_code_seq;
SELECT 'codes_left', 9000000 - CASE WHEN is_called THEN last_value + 1 ELSE last_value END FROM participant_code_seq;
SELECT 'v5_triggers', count(*) FROM pg_trigger WHERE tgrelid = 'users'::regclass AND tgname IN ('trg_users_participant_code_assign', 'trg_users_participant_code_immutable');
SELECT 'v5_applied', count(*) FROM flyway_schema_history WHERE version = '5' AND success;
COMMIT;
"@

$tmp = [IO.Path]::GetTempFileName()
try {
    [IO.File]::WriteAllText($tmp, $sql, (New-Object Text.UTF8Encoding $false))
    $out = cmd /c "docker exec -i $Container psql -U $DbUser -d $Database -v ON_ERROR_STOP=1 -At -F `"|`" -q < `"$tmp`" 2>&1"
    $code = $LASTEXITCODE
} finally {
    Remove-Item -LiteralPath $tmp -Force -ErrorAction SilentlyContinue
}
if ($code -ne 0) {
    Write-Host "Could not read the codes from $Database in ${Container}:"
    $out | Select-Object -First 5 | ForEach-Object { Write-Host "  $_" }
    exit 2
}

$values = @{}
foreach ($line in $out) {
    $parts = "$line" -split '\|'
    if ($parts.Count -eq 2) { $values[$parts[0]] = [long]$parts[1] }
}

$checks = @(
    @{ name = 'every account has a code'; ok = ($values['null_codes'] -eq 0); detail = "$($values['null_codes']) without" },
    @{ name = 'every code has 7 digits'; ok = ($values['out_of_range'] -eq 0); detail = "$($values['out_of_range']) outside 1000000-9999999" },
    @{ name = 'no code is used twice'; ok = ($values['duplicates'] -eq 0 -and $values['distinct_codes'] -eq ($values['accounts'] - $values['null_codes'])); detail = "$($values['duplicates']) codes used more than once" },
    @{ name = 'the sequence is ahead of the codes'; ok = ($values['sequence_used'] -ge $values['accounts']); detail = "$($values['sequence_used']) codes given, $($values['accounts']) accounts" }
)
if ($values['v5_applied'] -ge 1) {
    $checks += @{ name = 'the V5 triggers are in place'; ok = ($values['v5_triggers'] -eq 2); detail = "$($values['v5_triggers']) of 2" }
}

Write-Host "Participant codes in $Database ($Container), read only"
Write-Host ("  accounts {0}, distinct codes {1}, codes given {2}, codes left {3}" -f $values['accounts'], $values['distinct_codes'], $values['sequence_used'], $values['codes_left'])
if ($values['v5_applied'] -lt 1) { Write-Host '  V5 not applied yet: codes can still be changed by SQL (no trigger)' }
$failed = 0
foreach ($check in $checks) {
    $result = if ($check.ok) { 'PASS' } else { $failed++; 'FAIL' }
    Write-Host ("  {0}  {1}  ({2})" -f $result, $check.name, $check.detail)
}
if ($failed -gt 0) { Write-Host "RESULT: FAIL ($failed)"; exit 1 }
Write-Host 'RESULT: PASS'
exit 0
