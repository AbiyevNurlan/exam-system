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
set DB_PASSWORD=postgres_secure_2026
set JWT_SECRET=production-jwt-secret-key-minimum-256-bits-secure-random-string
set GRAFANA_PASSWORD=admin123

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
