<#
.SYNOPSIS
    Compares the gateway routes of the development and the production configuration.

.DESCRIPTION
    Reads spring.cloud.gateway.routes of api-gateway\src\main\resources\application.yml and
    application-production.yml and compares, route by route, the id, the order and the Path
    predicates. The target URIs (http:// in development, lb:// in production) and the filter
    arguments may differ; the paths may not: a path routed in development but not in production
    answers 404 only after deployment.

    Exit code 0 when both files route the same paths in the same order, 1 otherwise (with the
    differences listed).

.EXAMPLE
    .\scripts\check-gateway-routes.ps1
    .\scripts\check-gateway-routes.ps1 -DevFile some\copy.yml -ProdFile other\copy.yml
#>
[CmdletBinding()]
param(
    [string]$DevFile,
    [string]$ProdFile
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
if (-not $DevFile) { $DevFile = Join-Path $repoRoot 'api-gateway\src\main\resources\application.yml' }
if (-not $ProdFile) { $ProdFile = Join-Path $repoRoot 'api-gateway\src\main\resources\application-production.yml' }

# The routes of one file in order: id and the sorted list of its Path patterns
function Read-Routes([string]$file) {
    $routes = New-Object System.Collections.Generic.List[object]
    $inRoutes = $false
    $routesIndent = -1
    $current = $null
    foreach ($line in Get-Content $file) {
        if ($line -match '^\s*#' -or $line.Trim() -eq '') { continue }
        $indent = $line.Length - $line.TrimStart().Length
        if (-not $inRoutes) {
            if ($line -match '^(\s*)routes:\s*$') { $inRoutes = $true; $routesIndent = $Matches[1].Length }
            continue
        }
        # The routes block ends at the first key that is not deeper than "routes:"
        if ($indent -le $routesIndent -and $line.TrimStart() -notmatch '^-') { break }
        if ($line -match '^\s*-\s*id:\s*(\S+)') {
            $current = [pscustomobject]@{ Id = $Matches[1]; Paths = @() }
            $routes.Add($current)
            continue
        }
        if ($current -and $line -match '^\s*-\s*Path=(.+)$') {
            $current.Paths = @($Matches[1].Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ } | Sort-Object)
        }
    }
    if (-not $inRoutes) { throw "No routes: block in $file" }
    return $routes
}

$dev = Read-Routes $DevFile
$prod = Read-Routes $ProdFile
$problems = New-Object System.Collections.Generic.List[string]

$devIds = @($dev | ForEach-Object Id)
$prodIds = @($prod | ForEach-Object Id)
foreach ($id in $devIds | Where-Object { $prodIds -notcontains $_ }) { $problems.Add("route '$id' only in development") }
foreach ($id in $prodIds | Where-Object { $devIds -notcontains $_ }) { $problems.Add("route '$id' only in production") }
$common = @($devIds | Where-Object { $prodIds -contains $_ })
$prodCommon = @($prodIds | Where-Object { $devIds -contains $_ })
if (($common -join ',') -ne ($prodCommon -join ',')) {
    # The first matching route wins, so the order is part of the routing
    $problems.Add("route order differs: development $($common -join ' > '); production $($prodCommon -join ' > ')")
}
foreach ($id in $common) {
    $d = @(($dev | Where-Object Id -eq $id).Paths)
    $p = @(($prod | Where-Object Id -eq $id).Paths)
    foreach ($path in $d | Where-Object { $p -notcontains $_ }) { $problems.Add("route '$id': $path only in development") }
    foreach ($path in $p | Where-Object { $d -notcontains $_ }) { $problems.Add("route '$id': $path only in production") }
}

Write-Output ("{0,-20} {1,5} {2,5}" -f 'route', 'dev', 'prod')
foreach ($id in ($devIds + $prodIds | Select-Object -Unique)) {
    $d = ($dev | Where-Object Id -eq $id); $p = ($prod | Where-Object Id -eq $id)
    Write-Output ("{0,-20} {1,5} {2,5}" -f $id, $(if ($d) { @($d.Paths).Count } else { '-' }), $(if ($p) { @($p.Paths).Count } else { '-' }))
}
if ($problems.Count -gt 0) {
    Write-Output ''
    Write-Output "FAIL: the gateway files route different paths ($($problems.Count)):"
    $problems | ForEach-Object { Write-Output "  - $_" }
    Write-Output 'A new route goes into both application.yml and application-production.yml (docs\PROJECT_RULES.md).'
    exit 1
}
Write-Output ''
Write-Output "PASS: $($dev.Count) routes, the same ids, order and paths in both files"
exit 0
