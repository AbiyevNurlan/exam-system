<#
.SYNOPSIS
    Temporary public mode: serves the site from this computer through a Cloudflare quick tunnel
    (docker-compose.public.yml, docs/PUBLIC_TEMP.md).

.DESCRIPTION
    start   Preflight (refuses to start on any problem):
              - the payment sandbox off in .env: PAYMENTS_SANDBOX_ALLOWED, PAYMENTS_SANDBOX_ALLOWED_PUBLIC not true and
                no PAYMENTS_SANDBOX_ALLOW_IN_PRODUCTION (sandbox-state.ps1); -AllowSandbox goes on anyway, with the
                red warning at the start and again next to the address. A complete DEMO (PAYMENTS_DEMO_MODE=true,
                PAYMENTS_PROVIDER=SANDBOX, PAYMENTS_SANDBOX_ALLOWED_PUBLIC=true, docs\DEMO_PAYMENTS.md) goes on with a
                yellow warning (so watch-public.ps1 brings the site back in DEMO too); an incomplete one refuses
              - .env complete for docker-compose.yml + docker-compose.public.yml (check-env.ps1),
                PUBLIC_MAIL_* present, secrets strong, .env ignored and untracked by git
              - the database accepts DB_PASSWORD (else: scripts\init-public-env.ps1)
              - gateway routes identical in both profiles (check-gateway-routes.ps1) and every
                frontend path routed by the production gateway (check-gateway-production.ps1)
              - a backup (scripts\backup.ps1), unless -SkipBackup is given explicitly
            Then: frontend build (build-frontend.ps1), Mailpit stopped, tunnel started, the stack
            brought up with the public override ("up -d", never "down"), health of every service,
            the public address printed and smoke tests run against it.
    stop    Stops only edge and the tunnel. Database, Redis, storage and services keep running.
    status  Containers, tunnel, address, media address of exam-service, last backup, "Payments: DEMO / OFF /
            SANDBOX-BLOCKED / EXTERNAL", "Sandbox: OFF" (DEMO in a complete demo, or ON as FAIL, with the names of the
            settings, never their values).
    url     Prints the current public address.

    Never runs "down" and never removes a volume.

.EXAMPLE
    .\scripts\go-public.ps1 start
    .\scripts\go-public.ps1 start -SkipBackup
    .\scripts\go-public.ps1 status
    .\scripts\go-public.ps1 stop
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('start', 'stop', 'status', 'url')]
    [string]$Command,
    # Start without a fresh backup (on purpose only: the preflight otherwise takes one)
    [switch]$SkipBackup,
    # Serve the build that is already in deploy\public\site
    [switch]$SkipFrontendBuild,
    # Skip the production gateway check (it starts a temporary gateway for about a minute)
    [switch]$SkipGatewayCheck,
    # start: replace a running tunnel by a new one (a new address), e.g. after Cloudflare dropped it
    [switch]$NewTunnel,
    # start: go on although .env turns the payment sandbox on (on purpose only; the warning stays)
    [switch]$AllowSandbox
)

# Native commands (docker, git, curl) are judged by their exit codes; in Windows PowerShell their
# stderr would otherwise stop the script. Cmdlets that must not fail use -ErrorAction Stop.
$ErrorActionPreference = 'Continue'
$repoRoot = Split-Path $PSScriptRoot -Parent
$envFile = Join-Path $repoRoot '.env'
$baseFile = Join-Path $repoRoot 'docker-compose.yml'
$publicFile = Join-Path $repoRoot 'docker-compose.public.yml'
$stateDir = Join-Path $repoRoot 'deploy\public\state'
$urlFile = Join-Path $stateDir 'url.txt'
# The address for people (git-ignored), and the mark of a stop on purpose (see scripts\watch-public.ps1)
$currentUrlFile = Join-Path $repoRoot 'deploy\public\CURRENT_URL.txt'
$stoppedFile = Join-Path $stateDir 'stopped-on-purpose'
$composeArgs = @('compose', '--project-directory', $repoRoot, '-f', $baseFile, '-f', $publicFile)
$tunnelContainer = 'exam-cloudflared'
$localEdge = 'http://127.0.0.1:8088'
# Get-SandboxState: which settings of .env turn the payment sandbox on (names only)
. (Join-Path $PSScriptRoot 'sandbox-state.ps1')

