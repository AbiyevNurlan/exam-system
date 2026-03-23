@echo off
echo ========================================
echo   EXAM SYSTEM - Start All Services
echo ========================================
echo.

cd /d F:\exam-system

echo [1/5] Starting Auth Service (Port 8081)...
start "Auth Service" cmd /k "java -jar auth-service\target\auth-service-1.0.0-SNAPSHOT.jar"
timeout /t 5 /nobreak >nul

echo [2/5] Starting Exam Service (Port 8082)...
start "Exam Service" cmd /k "java -jar exam-service\target\exam-service-1.0.0-SNAPSHOT.jar"
timeout /t 5 /nobreak >nul

echo [3/5] Starting API Gateway (Port 8080)...
start "API Gateway" cmd /k "java -jar api-gateway\target\api-gateway-1.0.0-SNAPSHOT.jar"
timeout /t 5 /nobreak >nul

echo [4/5] Starting Monitoring Service (Port 8083)...
start "Monitoring Service" cmd /k "java -jar monitoring-service\target\monitoring-service-1.0.0-SNAPSHOT.jar"
timeout /t 5 /nobreak >nul

echo [5/5] Starting WebSocket Service (Port 8084)...
start "WebSocket Service" cmd /k "java -jar websocket-service\target\websocket-service-1.0.0-SNAPSHOT.jar"

echo.
echo ========================================
echo   All Services Started!
echo   Check: http://localhost:8080/actuator/health
echo ========================================
pause
