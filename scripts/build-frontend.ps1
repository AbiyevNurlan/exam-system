<#
.SYNOPSIS
    Builds the frontend for the public mode and puts the result into deploy\public\site.

.DESCRIPTION
    Production build of the frontend repository (npm run build) for one origin: the API is called at
    the relative path /api (VITE_API_URL=/api), which the edge passes to the gateway. The dist folder
    then replaces the contents of deploy\public\site (git-ignored), which the edge container serves.

    Installs the dependencies (npm ci) only when node_modules is missing. Uncommitted changes in
    the frontend repository are reported, not refused. The branch and commit of the build go to
    deploy\public\state\frontend-build.txt (not served).

    With -OutDir the build goes into that folder instead (emptied first) and deploy\public\site and
    the state file stay as they are: scripts\deploy-frontend.ps1 builds there and then swaps the
    files into the served folder without a moment in which it is empty.

.EXAMPLE
    .\scripts\build-frontend.ps1
    .\scripts\build-frontend.ps1 -FrontendDir D:\code\exam-frontend
    .\scripts\build-frontend.ps1 -OutDir F:\exam-system\deploy\public\frontend-staging
#>
[CmdletBinding()]
param(
    # Default: $env:EXAM_FRONTEND_DIR, else the usual folder of this machine
    [string]$FrontendDir = '',
    # Build into this folder instead of deploy\public\site (the served folder is not touched)
    [string]$OutDir = ''
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
if (-not $FrontendDir) { $FrontendDir = $env:EXAM_FRONTEND_DIR }
if (-not $FrontendDir) { $FrontendDir = 'C:\Users\Nurlan\Desktop\Yukselish imtahan\exam-frontend' }
$site = Join-Path $repoRoot 'deploy\public\site'
$state = Join-Path $repoRoot 'deploy\public\state'

if (-not (Test-Path -LiteralPath (Join-Path $FrontendDir 'package.json'))) {
    Write-Host "No frontend at $FrontendDir (package.json missing). Pass -FrontendDir or set EXAM_FRONTEND_DIR."
    exit 1
}

$branch = (& git -C $FrontendDir rev-parse --abbrev-ref HEAD 2>$null)
$commit = (& git -C $FrontendDir rev-parse --short HEAD 2>$null)
$dirty = @(& git -C $FrontendDir status --porcelain 2>$null).Count
Write-Host "Frontend: $FrontendDir ($branch $commit$(if ($dirty) { ", $dirty uncommitted change(s)" }))"

$previousApiUrl = $env:VITE_API_URL
Push-Location $FrontendDir
try {
    # npm writes progress to stderr; only the exit codes count
    $ErrorActionPreference = 'Continue'
    if (-not (Test-Path -LiteralPath 'node_modules')) {
        Write-Host 'npm ci ...'
        & npm ci --no-audit --no-fund
        if ($LASTEXITCODE -ne 0) { Write-Host 'npm ci failed.'; exit 1 }
    }
    $env:VITE_API_URL = '/api'
    Write-Host 'npm run build ...'
    & npm run build
    if ($LASTEXITCODE -ne 0) { Write-Host 'The frontend build failed; deploy\public\site was not changed.'; exit 1 }
} finally {
    $env:VITE_API_URL = $previousApiUrl
    Pop-Location
    $ErrorActionPreference = 'Stop'
}

$dist = Join-Path $FrontendDir 'dist'
if (-not (Test-Path -LiteralPath (Join-Path $dist 'index.html'))) {
    Write-Host 'dist\index.html is missing after the build; deploy\public\site was not changed.'
    exit 1
}

if ($OutDir) {
    New-Item -ItemType Directory -Force -Path $OutDir | Out-Null
    Get-ChildItem -LiteralPath $OutDir -Force | Remove-Item -Recurse -Force
    Copy-Item -Path (Join-Path $dist '*') -Destination $OutDir -Recurse -Force
    Set-Content -LiteralPath (Join-Path $OutDir '.build-info') -Encoding ascii -Value @(
        "built=$((Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ'))", "branch=$branch", "commit=$commit", "uncommitted=$dirty")
    $files = @(Get-ChildItem -LiteralPath $OutDir -Recurse -File)
    Write-Host ("{0}: {1} files, {2:N0} bytes (deploy\public\site not changed)" -f $OutDir, $files.Count, ($files | Measure-Object Length -Sum).Sum)
    exit 0
}

# Replace the contents (not the folder itself: the running edge has it mounted)
New-Item -ItemType Directory -Force -Path $site, $state | Out-Null
Get-ChildItem -LiteralPath $site -Force | Remove-Item -Recurse -Force
Copy-Item -Path (Join-Path $dist '*') -Destination $site -Recurse -Force
Set-Content -LiteralPath (Join-Path $state 'frontend-build.txt') -Encoding ascii -Value @(
    "built=$((Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ'))", "branch=$branch", "commit=$commit", "uncommitted=$dirty")

$files = @(Get-ChildItem -LiteralPath $site -Recurse -File)
$size = ($files | Measure-Object Length -Sum).Sum
Write-Host ("deploy\public\site: {0} files, {1:N0} bytes" -f $files.Count, $size)
exit 0