# Containers and the port of their health endpoint (checked inside the container)
$services = [ordered]@{
    'exam-auth-service' = 8081; 'exam-exam-service' = 8082; 'exam-monitoring-service' = 8083
    'exam-websocket-service' = 8084; 'exam-api-gateway' = 8080
}

# ---------------------------------------------------------------- helpers

function Invoke-Compose {
    $previous = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'   # compose reports progress on stderr
    try { & docker @composeArgs @args 2>&1 | ForEach-Object { "$_" } } finally { $ErrorActionPreference = $previous }
    return
}

function Read-DotEnv([string]$name) {
    $value = $null
    foreach ($line in Get-Content -LiteralPath $envFile) {
        if ($line -match "^\s*(?:export\s+)?$name\s*=(.*)$") {
            # As docker compose reads it: quotes removed, or " #..." cut from an unquoted value
            $value = $Matches[1].Trim()
            if ($value -match '^"(.*)"$' -or $value -match "^'(.*)'$") { $value = $Matches[1] } else { $value = ($value -replace '\s+#.*$', '') }
        }
    }
    return $value
}

function Test-Running([string]$container) {
    return (& docker inspect -f '{{.State.Running}}' $container 2>$null) -eq 'true'
}

function Get-TunnelUrl {
    if (-not (Test-Running $tunnelContainer)) { return $null }
    # Only the log of the current run: a restarted container keeps the addresses of earlier runs
    $startedAt = (& docker inspect -f '{{.State.StartedAt}}' $tunnelContainer 2>$null)
    $log = (cmd /c "docker logs --since $startedAt $tunnelContainer 2>&1") -join "`n"
    $found = [regex]::Matches($log, 'https://[a-z0-9-]+\.trycloudflare\.com')
    if ($found.Count -eq 0) { return $null }
    return $found[$found.Count - 1].Value
}

function Wait-TunnelUrl([int]$seconds = 90) {
    $deadline = (Get-Date).AddSeconds($seconds)
    while ((Get-Date) -lt $deadline) {
        $url = Get-TunnelUrl
        if ($url) { return $url }
        Start-Sleep -Seconds 2
    }
    return $null
}

function Get-MediaEndpoint {
    if (-not (Test-Running 'exam-exam-service')) { return $null }
    # Only this one variable is read (the environment also holds secrets)
    return (& docker exec exam-exam-service printenv MEDIA_S3_PUBLIC_ENDPOINT 2>$null)
}

function Get-Health([string]$container, [int]$port) {
    if (-not (Test-Running $container)) { return 'not running' }
    $body = (& docker exec $container wget -q -T 5 -O - "http://127.0.0.1:$port/actuator/health" 2>$null) -join ''
    if ($body -match '"status"\s*:\s*"UP"') { return 'UP' }
    if ($body) { return 'DOWN' }
    return 'no answer'
}

function Wait-Healthy([int]$seconds = 240) {
    $deadline = (Get-Date).AddSeconds($seconds)
    do {
        $states = [ordered]@{}
        foreach ($name in $services.Keys) { $states[$name] = Get-Health $name $services[$name] }
        if (@($states.Values | Where-Object { $_ -ne 'UP' }).Count -eq 0) { return $states }
        Start-Sleep -Seconds 5
    } while ((Get-Date) -lt $deadline)
    return $states
}

# HTTP request with curl.exe: status, headers and body (no secrets are sent)
function Invoke-Http([string]$method, [string]$url, [string[]]$headers = @(), $body = $null) {
    $headerFile = [IO.Path]::GetTempFileName(); $bodyFile = [IO.Path]::GetTempFileName()
    try {
        $curlArgs = @('-s', '--path-as-is', '-m', '30', '-X', $method, '-D', $headerFile, '-o', $bodyFile, '-w', '%{http_code}')
        foreach ($h in $headers) { $curlArgs += @('-H', $h) }
        if ($null -ne $body) { $curlArgs += @('-H', 'Content-Type: application/json', '--data-binary', $body) }
        $status = [int](& curl.exe @curlArgs $url 2>$null)
        return @{ status = $status; headers = (Get-Content -LiteralPath $headerFile -Raw); body = (Get-Content -LiteralPath $bodyFile -Raw -Encoding UTF8) }
    } catch {
        return @{ status = 0; headers = ''; body = '' }
    } finally {
        Remove-Item -LiteralPath $headerFile, $bodyFile -Force -ErrorAction SilentlyContinue
    }
}

