@echo off
echo Stopping all Java services...
taskkill /F /FI "WINDOWTITLE eq Auth Service*" 2>nul
taskkill /F /FI "WINDOWTITLE eq Exam Service*" 2>nul
taskkill /F /FI "WINDOWTITLE eq API Gateway*" 2>nul
taskkill /F /FI "WINDOWTITLE eq Monitoring Service*" 2>nul
taskkill /F /FI "WINDOWTITLE eq WebSocket Service*" 2>nul
echo All services stopped!
pause
