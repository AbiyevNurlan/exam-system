<#
.SYNOPSIS
    Updates only the frontend of the running public mode: the public address stays the same.

.DESCRIPTION
    For a frontend change only (a backend change needs .\scripts\go-public.ps1 start). No container
    is started, stopped or restarted: the edge (nginx) serves deploy\public\site straight from the
    bind mount and has no file cache, so new files are served at once without a reload; the
    Cloudflare tunnel and with it the public address are not touched.

    1. Checks: the frontend repository is on main with no uncommitted change, enough free space on
       C:, edge and tunnel running; the public address and the start time of every container are noted.
    2. Builds with scripts\build-frontend.ps1 -OutDir into deploy\public\frontend-staging.
    3. Copies the served version to deploy\public\frontend-previous\<time> (the last 3 are kept).
    4. Puts the new version in place without a moment in which the site is empty: first the new
       hashed assets (the old index.html does not know them), then index.html by a rename, then the
       files of neither the new nor the previous version are removed (an open tab of the previous
       version can still load its chunks).
    5. Checks: the same public address and no container restarted; / and /login answer 200 over the
       public address and locally; the served index.html is the new build; index.html is no-cache;
       its main script answers 200.
    6. On any failure after step 4 the previous version is put back the same way and checked again.

    While it runs the public address is polled every 0.3 s; the summary gives the longest outage.
    Nothing secret is read or printed.

.EXAMPLE
    .\scripts\deploy-frontend.ps1
    .\scripts\deploy-frontend.ps1 -TestRollback    # deploys, fails the check on purpose, rolls back
#>
[CmdletBinding()]
param(
    # Default: $env:EXAM_FRONTEND_DIR, else the usual folder of this machine
    [string]$FrontendDir = '',
    [double]$MinFreeGB = 1.5,
    [int]$KeepBackups = 3,
    # Fails the check after the swap on purpose, to see the automatic rollback work
    [switch]$TestRollback
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
if (-not $FrontendDir) { $FrontendDir = $env:EXAM_FRONTEND_DIR }
if (-not $FrontendDir) { $FrontendDir = 'C:\Users\Nurlan\Desktop\Yukselish imtahan\exam-frontend' }
$public = Join-Path $repoRoot 'deploy\public'
$site = Join-Path $public 'site'
$staging = Join-Path $public 'frontend-staging'
$previousRoot = Join-Path $public 'frontend-previous'
$stateFile = Join-Path $public 'state\frontend-build.txt'
$localBase = 'http://127.0.0.1:8088'

function Stop-Deploy([string]$message) {
    Write-Host "STOPPED: $message"
    Write-Host 'Nothing was changed on the site.'
    exit 1
}

function Get-PublicUrl {
    $out = & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'go-public.ps1') url 2>$null | Select-Object -Last 1
    if ("$out" -match '^https://[a-z0-9-]+\.trycloudflare\.com$') { return "$out".Trim() }
    return $null
}

function Get-ContainerStarts {
    $names = @(& docker ps --filter 'name=exam-' --format '{{.Names}}')
    $starts = @{}
    foreach ($n in $names) { $starts[$n] = (& docker inspect -f '{{.State.StartedAt}}' $n).Trim() }
    return $starts
}

function Get-Http([string]$url) {
    try {
        $r = Invoke-WebRequest -Uri $url -UseBasicParsing -TimeoutSec 20 -Headers @{ 'Cache-Control' = 'no-cache' }
        return @{ status = [int]$r.StatusCode; body = $r.Content; cache = "$($r.Headers['Cache-Control'])" }
    } catch {
        $resp = $_.Exception.Response
        return @{ status = $(if ($resp) { [int]$resp.StatusCode } else { 0 }); body = ''; cache = '' }
    }
}

# The main script of an index.html, e.g. /assets/index-C_nDn-11.js: it names the build
function Get-EntryScript([string]$html) {
    $m = [regex]::Match($html, '<script[^>]+src="(/assets/index-[^"]+\.js)"')
    if ($m.Success) { return $m.Groups[1].Value }
    return $null
}