function Get-LastBackup {
    $root = Join-Path $repoRoot 'backups'
    if (-not (Test-Path -LiteralPath $root)) { return $null }
    return Get-ChildItem -LiteralPath $root -Directory | Where-Object { $_.Name -match '^\d{8}-\d{4}$' } |
        Sort-Object Name | Select-Object -Last 1
}

function Fail([string]$message) {
    Write-Host ''
    Write-Host "NOT STARTED: $message"
    exit 1
}

function Write-DemoWarning {
    Write-Host ''
    Write-Host 'DEMO odenis rejimi aciqdir: real pul tutulmur (PAYMENTS_DEMO_MODE=true, docs\DEMO_PAYMENTS.md)' -ForegroundColor Yellow
    Write-Host '  Do not publish a paid exam of real value while the demo is on.' -ForegroundColor Yellow
}

# The sandbox switches that are on, without the two a complete DEMO is meant to have
function Get-UnwantedSandbox($payments) {
    $on = @(Get-SandboxState $envFile)
    if ($payments.DemoComplete) { $on = @($on | Where-Object { $_ -ne 'PAYMENTS_SANDBOX_ALLOWED' -and $_ -ne 'PAYMENTS_SANDBOX_ALLOWED_PUBLIC' }) }
    return , $on
}

function Write-SandboxWarning([string[]]$on) {
    Write-Host ''
    Write-Host "SANDBOX: sandbox canlida aciq ola bilmez; .env-de false edin ($($on -join ', '))" -ForegroundColor Red
    Write-Host '  The test payment page lets anyone take a paid exam without paying. Development stack only (docs\BACKLOG.md).' -ForegroundColor Red
}

# ---------------------------------------------------------------- preflight

$script:gatewayChecked = $false

function Invoke-GatewayCheck {
    $passed = $false
    try {
        & (Join-Path $PSScriptRoot 'check-gateway-production.ps1') 2>&1 | ForEach-Object { "$_" } |
            Where-Object { $_ -match '^(PASS|FAIL)' } | ForEach-Object { Write-Host "Production gateway check: $_" }
        $passed = $LASTEXITCODE -eq 0
    } catch {
        # The check throws when it cannot even sign in (services down, wrong admin settings)
        Write-Host "Production gateway check: $($_.Exception.Message)"
    }
    if (-not $passed) {
        if (Test-Running $tunnelContainer) { Invoke-Compose stop cloudflared edge | Out-Null; Write-Host 'Tunnel and edge stopped again.' }
        Fail 'the production gateway does not route every frontend path (check-gateway-production.ps1)'
    }
}

