@echo off
chcp 65001 >nul
cd /d "%~dp0"

title Library Seat System - Stop

echo =========================================
echo   Stopping Library Seat System
echo =========================================
echo.

echo Stopping Docker services...
echo.

docker compose down
if %errorlevel% equ 0 (
    echo [OK] Docker services stopped
) else (
    echo [WARN] Some issues while stopping services
)

echo.
echo =========================================
echo   STOP COMPLETE!
echo =========================================
echo.
pause
