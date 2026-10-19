<#
.SYNOPSIS
    Keeps the public mode up: when the site cannot be reached it is started again with a new tunnel.

.DESCRIPTION
    Every -IntervalSeconds (default 60) it checks:
      - exam-edge and exam-cloudflared run;
      - the address in the tunnel log is the one the stack was started with (deploy\public\state\url.txt):
        after a reboot Docker starts the old containers again and the tunnel gets a new address that
        exam-service does not know;
      - <address>/ answers 200 from outside, through Cloudflare (3 tries, 10 s apart: one lost request
        is no outage); the log names what each try got (502, 530, timeout/no answer).
    When a check fails it runs .\scripts\go-public.ps1 start -NewTunnel -SkipBackup -SkipFrontendBuild
    (no data is touched; the served frontend stays; the nightly backup is separate) and writes the new
    address to deploy\public\CURRENT_URL.txt and to deploy\public\state\watch.log, with an
    "ADDRESS CHANGED: old -> new" line. Not a bare restart of exam-cloudflared: that would give a new
    address that exam-service does not know (media links of exams would point at the old one).

    It does nothing while the site was stopped on purpose (.\scripts\go-public.ps1 stop leaves
    deploy\public\state\stopped-on-purpose; the next start removes it), and it waits 5 minutes
    after a restart before it tries again. The new address must still be sent to the participants:
    a free quick tunnel cannot keep its address.

.EXAMPLE
    .\scripts\watch-public.ps1            # runs until the window is closed
    .\scripts\watch-public.ps1 -Once      # one check (and one restart if needed), e.g. from Task Scheduler
#>
[CmdletBinding()]
param(
    [int]$IntervalSeconds = 60,
    [int]$CooldownSeconds = 300,
    [switch]$Once
)

$ErrorActionPreference = 'Continue'
$repoRoot = Split-Path $PSScriptRoot -Parent
$stateDir = Join-Path $repoRoot 'deploy\public\state'
$urlFile = Join-Path $stateDir 'url.txt'
$stoppedFile = Join-Path $stateDir 'stopped-on-purpose'
$logFile = Join-Path $stateDir 'watch.log'
$goPublic = Join-Path $PSScriptRoot 'go-public.ps1'
$lastRestart = [DateTime]::MinValue

function Write-Log([string]$message) {
    $line = "$((Get-Date).ToString('yyyy-MM-dd HH:mm:ss')) $message"
    Write-Host $line
    New-Item -ItemType Directory -Force -Path $stateDir | Out-Null
    Add-Content -LiteralPath $logFile -Value $line -Encoding ascii
}

function Test-Running([string]$container) {
    return (& docker inspect -f '{{.State.Running}}' $container 2>$null) -eq 'true'
}

# Why the site is not up, or $null when it is
function Get-Problem {
    if (-not (Test-Running 'exam-edge')) { return 'exam-edge is not running' }
    if (-not (Test-Running 'exam-cloudflared')) { return 'exam-cloudflared is not running' }
    $expected = if (Test-Path -LiteralPath $urlFile) { (Get-Content -LiteralPath $urlFile -TotalCount 1).Trim() } else { '' }
    $current = (& powershell -NoProfile -ExecutionPolicy Bypass -File $goPublic url 2>$null | Select-Object -Last 1)
    $current = "$current".Trim()
    if ($current -notmatch '^https://') { return 'the tunnel has no address' }
    if ($expected -and $current -ne $expected) { return "the tunnel address changed ($expected -> $current): the services do not know it" }
    # From outside, through Cloudflare: a tunnel that is reconnecting answers 502/530 or nothing at all
    $answers = @()
    for ($try = 1; $try -le 3; $try++) {
        try {
            $status = [int](Invoke-WebRequest -Uri "$current/" -UseBasicParsing -TimeoutSec 15).StatusCode
            if ($status -eq 200) { return $null }
            $answers += "$status"
        } catch {
            $response = $_.Exception.Response
            $answers += $(if ($response) { "$([int]$response.StatusCode)" } else { 'timeout/no answer' })
        }
        if ($try -lt 3) { Start-Sleep -Seconds 10 }
    }
    return "$current/ does not answer 200 (tries: $($answers -join ', '))"
}

function Invoke-Check {
    if (Test-Path -LiteralPath $stoppedFile) { Write-Host "$((Get-Date).ToString('HH:mm:ss')) stopped on purpose (go-public.ps1 stop): not started"; return }
    $problem = Get-Problem
    if (-not $problem) { Write-Host "$((Get-Date).ToString('HH:mm:ss')) ok"; return }
    if (((Get-Date) - $script:lastRestart).TotalSeconds -lt $CooldownSeconds) {
        Write-Log "PROBLEM: $problem (restarted less than $CooldownSeconds s ago, waiting)"
        return
    }
    # Not a bare restart of exam-cloudflared: a new tunnel has a new address, and exam-service must get
    # it too (signed media links), which only go-public.ps1 start does
    $before = if (Test-Path -LiteralPath $urlFile) { (Get-Content -LiteralPath $urlFile -TotalCount 1).Trim() } else { '' }
    Write-Log "PROBLEM: $problem -> go-public.ps1 start -NewTunnel (the tunnel container is replaced: the address WILL change)"
    $script:lastRestart = Get-Date
    $out = & powershell -NoProfile -ExecutionPolicy Bypass -File $goPublic start -NewTunnel -SkipBackup -SkipFrontendBuild 2>&1 | ForEach-Object { "$_" }
    $code = $LASTEXITCODE
    $address = ($out | Select-String -Pattern '^PUBLIC ADDRESS: (\S+)' | Select-Object -Last 1)
    if ($code -eq 0 -and $address) {
        $after = $address.Matches[0].Groups[1].Value
        if ($before -and $before -ne $after) { Write-Log "ADDRESS CHANGED: $before -> $after" }
        Write-Log "STARTED: new public address $after (send it to the participants)"
    }
    else {
        Write-Log "START FAILED (exit $code):"
        $out | Select-Object -Last 8 | ForEach-Object { Write-Log "  $_" }
    }
}

Write-Host "watch-public: every $IntervalSeconds s; log $logFile"
do {
    Invoke-Check
    if (-not $Once) { Start-Sleep -Seconds $IntervalSeconds }
} while (-not $Once)