function Invoke-Preflight {
    Write-Host '== Preflight'
    if (-not (Test-Path -LiteralPath $envFile)) { Fail '.env is missing (copy .env.example, then run scripts\init-public-env.ps1)' }

    # The payment sandbox never goes live by accident (names only, never values): only as a complete DEMO, on purpose
    $payments = Get-PaymentsState $envFile
    if ($payments.DemoRequested) {
        if (-not $payments.DemoComplete) { Fail "PAYMENTS_DEMO_MODE=true also needs $($payments.Missing -join ' and ') in .env (docs\DEMO_PAYMENTS.md)" }
        Write-DemoWarning
    }
    $sandboxOn = Get-UnwantedSandbox $payments
    if ($sandboxOn.Count -gt 0) {
        Write-SandboxWarning $sandboxOn
        if (-not $AllowSandbox) { Fail 'the payment sandbox is on in .env (set it to false; -AllowSandbox only on purpose)' }
        Write-Host '  -AllowSandbox given: going on anyway.' -ForegroundColor Red
    }

    & git -C $repoRoot check-ignore -q -- '.env'
    if ($LASTEXITCODE -ne 0) { Fail '.env is not ignored by git' }
    if (& git -C $repoRoot ls-files -- '.env') { Fail '.env is tracked by git' }

    & (Join-Path $PSScriptRoot 'check-env.ps1') -ComposeFile $baseFile, $publicFile
    if ($LASTEXITCODE -ne 0) { Fail '.env is not complete for the public mode (PUBLIC_MAIL_*: .env.example; secrets: scripts\init-public-env.ps1)' }

    # Strength of the secrets (values are compared, never printed)
    $weak = New-Object System.Collections.Generic.List[string]
    $db = Read-DotEnv 'DB_PASSWORD'
    if ($db.Length -lt 24 -or $db -eq 'postgres') { $weak.Add('DB_PASSWORD (24+ characters)') }
    if ((Read-DotEnv 'REDIS_PASSWORD').Length -lt 24) { $weak.Add('REDIS_PASSWORD (24+ characters)') }
    if ((Read-DotEnv 'INTERNAL_SERVICE_SECRET').Length -lt 32) { $weak.Add('INTERNAL_SERVICE_SECRET (32+ characters)') }
    if ((Read-DotEnv 'JWT_SECRET') -ceq (Read-DotEnv 'OTP_HMAC_SECRET')) { $weak.Add('OTP_HMAC_SECRET (must differ from JWT_SECRET)') }
    foreach ($key in 'MEDIA_S3_ACCESS_KEY', 'MEDIA_S3_SECRET_KEY') { if ((Read-DotEnv $key).Length -lt 16) { $weak.Add("$key (16+ characters)") } }
    if ($weak.Count -gt 0) { Fail "weak or missing secrets: $($weak -join ', '). Run scripts\init-public-env.ps1" }

    # Mail combination (auth-service checks it too, but only after a restart)
    $mailHost = Read-DotEnv 'PUBLIC_MAIL_HOST'
    $startTls = "$(Read-DotEnv 'PUBLIC_MAIL_STARTTLS')"; $ssl = "$(Read-DotEnv 'PUBLIC_MAIL_SSL')"
    if ($startTls -eq 'true' -and $ssl -eq 'true') { Fail 'PUBLIC_MAIL_STARTTLS and PUBLIC_MAIL_SSL are both true (587 + STARTTLS or 465 + SSL)' }
    if ($mailHost -match 'mailpit|localhost|127\.0\.0\.1') { Fail 'PUBLIC_MAIL_HOST must be a real SMTP server, not Mailpit' }
    if ($mailHost -eq 'smtp.gmail.com' -and ((Read-DotEnv 'PUBLIC_MAIL_PASSWORD') -replace '\s', '') -notmatch '^[a-z]{16}$') {
        Fail 'PUBLIC_MAIL_PASSWORD does not look like a Google App Password (16 letters). The account password does not work for SMTP and must not be stored.'
    }
    Write-Host 'Secrets and mail settings: ok'

    # The database must accept DB_PASSWORD (init-public-env.ps1 sets the role password). Checked over
    # the network from another container: inside the database container 127.0.0.1 needs no password.
    if (Test-Running 'exam-postgres') {
        $previous = $env:PGPASSWORD
        try {
            $env:PGPASSWORD = $db
            & docker run --rm --network exam-system_exam-network -e PGPASSWORD postgres:16-alpine psql -h postgres -U postgres -d postgres -tAc 'SELECT 1' *> $null
            if ($LASTEXITCODE -ne 0) { Fail 'the database does not accept DB_PASSWORD of .env: run scripts\init-public-env.ps1' }
        } finally { $env:PGPASSWORD = $previous }
        Write-Host 'Database password: ok'
    }

    & (Join-Path $PSScriptRoot 'check-gateway-routes.ps1') | Select-Object -Last 1 | ForEach-Object { Write-Host $_ }
    if ($LASTEXITCODE -ne 0) { Fail 'gateway routes differ between the profiles (check-gateway-routes.ps1)' }

    if ($SkipGatewayCheck) {
        Write-Host 'Production gateway check: SKIPPED (-SkipGatewayCheck)'
    } elseif ((Get-Health 'exam-auth-service' 8081) -eq 'UP' -and (Get-Health 'exam-exam-service' 8082) -eq 'UP') {
        Invoke-GatewayCheck
        $script:gatewayChecked = $true
    } else {
        # It needs running services: done after the stack is up, before the address is handed out
        Write-Host 'Production gateway check: after the stack is up (services not running now)'
    }

    if ($SkipBackup) {
        $last = Get-LastBackup
        Write-Host "Backup: SKIPPED (-SkipBackup); last backup $(if ($last) { $last.Name } else { 'none' })"
    } else {
        Write-Host 'Backup ...'
        & (Join-Path $PSScriptRoot 'backup.ps1') | Select-Object -Last 1 | ForEach-Object { Write-Host $_ }
        if ($LASTEXITCODE -ne 0) { Fail 'the backup failed (or use -SkipBackup on purpose)' }
    }
}

