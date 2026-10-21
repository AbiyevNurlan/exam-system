<#
.SYNOPSIS
    Warns when code changed but docs\PROJECT_MAP.md did not.

.DESCRIPTION
    docs\PROJECT_RULES.md, "Project map rule": at the end of every task, before its commit, docs\PROJECT_MAP.md is
    updated (the section of what changed, the status table and a new line of the change log). A task whose map is
    not updated is not finished.

    What is compared:
      default    the current branch against -Base (default main) from their merge base, plus every uncommitted
                 change (staged, unstaged and untracked files that git does not ignore)
      -Staged    only the staged changes, for a check right before "git commit"

    Code is every changed path except Markdown files (*.md) and the docs\ folder: Java, SQL migrations, YAML,
    compose files, scripts, Dockerfiles, nginx configuration.

    Read only: it runs git diff, git ls-files, git merge-base and git rev-parse. It never changes a file, the index,
    a branch or the stack.

    Exit codes:
      0  no code change, or docs\PROJECT_MAP.md changed as well
      1  code changed and docs\PROJECT_MAP.md did not (a warning; the changed code paths are listed)
      2  not a git repository, or -Base does not exist

.EXAMPLE
    .\scripts\check-project-map.ps1
    .\scripts\check-project-map.ps1 -Base feat/b13-teacher-isolation
    .\scripts\check-project-map.ps1 -Staged
#>
param(
    # The branch or commit the current branch is compared with (from their merge base)
    [string]$Base = 'main',
    # Look only at the staged changes (what the next commit would contain)
    [switch]$Staged,
    # How many changed code paths to print at most
    [int]$MaxList = 30
)

# Native commands are judged by their exit codes; in Windows PowerShell their stderr would otherwise stop the script
$ErrorActionPreference = 'Continue'

$repo = Split-Path -Parent $PSScriptRoot
$mapPath = 'docs/PROJECT_MAP.md'

# Lines of a git command (paths unquoted); $null when git failed. The comma keeps an empty result an empty array
# (PowerShell would otherwise unroll it into $null, which reads as a failure).
function Invoke-GitLines([string[]]$GitArgs) {
    $lines = & git -C $repo -c core.quotepath=off @GitArgs 2>$null
    if ($LASTEXITCODE -ne 0) { return $null }
    return , @($lines | Where-Object { $_ -and $_.Trim() -ne '' } | ForEach-Object { $_.Trim() })
}

if ($null -eq (Invoke-GitLines @('rev-parse', '--is-inside-work-tree'))) {
    Write-Host "ERROR: $repo is not a git repository"
    exit 2
}

$changed = New-Object System.Collections.Generic.List[string]
if ($Staged) {
    $what = 'the staged changes'
    $stagedPaths = Invoke-GitLines @('diff', '--cached', '--name-only')
    if ($null -eq $stagedPaths) { Write-Host 'ERROR: git diff --cached failed'; exit 2 }
    foreach ($path in $stagedPaths) { $changed.Add($path) }
} else {
    if ($null -eq (Invoke-GitLines @('rev-parse', '--verify', '--quiet', "$Base^{commit}"))) {
        Write-Host "ERROR: the base '$Base' does not exist (give another one with -Base)"
        exit 2
    }
    $mergeBase = Invoke-GitLines @('merge-base', 'HEAD', $Base)
    if (-not $mergeBase) { Write-Host "ERROR: HEAD and '$Base' have no common commit"; exit 2 }
    $mergeBase = $mergeBase[0]
    $what = "'$Base' (merge base $($mergeBase.Substring(0, 7))) and the uncommitted changes"
    $committed = Invoke-GitLines @('diff', '--name-only', $mergeBase, 'HEAD')
    $uncommitted = Invoke-GitLines @('diff', '--name-only', 'HEAD')
    $untracked = Invoke-GitLines @('ls-files', '--others', '--exclude-standard')
    if ($null -eq $committed -or $null -eq $uncommitted -or $null -eq $untracked) {
        Write-Host 'ERROR: git diff or git ls-files failed'
        exit 2
    }
    foreach ($path in ($committed + $uncommitted + $untracked)) { $changed.Add($path) }
}

$all = @($changed | ForEach-Object { $_ -replace '\\', '/' } | Sort-Object -Unique)
$code = @($all | Where-Object { -not ($_ -like 'docs/*') -and -not ($_ -like '*.md') })
$mapChanged = $all -contains $mapPath

# The newest line of the change log (section 17), for information only
$newest = $null
$mapFile = Join-Path $repo 'docs\PROJECT_MAP.md'
if (Test-Path $mapFile) {
    $inLog = $false
    foreach ($line in [System.IO.File]::ReadAllLines($mapFile, [System.Text.Encoding]::UTF8)) {
        if ($line -match '^## 17\.') { $inLog = $true; continue }
        if ($inLog -and $line -match '^## ') { break }
        if ($inLog -and $line -match '^\|\s*(\d{4}-\d{2}-\d{2}[^|]*)\|\s*([^|]*)\|') {
            $newest = "$($Matches[1].Trim()), $($Matches[2].Trim())"
            break
        }
    }
} else {
    Write-Host "NOTE: $mapPath does not exist in this working tree"
}

Write-Host "Compared: $what"
Write-Host "Changed paths: $($all.Count), of them code: $($code.Count)"
if ($newest) { Write-Host "Newest change log line of ${mapPath}: $newest" }

if ($code.Count -eq 0) {
    Write-Host 'OK: no code changed, the project map needs no update.'
    exit 0
}

if ($mapChanged) {
    Write-Host "OK: code changed and $mapPath was updated too."
    Write-Host '    Check by eye that the section of the change, the status table (section 14) and the change log (section 17) say what was done.'
    exit 0
}

Write-Host ''
Write-Host "WARNING: code changed but $mapPath did not. The task is not finished (PROJECT_RULES.md, Project map rule)."
Write-Host '         Update the section of what changed, the status table (section 14) and add a line at the top of'
Write-Host '         the change log (section 17), then run this check again.'
Write-Host ''
Write-Host 'Changed code paths:'
$code | Select-Object -First $MaxList | ForEach-Object { Write-Host "  $_" }
if ($code.Count -gt $MaxList) { Write-Host "  ... and $($code.Count - $MaxList) more" }
exit 1
