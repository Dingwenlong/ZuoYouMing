@echo off
chcp 65001 >nul 2>&1
setlocal enabledelayedexpansion

echo =======================================================
echo        Library Seat Management System - Launcher       
echo =======================================================
echo.

:: Check admin rights (needed for some operations)
net session >nul 2>&1
if %errorlevel% neq 0 (
    echo [WARN] Not running as administrator. Some features may not work.
    echo.
)

:: ============================================================
:: Step 0: Check Prerequisites
:: ============================================================
echo [0/5] Checking Prerequisites...

:: Check Docker
docker --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Docker is not installed or not in PATH.
    echo Please install Docker Desktop from https://www.docker.com/products/docker-desktop
    pause
    exit /b 1
)
echo       [OK] Docker is installed

:: Check Docker Desktop is running
docker info >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Docker Desktop is not running.
    echo Please start Docker Desktop and try again.
    pause
    exit /b 1
)
echo       [OK] Docker Desktop is running

:: Check Node.js
node --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Node.js is not installed.
    echo Please install Node.js from https://nodejs.org/
    pause
    exit /b 1
)
for /f "tokens=*" %%i in ('node --version') do set NODE_VERSION=%%i
echo       [OK] Node.js %NODE_VERSION% is installed

:: Check Java
java -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java is not installed.
    echo Please install JDK 17 or later.
    pause
    exit /b 1
)
echo       [OK] Java is installed

:: Check Maven
mvn --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Maven is not installed.
    echo Please install Maven from https://maven.apache.org/
    pause
    exit /b 1
)
echo       [OK] Maven is installed

echo.

:: ============================================================
:: Step 1: Start Docker Services
:: ============================================================
echo [1/5] Starting Docker Services...
docker-compose -p library-seat up -d
if errorlevel 1 (
    echo [ERROR] Docker services failed to start.
    echo Please check if ports 3306, 6379 are not in use.
    pause
    exit /b 1
)
echo       [OK] Docker services are up.

:: Wait for MySQL to be ready
echo       Waiting for MySQL to be ready...
set MYSQL_READY=0
for /L %%i in (1,1,30) do (
    docker exec library-mysql mysql -uroot -proot_password -e "SELECT 1" >nul 2>&1
    if not errorlevel 1 (
        set MYSQL_READY=1
        goto :mysql_ready
    )
    timeout /t 1 >nul
)
:mysql_ready
if %MYSQL_READY% equ 0 (
    echo [ERROR] MySQL failed to start within 30 seconds.
    pause
    exit /b 1
)
echo       [OK] MySQL is ready.

:: ============================================================
:: Step 2: Run Database Migrations
:: ============================================================
echo [2/5] Running Database Migrations...

:: Check if migration directory exists
if exist "docker\mysql\migration" (
    for %%f in (docker\mysql\migration\*.sql) do (
        echo       Running migration: %%~nxf
        docker exec -i library-mysql mysql -uroot -proot_password library_seat < "%%f" 2>nul
        if not errorlevel 1 (
            echo       [OK] %%~nxf executed successfully
        ) else (
            echo       [SKIP] %%~nxf may have already been applied
        )
    )
) else (
    echo       No migration scripts found.
)
echo       [OK] Database migrations completed.

:: ============================================================
:: Step 3: Install Frontend Dependencies
:: ============================================================
echo [3/5] Checking Frontend Dependencies...
cd frontend

if not exist "node_modules" (
    echo       Installing frontend dependencies...
    npm install
    if errorlevel 1 (
        echo [ERROR] Failed to install frontend dependencies.
        cd ..
        pause
        exit /b 1
    )
) else (
    echo       [OK] Frontend dependencies already installed.
)
cd ..

:: ============================================================
:: Step 4: Start Backend and Frontend
:: ============================================================
echo [4/5] Starting Backend and Frontend...

:: Start Backend
start "Library Backend" cmd /k "color 0A && call start-backend.bat"

:: Wait for backend to start
echo       Waiting for backend to start...
timeout /t 10 >nul

:: Start Frontend
start "Library Frontend" cmd /k "color 0B && call start-frontend.bat"

:: ============================================================
:: Step 5: Open Browser
:: ============================================================
echo [5/5] Opening Browser...
timeout /t 15 >nul
start http://localhost:3000

echo.
echo =======================================================
echo    System is starting up!
echo    - Backend:  http://localhost:8082/api
echo    - Frontend: http://localhost:3000
echo    - MySQL:    localhost:3306 (root/root_password)
echo    - Redis:    localhost:6379
echo =======================================================
echo.
echo [TIP] Press any key to close this window.
echo       Backend and Frontend will continue running.
echo.
pause