# ---------------------------------------------------------------- smoke tests

function Invoke-SmokeTests([string]$base) {
    Write-Host "== Smoke tests against $base"
    # A new quick tunnel name can take a moment to resolve
    $deadline = (Get-Date).AddSeconds(90)
    do { $rootPage = Invoke-Http 'GET' "$base/"; if ($rootPage.status -eq 200) { break }; Start-Sleep -Seconds 3 } while ((Get-Date) -lt $deadline)

    $checks = @(
        @{ what = 'GET / (frontend)'; expect = 200; r = $rootPage; extra = { param($r) $r.body -match 'id="root"' } },
        @{ what = 'GET /login (deep link, refresh)'; expect = 200; r = (Invoke-Http 'GET' "$base/login"); extra = { param($r) $r.body -match 'id="root"' } },
        @{ what = 'GET /register (registration page)'; expect = 200; r = (Invoke-Http 'GET' "$base/register"); extra = { param($r) $r.body -match 'id="root"' } },
        @{ what = 'GET /admin/exams (deep link)'; expect = 200; r = (Invoke-Http 'GET' "$base/admin/exams"); extra = $null },
        @{ what = 'GET /api/exams/available without token'; expect = 401; r = (Invoke-Http 'GET' "$base/api/exams/available"); extra = $null },
        @{ what = 'GET /api/auth/me without token'; expect = 401; r = (Invoke-Http 'GET' "$base/api/auth/me"); extra = $null },
        @{ what = 'GET /api/join/NO-SUCH-CODE (public, service answer)'; expect = 404; r = (Invoke-Http 'GET' "$base/api/join/NO-SUCH-CODE"); extra = { param($r) $r.body -match '"success"\s*:\s*false' } },
        @{ what = 'GET /internal/users/stats'; expect = 404; r = (Invoke-Http 'GET' "$base/internal/users/stats"); extra = $null },
        @{ what = 'GET /actuator/health'; expect = 404; r = (Invoke-Http 'GET' "$base/actuator/health"); extra = $null },
        @{ what = 'GET /api/actuator/health'; expect = 404; r = (Invoke-Http 'GET' "$base/api/actuator/health"); extra = $null },
        @{ what = 'GET /api/exams/../../actuator/gateway/routes'; expect = 404; r = (Invoke-Http 'GET' "$base/api/exams/../../actuator/gateway/routes"); extra = $null },
        @{ what = 'GET /exam-media/x without signature'; expect = 404; r = (Invoke-Http 'GET' "$base/exam-media/x"); extra = $null },
        @{ what = 'GET /assets/missing.js (no SPA fallback)'; expect = 404; r = (Invoke-Http 'GET' "$base/assets/missing.js"); extra = $null },
        @{ what = 'API request from another site (Origin)'; expect = 403; r = (Invoke-Http 'GET' "$base/api/exams/available" @('Origin: https://evil.example')); extra = $null }
    )
    $failed = 0
    $rows = foreach ($check in $checks) {
        $ok = $check.r.status -eq $check.expect
        if ($ok -and $check.extra) { $ok = [bool](& $check.extra $check.r) }
        if (-not $ok) { $failed++ }
        [pscustomobject]@{ Result = $(if ($ok) { 'PASS' } else { 'FAIL' }); Expected = $check.expect; Got = $check.r.status; Check = $check.what }
    }
    $headers = "$($rootPage.headers)"
    foreach ($h in 'Content-Security-Policy', 'Strict-Transport-Security', 'X-Content-Type-Options', 'X-Frame-Options', 'Referrer-Policy') {
        $ok = $headers -match "(?im)^$h\s*:"
        if (-not $ok) { $failed++ }
        $rows += [pscustomobject]@{ Result = $(if ($ok) { 'PASS' } else { 'FAIL' }); Expected = 'header'; Got = $(if ($ok) { 'present' } else { 'missing' }); Check = $h }
    }
    $rows | Format-Table -AutoSize | Out-String -Width 160 | Write-Host
    return $failed
}

