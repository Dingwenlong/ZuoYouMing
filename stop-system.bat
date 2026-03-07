@echo off
chcp 65001 >nul
setlocal

echo =======================================================
echo        Library Seat Management System - Stopper       
echo =======================================================
echo.

echo [INFO] Stopping all services...
echo.

:: Kill backend process (Spring Boot on port 8080)
echo [1/3] Stopping Backend (port 8080)...
for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":8080" ^| findstr "LISTENING"') do (
    taskkill /PID %%a /F >nul 2>&1
    if !errorlevel! equ 0 (
        echo       [OK] Backend stopped.
    )
)
if not defined a echo       [SKIP] Backend is not running.

:: Kill frontend process (Vite on port 3000)
echo [2/3] Stopping Frontend (port 3000)...
for /f "tokens=5" %%a in ('netstat -aon ^| findstr ":3000" ^| findstr "LISTENING"') do (
    taskkill /PID %%a /F >nul 2>&1
    if !errorlevel! equ 0 (
        echo       [OK] Frontend stopped.
    )
)
if not defined a echo       [SKIP] Frontend is not running.

:: Stop Docker services
echo [3/3] Stopping Docker services...
docker-compose -p library-seat down
if %errorlevel% equ 0 (
    echo       [OK] Docker services stopped.
) else (
    echo       [SKIP] Docker services may not be running.
)

echo.
echo =======================================================
echo    All services have been stopped.
echo =======================================================
echo.
pause
