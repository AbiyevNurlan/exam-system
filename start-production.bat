@echo off
REM =====================================================
REM EXAM PORTAL - PRODUCTION START SCRIPT
REM For Windows
REM =====================================================

echo ============================================
echo    EXAM PORTAL - PRODUCTION DEPLOYMENT
echo    Optimized for 100K Concurrent Users
echo ============================================

REM Check Docker
docker --version >nul 2>&1
if %errorlevel% neq 0 (
    echo ERROR: Docker is not installed or not running
    exit /b 1
)

REM Set environment variables
REM Secrets are never hardcoded: they come from the environment or the git-ignored .env file (see .env.example)
for %%V in (JWT_SECRET DB_PASSWORD GRAFANA_PASSWORD) do (
    if not defined %%V if exist .env (
        for /f "usebackq eol=# tokens=1,* delims==" %%A in (".env") do (
            if "%%A"=="%%V" if not "%%B"=="CHANGE_ME" set "%%V=%%B"
        )
    )
    if not defined %%V (
        echo ERROR: %%V is not set.
        echo        Copy .env.example to .env and replace every CHANGE_ME value:
        echo        JWT_SECRET, DB_PASSWORD, GRAFANA_PASSWORD.
        exit /b 1
    )
)

REM Build and start
echo.
echo [1/4] Building Java applications...
call mvnw.cmd clean package -DskipTests -q

echo.
echo [2/4] Building Docker images...
docker-compose -f docker-compose.production.yml build --parallel

echo.
echo [3/4] Starting infrastructure (PostgreSQL, Redis)...
docker-compose -f docker-compose.production.yml up -d postgres-primary redis-master
timeout /t 30 /nobreak >nul

echo.
echo [4/4] Starting all services...
docker-compose -f docker-compose.production.yml up -d

echo.
echo ============================================
echo    DEPLOYMENT COMPLETE
echo ============================================
echo.
echo Services:
echo   - NGINX Load Balancer: http://localhost:80
echo   - API Gateway 1:       http://localhost:8080
echo   - API Gateway 2:       http://localhost:8081
echo   - Auth Service x2:     Internal
echo   - Exam Service x3:     Internal
echo   - PostgreSQL:          localhost:5432
echo   - Redis:               localhost:6379
echo   - Prometheus:          http://localhost:9090
echo   - Grafana:             http://localhost:3000
echo.
echo Monitoring:
echo   - Health: curl http://localhost/health
echo   - Metrics: curl http://localhost:8080/actuator/prometheus
echo.
echo ============================================

pause