# ---------------------------------------------------------------- commands

switch ($Command) {
    'start' {
        Invoke-Preflight

        if (-not $SkipFrontendBuild) {
            Write-Host '== Frontend build'
            & (Join-Path $PSScriptRoot 'build-frontend.ps1')
            if ($LASTEXITCODE -ne 0) { Fail 'the frontend build failed' }
        } elseif (-not (Test-Path -LiteralPath (Join-Path $repoRoot 'deploy\public\site\index.html'))) {
            Fail 'deploy\public\site holds no build (run without -SkipFrontendBuild)'
        }

        Write-Host '== Stack'
        if (Test-Running 'exam-mailpit') { & docker stop exam-mailpit | Out-Null; Write-Host 'Mailpit stopped (not part of the public mode)' }

        # A running tunnel that Cloudflare dropped ("Tunnel not found") keeps its dead address:
        # -NewTunnel stops it first so that the start below opens a new one
        if ($NewTunnel -and (Test-Running $tunnelContainer)) {
            Invoke-Compose stop cloudflared | Out-Null
            Write-Host 'Old tunnel stopped (-NewTunnel)'
        }
        # The tunnel first: its address goes into exam-service (presigned media URLs)
        Invoke-Compose up -d --no-deps cloudflared | Select-Object -Last 1 | Write-Host
        $url = Wait-TunnelUrl
        if (-not $url) { Fail 'the tunnel gave no address within 90 s (docker logs exam-cloudflared)' }

        $env:PUBLIC_URL = $url
        $out = Invoke-Compose up -d
        $out | Where-Object { $_ -match 'Error|error' } | ForEach-Object { Write-Host $_ }
        if ($LASTEXITCODE -ne 0) { $out | Select-Object -Last 15 | ForEach-Object { Write-Host $_ }; Fail 'docker compose up failed' }

        Write-Host 'Waiting for the services ...'
        $health = Wait-Healthy
        $health.GetEnumerator() | ForEach-Object { Write-Host ("  {0,-25} {1}" -f $_.Key, $_.Value) }
        if (@($health.Values | Where-Object { $_ -ne 'UP' }).Count -gt 0) {
            Invoke-Compose stop cloudflared edge | Out-Null
            Fail 'not every service is UP (docker logs <container>); tunnel and edge stopped again'
        }
        if (-not $SkipGatewayCheck -and -not $script:gatewayChecked) { Invoke-GatewayCheck }

        New-Item -ItemType Directory -Force -Path $stateDir | Out-Null
        Set-Content -LiteralPath $urlFile -Encoding ascii -Value $url
        Set-Content -LiteralPath $currentUrlFile -Encoding ascii -Value $url
        Remove-Item -LiteralPath $stoppedFile -Force -ErrorAction SilentlyContinue

        $failed = Invoke-SmokeTests $url
        Write-Host ''
        Write-Host "PUBLIC ADDRESS: $url"
        Write-Host '(changes with every start of the tunnel; stop: .\scripts\go-public.ps1 stop)'
        $payments = Get-PaymentsState $envFile
        if ($payments.DemoComplete) { Write-DemoWarning }
        $sandboxOn = Get-UnwantedSandbox $payments
        if ($sandboxOn.Count -gt 0) { Write-SandboxWarning $sandboxOn }
        if ($failed -gt 0) { Write-Host "WARNING: $failed smoke test(s) failed, see above."; exit 1 }
        exit 0
    }

    'stop' {
        Invoke-Compose stop cloudflared edge | Select-Object -Last 2 | Write-Host
        Remove-Item -LiteralPath $urlFile -Force -ErrorAction SilentlyContinue
        # Stopped on purpose: scripts\watch-public.ps1 leaves the site down until the next start
        New-Item -ItemType Directory -Force -Path $stateDir | Out-Null
        Set-Content -LiteralPath $stoppedFile -Encoding ascii -Value (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
        Write-Host 'Tunnel and edge stopped. Database, Redis, storage and services keep running (public settings).'
        Write-Host 'Back to development: .\scripts\up.ps1 (recreates the services with docker-compose.yml, starts Mailpit).'
        exit 0
    }

    'status' {
        $rows = foreach ($name in @('exam-cloudflared', 'exam-edge', 'exam-api-gateway', 'exam-auth-service', 'exam-exam-service',
                'exam-monitoring-service', 'exam-websocket-service', 'exam-postgres', 'exam-redis', 'exam-media-storage', 'exam-mailpit')) {
            $state = (& docker inspect -f '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' $name 2>$null)
            $health = if ($services.Contains($name)) { Get-Health $name $services[$name] } else { '' }
            [pscustomobject]@{ Container = $name; State = $(if ($state) { $state.Trim() } else { 'absent' }); Health = $health }
        }
        $rows | Format-Table -AutoSize | Out-String -Width 160 | Write-Host
        $url = Get-TunnelUrl
        Write-Host "Tunnel address:      $(if ($url) { $url } else { 'none (tunnel not running)' })"
        # There is one tunnel: media links are signed for the address exam-service got at the last start
        # (PUBLIC_URL). The tunnel gets a new address whenever its container starts again (a restart,
        # a reboot, Cloudflare dropping it); exam-service keeps the old one until it is recreated.
        $media = Get-MediaEndpoint
        Write-Host "Media address:       $media"
        if ($url -and $media -and $media -ne $url) {
            Write-Host '  WARNING: exam-service still signs media links (exam images, audio) for an older tunnel address,'
            Write-Host '  so they do not load in the browser; the site itself works. Fix: .\scripts\go-public.ps1 start'
            Write-Host '  (recreates the services with the current tunnel address; the address stays unless the'
            Write-Host '  cloudflared settings changed, then the tunnel and its address are new)'
        }
        $startedWith = if (Test-Path -LiteralPath $urlFile) { (Get-Content -LiteralPath $urlFile -TotalCount 1) } else { $null }
        if ($startedWith -and $url -and $startedWith.Trim() -ne $url) { Write-Host "Last start used:     $($startedWith.Trim())  (the tunnel address changed since)" }
        $local = Invoke-Http 'GET' "$localEdge/"
        Write-Host "Local check ($localEdge): $(if ($local.status) { $local.status } else { 'no answer' })"
        $last = Get-LastBackup
        if ($last) {
            $age = (Get-Date) - $last.CreationTime
            Write-Host ("Last backup:         {0} ({1:N1} hours ago)" -f $last.Name, $age.TotalHours)
        } else { Write-Host 'Last backup:         none' }
        $payments = Get-PaymentsState $envFile
        $paymentsColor = if ($payments.Mode -eq 'DEMO') { 'Yellow' } else { 'Gray' }
        Write-Host "Payments:            $($payments.Mode)$(if ($payments.Mode -eq 'DEMO') { '   (no real money is charged)' })" -ForegroundColor $paymentsColor
        if ($payments.DemoRequested -and -not $payments.DemoComplete) {
            Write-Host "  FAIL: PAYMENTS_DEMO_MODE=true also needs $($payments.Missing -join ' and ')" -ForegroundColor Red
        }
        $sandboxOn = Get-UnwantedSandbox $payments
        if ($sandboxOn.Count -gt 0) {
            Write-Host "Sandbox:             ON   FAIL ($($sandboxOn -join ', ') in .env: set to false)" -ForegroundColor Red
        } elseif ($payments.DemoComplete) { Write-Host 'Sandbox:             DEMO' -ForegroundColor Yellow
        } else { Write-Host 'Sandbox:             OFF' }
        exit 0
    }

    'url' {
        $url = Get-TunnelUrl
        if (-not $url) { Write-Host 'No tunnel is running (.\scripts\go-public.ps1 start).'; exit 1 }
        Write-Output $url
        exit 0
    }
}
