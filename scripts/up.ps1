<#
.SYNOPSIS
    Starts the development stack: checks .env first, then runs "docker-compose up -d".

.DESCRIPTION
    Runs scripts\check-env.ps1 and stops when a required setting is missing, so a service never
    starts half configured. Then starts the stack with this repository's docker-compose.yml and
    .env, whatever the current directory is.

    It only ever runs "up -d". It never stops or removes anything; "docker-compose down -v" deletes
    the database and media volumes and is forbidden (README: "Database safety").

    Containers of this stack that were created from another folder (an old copy of the project
    started with the same project name) are reported: they run with that copy's settings until
    "up -d" from here recreates them.

.EXAMPLE
    .\scripts\up.ps1
    .\scripts\up.ps1 auth-service api-gateway
#>
[CmdletBinding()]
param(
    # Services to start; all of them when empty
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Services = @()
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$composeFile = Join-Path $repoRoot 'docker-compose.yml'

& (Join-Path $PSScriptRoot 'check-env.ps1')
if ($LASTEXITCODE -ne 0) {
    Write-Host 'Nothing was started.'
    exit 1
}

$composeArgs = @('--project-directory', $repoRoot, '-f', $composeFile)

# docker-compose reports progress and warnings on stderr; that must not stop the script
$ErrorActionPreference = 'Continue'

# Containers of this stack's services that another folder created
$ids = @(& docker-compose @composeArgs ps -a -q)
if ($LASTEXITCODE -ne 0) {
    Write-Host 'docker-compose cannot reach Docker. Is Docker Desktop running?'
    exit 1
}
$serviceNames = @(& docker-compose @composeArgs config --services)
foreach ($id in $ids) {
    if (-not $id) { continue }
    $container = (& docker inspect $id | Out-String | ConvertFrom-Json)[0]
    $workingDir = $container.Config.Labels.'com.docker.compose.project.working_dir'
    if ($serviceNames -notcontains $container.Config.Labels.'com.docker.compose.service') { continue }
    if ($workingDir -and ($workingDir.TrimEnd('\') -ne $repoRoot.TrimEnd('\'))) {
        Write-Host "Note: $($container.Name.TrimStart('/')) was created from $workingDir and is recreated from $repoRoot now."
    }
}

& docker-compose @composeArgs up -d @Services
if ($LASTEXITCODE -ne 0) {
    Write-Host 'docker-compose up failed.'
    exit 1
}

& docker-compose @composeArgs ps
exit 0