# Relative paths of the files of a version (no dot files such as .build-info)
function Get-VersionFiles([string]$dir) {
    if (-not (Test-Path -LiteralPath $dir)) { return @() }
    $root = (Resolve-Path -LiteralPath $dir).Path.TrimEnd('\')
    return @(Get-ChildItem -LiteralPath $root -Recurse -File -Force |
        Where-Object { $_.Name -notlike '.*' } |
        ForEach-Object { $_.FullName.Substring($root.Length + 1) })
}

# Puts the version in $source into the served folder; keeps the files of $keepFrom as well
function Install-Version([string]$source, [string]$keepFrom) {
    $files = Get-VersionFiles $source
    if ($files -notcontains 'index.html') { throw "no index.html in $source" }
    # 1. everything but index.html: new hashed names, the served index.html does not refer to them yet
    foreach ($rel in ($files | Where-Object { $_ -ne 'index.html' })) {
        $target = Join-Path $site $rel
        New-Item -ItemType Directory -Force -Path (Split-Path $target -Parent) | Out-Null
        Copy-Item -LiteralPath (Join-Path $source $rel) -Destination $target -Force
    }
    # 2. index.html by a rename in the same folder, so nginx never reads half a file
    $tmp = Join-Path $site 'index.html.next'
    Copy-Item -LiteralPath (Join-Path $source 'index.html') -Destination $tmp -Force
    Move-Item -LiteralPath $tmp -Destination (Join-Path $site 'index.html') -Force
    # 3. files of neither this nor the kept version
    $wanted = @{}
    foreach ($rel in $files + (Get-VersionFiles $keepFrom)) { $wanted[$rel.ToLowerInvariant()] = $true }
    foreach ($rel in (Get-VersionFiles $site)) {
        if (-not $wanted.ContainsKey($rel.ToLowerInvariant())) { Remove-Item -LiteralPath (Join-Path $site $rel) -Force }
    }
    Get-ChildItem -LiteralPath $site -Recurse -Directory | Sort-Object { $_.FullName.Length } -Descending |
        Where-Object { @(Get-ChildItem -LiteralPath $_.FullName -Force).Count -eq 0 } | Remove-Item -Force
}

# Checks the served site against the version in $expectedDir; returns a list of problems
function Test-Served([string]$expectedDir, [string]$url, [hashtable]$startsBefore) {
    $problems = @()
    $expectedEntry = Get-EntryScript ([IO.File]::ReadAllText((Join-Path $expectedDir 'index.html')))
    $urlNow = Get-PublicUrl
    if ($urlNow -ne $url) { $problems += "public address changed: $url -> $urlNow" }
    $startsNow = Get-ContainerStarts
    foreach ($n in $startsBefore.Keys) {
        if ($startsNow[$n] -ne $startsBefore[$n]) { $problems += "container $n was restarted or is gone" }
    }
    foreach ($base in $url, $localBase) {
        $root = Get-Http "$base/"
        if ($root.status -ne 200) { $problems += "$base/ answered $($root.status)" ; continue }
        $served = Get-EntryScript $root.body
        if ($served -ne $expectedEntry) { $problems += "$base/ serves $served, expected $expectedEntry" }
        if ($root.cache -notmatch 'no-cache') { $problems += "$base/ has Cache-Control '$($root.cache)', expected no-cache" }
        $login = Get-Http "$base/login"
        if ($login.status -ne 200) { $problems += "$base/login answered $($login.status)" }
        if ($served) {
            $asset = Get-Http "$base$served"
            if ($asset.status -ne 200) { $problems += "$base$served answered $($asset.status)" }
        }
    }
    return ,$problems
}

# ---------------------------------------------------------------- 1. checks

$branch = (& git -C $FrontendDir rev-parse --abbrev-ref HEAD 2>$null)
$commit = (& git -C $FrontendDir rev-parse --short HEAD 2>$null)
if ($LASTEXITCODE -ne 0 -or -not $branch) { Stop-Deploy "no git repository at $FrontendDir" }
if ($branch -ne 'main') { Stop-Deploy "the frontend repository is on '$branch', not main" }
$dirty = @(& git -C $FrontendDir status --porcelain 2>$null)
if ($dirty.Count -gt 0) { Stop-Deploy "the frontend repository has $($dirty.Count) uncommitted change(s)" }

$freeGB = [math]::Round((Get-PSDrive C).Free / 1GB, 2)
if ($freeGB -lt $MinFreeGB) { Stop-Deploy "only $freeGB GB free on C: (at least $MinFreeGB GB needed)" }

foreach ($c in 'exam-edge', 'exam-cloudflared') {
    if ((& docker inspect -f '{{.State.Running}}' $c 2>$null) -ne 'true') { Stop-Deploy "$c is not running (start the public mode with .\scripts\go-public.ps1 start)" }
}
$url = Get-PublicUrl
if (-not $url) { Stop-Deploy 'no public address in the tunnel log' }
$startsBefore = Get-ContainerStarts
$before = Get-Http "$url/"
if ($before.status -ne 200) { Stop-Deploy "$url/ answers $($before.status) before the deploy" }
$entryBefore = Get-EntryScript $before.body
if (-not (Test-Path -LiteralPath (Join-Path $site 'index.html'))) { Stop-Deploy 'deploy\public\site holds no version to fall back to' }

Write-Host "Frontend:        $FrontendDir (main $commit, clean)"
Write-Host "Free on C:       $freeGB GB"
Write-Host "Public address:  $url"
Write-Host "Served now:      $entryBefore"

# ---------------------------------------------------------------- availability probe

$probeLog = Join-Path $env:TEMP "deploy-frontend-probe-$PID.log"
$probeFlag = Join-Path $env:TEMP "deploy-frontend-probe-$PID.run"
Set-Content -LiteralPath $probeFlag -Value 'run'
$probe = Start-Job -ArgumentList $url, $probeLog, $probeFlag -ScriptBlock {
    param($url, $log, $flag)
    $w = [IO.StreamWriter]::new($log)
    try {
        while (Test-Path -LiteralPath $flag) {
            $t = [DateTime]::UtcNow
            try { $s = [int](Invoke-WebRequest -Uri "$url/" -UseBasicParsing -TimeoutSec 5).StatusCode } catch { $s = 0 }
            $w.WriteLine("$($t.Ticks)|$s"); $w.Flush()
            Start-Sleep -Milliseconds 300
        }
    } finally { $w.Close() }
}

function Get-ProbeSummary {
    Remove-Item -LiteralPath $probeFlag -Force -ErrorAction SilentlyContinue
    $null = Wait-Job $probe -Timeout 15
    Remove-Job $probe -Force -ErrorAction SilentlyContinue
    $rows = @(Get-Content -LiteralPath $probeLog -ErrorAction SilentlyContinue | ForEach-Object {
        $p = $_ -split '\|'; [pscustomobject]@{ at = [long]$p[0]; ok = ($p[1] -eq '200') } })
    Remove-Item -LiteralPath $probeLog -Force -ErrorAction SilentlyContinue
    $longest = 0.0; $start = $null
    foreach ($r in $rows) {
        if (-not $r.ok -and $null -eq $start) { $start = $r.at }
        if ($r.ok -and $null -ne $start) { $longest = [math]::Max($longest, ($r.at - $start) / 1e7); $start = $null }
    }
    if ($null -ne $start -and $rows.Count) { $longest = [math]::Max($longest, ($rows[-1].at - $start) / 1e7) }
    return [pscustomobject]@{ requests = $rows.Count; failed = @($rows | Where-Object { -not $_.ok }).Count; longest = $longest }
}

# ---------------------------------------------------------------- 2. build

Write-Host '== Build'
& (Join-Path $PSScriptRoot 'build-frontend.ps1') -FrontendDir $FrontendDir -OutDir $staging
if ($LASTEXITCODE -ne 0) { $null = Get-ProbeSummary; Stop-Deploy 'the frontend build failed' }
$entryNew = Get-EntryScript ([IO.File]::ReadAllText((Join-Path $staging 'index.html')))
Write-Host "New build:       $entryNew$(if ($entryNew -eq $entryBefore) { ' (the same as the served one: the assets do not change)' })"

# ---------------------------------------------------------------- 3. backup of the served version

$stamp = (Get-Date).ToString('yyyyMMdd-HHmmss')
$backup = Join-Path $previousRoot $stamp
New-Item -ItemType Directory -Force -Path $backup | Out-Null
Copy-Item -Path (Join-Path $site '*') -Destination $backup -Recurse -Force
if (Test-Path -LiteralPath $stateFile) { Copy-Item -LiteralPath $stateFile -Destination (Join-Path $backup '.build-info') -Force }
$old = @(Get-ChildItem -LiteralPath $previousRoot -Directory | Sort-Object Name -Descending | Select-Object -Skip $KeepBackups)
$old | Remove-Item -Recurse -Force
Write-Host "Backup:          $backup ($(@(Get-VersionFiles $backup).Count) files; $(@(Get-ChildItem -LiteralPath $previousRoot -Directory).Count) kept)"

# ---------------------------------------------------------------- 4.-6. swap, check, roll back

$result = 'DEPLOYED'
$problems = @()
try {
    Write-Host '== Swap'
    Install-Version $staging $backup
    Start-Sleep -Seconds 1
    Write-Host '== Check'
    $problems = Test-Served $staging $url $startsBefore
    if ($TestRollback) { $problems += 'TEST: failure forced by -TestRollback' }
} catch {
    $problems += "swap failed: $($_.Exception.Message)"
}

if ($problems.Count -gt 0) {
    $problems | ForEach-Object { Write-Host "  PROBLEM: $_" }
    Write-Host "== Rollback to $stamp"
    try {
        Install-Version $backup $staging
        # Files of the failed version are not needed by any open tab: drop them
        Install-Version $backup $backup
        Start-Sleep -Seconds 1
        $after = Test-Served $backup $url $startsBefore
        if ($after.Count -gt 0) { $after | ForEach-Object { Write-Host "  AFTER ROLLBACK: $_" }; $result = 'ROLLBACK FAILED' }
        else { $result = 'ROLLED BACK' }
    } catch {
        Write-Host "  rollback error: $($_.Exception.Message)"
        $result = 'ROLLBACK FAILED'
    }
} else {
    Copy-Item -LiteralPath (Join-Path $staging '.build-info') -Destination $stateFile -Force
}

$probeSummary = Get-ProbeSummary
$served = Get-EntryScript (Get-Http "$url/").body
Write-Host ''
Write-Host "RESULT:          $result"
Write-Host "Public address:  $(Get-PublicUrl) ($(if ((Get-PublicUrl) -eq $url) { 'unchanged' } else { 'CHANGED' }))"
Write-Host "Served:          $served (before: $entryBefore)"
Write-Host ("Availability:    {0} probes, {1} failed, longest outage {2:N1} s" -f $probeSummary.requests, $probeSummary.failed, $probeSummary.longest)
if ($result -eq 'DEPLOYED') { exit 0 }
if ($result -eq 'ROLLED BACK') { Write-Host 'The previous version is served again.'; exit 2 }
Write-Host 'The site may be broken: restore it with .\scripts\go-public.ps1 start'
exit 3
