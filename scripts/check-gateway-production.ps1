<#
.SYNOPSIS
    Starts the gateway image with the production profile next to the development stack and checks
    that every API path the frontend uses is routed, then removes that gateway again.

.DESCRIPTION
    A temporary container "exam-gateway-prod-check" (port 8090 by default) runs the current
    exam-system-api-gateway image with SPRING_PROFILES_ACTIVE=production on the network of the
    development stack, so its lb:// routes reach the development services. The development
    gateway (port 8080) is not touched.

    For each path one request is sent with an admin token (from .env, never printed):
      ROUTED       the service answered (200, 201, 204, or an error of the service: 400, 403, 404
                   with the service's JSON body, 409, 422)
      NOT ROUTED   404/405 from the gateway itself (no route for the path)
      DOWN         503 of the circuit breaker fallback (the route exists, the service is not reached)
      AUTH         401/403 from the gateway (the token was refused)
    CORS: a preflight and a request from an allowed origin must carry Access-Control-Allow-Origin
    and expose X-Total-Count, Content-Disposition and Retry-After; another origin gets no
    Access-Control-Allow-Origin.

    Exit code 0 when every path is ROUTED and CORS is right, 1 otherwise. It never stops or
    removes anything of the stack: only its own temporary container.

.EXAMPLE
    .\scripts\check-gateway-production.ps1
    .\scripts\check-gateway-production.ps1 -Port 8091 -KeepRunning
#>
[CmdletBinding()]
param(
    [int]$Port = 8090,
    [string]$AllowedOrigin = 'http://localhost:3000',
    [string]$Image = 'exam-system-api-gateway',
    [string]$Network = 'exam-system_exam-network',
    # Leave the production gateway running after the check (remove it yourself with docker rm -f)
    [switch]$KeepRunning
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path $PSScriptRoot -Parent
$container = 'exam-gateway-prod-check'
$base = "http://127.0.0.1:$Port"

function Read-DotEnv([string]$name) {
    $line = Get-Content (Join-Path $repoRoot '.env') | Where-Object { $_ -match "^\s*$name\s*=" } | Select-Object -First 1
    if (-not $line) { throw "$name is missing in .env" }
    return ($line -replace "^\s*$name\s*=\s*", '').Trim().Trim('"').Trim("'")
}

function Send([string]$method, [string]$path, $headers = @{}, $body = $null) {
    $request = [Net.HttpWebRequest]::Create("$base$path")
    $request.Method = $method
    $request.Timeout = 30000
    foreach ($key in $headers.Keys) {
        if ($key -eq 'Origin') { $request.Headers.Add('Origin', $headers[$key]) } else { $request.Headers[$key] = $headers[$key] }
    }
    if ($null -ne $body) {
        $bytes = [Text.Encoding]::UTF8.GetBytes(($body | ConvertTo-Json -Depth 5 -Compress))
        $request.ContentType = 'application/json'
        $request.ContentLength = $bytes.Length
        $stream = $request.GetRequestStream(); $stream.Write($bytes, 0, $bytes.Length); $stream.Close()
    }
    try { $response = $request.GetResponse() } catch [Net.WebException] { $response = $_.Exception.Response }
    if (-not $response) { return @{ status = 0; body = ''; headers = @{} } }
    $text = (New-Object IO.StreamReader($response.GetResponseStream())).ReadToEnd()
    $result = @{ status = [int]$response.StatusCode; body = $text; headers = $response.Headers }
    $response.Close()
    return $result
}

function Classify($r) {
    $serviceBody = $r.body -match '"success"\s*:\s*false' -and $r.body -notmatch 'SERVICE_UNAVAILABLE'
    if ($r.status -in 200, 201, 204) { return 'ROUTED' }
    if ($r.status -eq 503 -and $r.body -match 'SERVICE_UNAVAILABLE|temporarily unavailable') { return 'DOWN' }
    if ($serviceBody) { return 'ROUTED' }
    if ($r.status -in 404, 405) { return 'NOT ROUTED' }
    if ($r.status -in 401, 403) { return 'AUTH' }
    return "OTHER $($r.status)"
}

$jwt = Read-DotEnv 'JWT_SECRET'
$adminEmail = Read-DotEnv 'ADMIN_EMAIL'
$adminPassword = Read-DotEnv 'ADMIN_PASSWORD'

cmd /c "docker rm -f $container >nul 2>&1"
# Redis of the public mode (docker-compose.public.yml) has a password, the development one has none
$redisCommand = (& docker inspect -f '{{json .Config.Cmd}}' exam-redis 2>$null) -join ''
$redisSettings = if ($redisCommand -match 'requirepass') { @("REDIS_PASSWORD=$(Read-DotEnv 'REDIS_PASSWORD')") } else { @() }
$envFile = [IO.Path]::GetTempFileName()
try {
    Set-Content $envFile -Encoding ascii -Value (@(
        'SPRING_PROFILES_ACTIVE=production', "JWT_SECRET=$jwt", 'REDIS_HOST=redis', 'REDIS_PORT=6379', 'TZ=UTC',
        "CORS_ALLOWED_ORIGINS=$AllowedOrigin", 'GATEWAY_TRUSTED_PROXIES=127.0.0.1',
        'AUTH_SERVICE_URL=http://auth-service:8081', 'EXAM_SERVICE_URL=http://exam-service:8082') + $redisSettings)
    docker run -d --name $container --network $Network -p "127.0.0.1:${Port}:8080" --env-file $envFile $Image | Out-Null
} finally {
    Remove-Item $envFile -Force
}

$exitCode = 1
try {
    $started = $false
    for ($i = 0; $i -lt 60 -and -not $started; $i++) {
        Start-Sleep -Seconds 2
        $started = (cmd /c "docker logs $container 2>&1" | Select-String -Pattern 'Started ApiGatewayApplication' -Quiet)
        if (cmd /c "docker logs $container 2>&1" | Select-String -Pattern 'APPLICATION FAILED TO START' -Quiet) { break }
    }
    if (-not $started) { cmd /c "docker logs $container 2>&1" | Select-Object -Last 30; throw 'The production gateway did not start' }
    $profile = (cmd /c "docker logs $container 2>&1" | Select-String -Pattern 'profile is active' | Select-Object -First 1).Line
    Write-Output "Production gateway up on port ${Port}: $($profile -replace '^.*INFO\s+\S+\s+-\s+', '')"

    $login = Send 'POST' '/auth/login' @{} @{ email = $adminEmail; password = $adminPassword }
    if ($login.status -ne 200) { throw "Admin sign-in through the production gateway answered $($login.status)" }
    $token = ($login.body | ConvertFrom-Json).data.accessToken
    $auth = @{ Authorization = "Bearer $token" }

    # Every path family of the frontend (services/*.ts) with one harmless request
    $checks = @(
        @('POST', '/auth/login', 'sign-in (done above)'),
        @('GET', '/auth/me', 'auth-service (404 of the service: no such path)'),
        @('GET', '/api/exams/manage?page=0&size=1', 'exams management'),
        @('GET', '/api/exams/available?page=0&size=1', 'participant exam list'),
        @('GET', '/api/exams/my-sessions?page=0&size=1', 'participant sessions'),
        @('POST', '/api/exams/audience/preview', 'audience preview'),
        @('GET', '/api/exams/monitoring/active', 'live monitoring'),
        @('GET', '/api/admin/groups?page=0&size=1', 'groups (admin)'),
        @('GET', '/api/groups?page=0&size=1', 'group names'),
        @('GET', '/api/admin/participants?page=0&size=1', 'participants'),
        @('GET', '/api/admin/participants/summary', 'participant summary'),
        @('GET', '/api/join/NO-SUCH-CODE', 'join page (404 of the service)'),
        @('GET', '/api/analytics/overview?days=7', 'platform analytics'),
        @('GET', '/api/admin/audit?page=0&size=1', 'audit list'),
        @('GET', '/api/grading/pending-count', 'grading badge'),
        @('GET', '/api/grading/pending-exams?page=0&size=1', 'grading overview'),
        @('GET', '/api/media/00000000-0000-0000-0000-000000000000/preview-url', 'media (404 of the service)')
    )
    $rows = foreach ($check in $checks) {
        $r = if ($check[1] -eq '/auth/login') { $login } else {
            $body = if ($check[1] -eq '/api/exams/audience/preview') { @{ groupIds = @() } } else { $null }
            Send $check[0] $check[1] $auth $body
        }
        [pscustomobject]@{ Method = $check[0]; Path = ($check[1] -replace '\?.*$', ''); Status = $r.status; Result = (Classify $r); What = $check[2] }
    }
    $rows | Format-Table -AutoSize | Out-String -Width 160 | Write-Output

    # CORS
    $preflight = Send 'OPTIONS' '/api/exams/manage' @{ Origin = $AllowedOrigin; 'Access-Control-Request-Method' = 'GET'; 'Access-Control-Request-Headers' = 'authorization' }
    $actual = Send 'GET' '/api/exams/manage?page=0&size=1' (@{ Origin = $AllowedOrigin } + $auth)
    $foreign = Send 'GET' '/api/exams/manage?page=0&size=1' (@{ Origin = 'https://not-allowed.example' } + $auth)
    $exposed = "$($actual.headers['Access-Control-Expose-Headers'])"
    $corsOk = $preflight.headers['Access-Control-Allow-Origin'] -eq $AllowedOrigin -and
              $actual.headers['Access-Control-Allow-Origin'] -eq $AllowedOrigin -and
              $exposed -match 'X-Total-Count' -and $exposed -match 'Content-Disposition' -and $exposed -match 'Retry-After' -and
              -not $foreign.headers['Access-Control-Allow-Origin']
    Write-Output "CORS preflight from ${AllowedOrigin}: $($preflight.status), Allow-Origin '$($preflight.headers['Access-Control-Allow-Origin'])'"
    Write-Output "CORS request: Expose-Headers '$exposed'"
    Write-Output "CORS from https://not-allowed.example: status $($foreign.status), Allow-Origin '$($foreign.headers['Access-Control-Allow-Origin'])'"

    $notRouted = @($rows | Where-Object { $_.Result -ne 'ROUTED' })
    if ($notRouted.Count -eq 0 -and $corsOk) {
        Write-Output "PASS: $($rows.Count) paths routed, CORS right"
        $exitCode = 0
    } else {
        Write-Output "FAIL: $($notRouted.Count) path(s) not routed; CORS ok: $corsOk"
    }
} finally {
    if (-not $KeepRunning) {
        cmd /c "docker rm -f $container >nul 2>&1"
        Write-Output "Removed $container (the development gateway on 8080 was not touched)"
    }
}
exit $exitCode
