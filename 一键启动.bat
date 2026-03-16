@echo off
chcp 65001 >nul
cd /d "%~dp0"

title Library Seat System - Quick Start

echo =========================================
echo   Library Seat System - Quick Start
echo =========================================
echo.

REM Check Docker
where docker >nul 2>nul
if %errorlevel% neq 0 (
    echo [ERROR] Docker not installed. Please install Docker Desktop first.
    pause
    exit /b 1
)
echo [OK] Docker is installed

REM Check Java
where java >nul 2>nul
if %errorlevel% neq 0 (
    echo [ERROR] Java not installed. Please install Java 21.
    pause
    exit /b 1
)
echo [OK] Java is installed

REM Check Maven
where mvn >nul 2>nul
if %errorlevel% neq 0 (
    echo [ERROR] Maven not installed.
    pause
    exit /b 1
)
echo [OK] Maven is installed

REM Check Node.js
where node >nul 2>nul
if %errorlevel% neq 0 (
    echo [ERROR] Node.js not installed.
    pause
    exit /b 1
)
echo [OK] Node.js is installed

echo.
echo =========================================
echo   Starting Docker Services...
echo =========================================
echo.

REM Kill processes using required ports
echo Checking for processes using ports 3306, 6379, 18083...

for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":3306 " ^| findstr "LISTENING"') do (
    echo Killing process on port 3306: PID %%a
    taskkill /F /PID %%a >nul 2>nul
)

for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":6379 " ^| findstr "LISTENING"') do (
    echo Killing process on port 6379: PID %%a
    taskkill /F /PID %%a >nul 2>nul
)

for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":18083 " ^| findstr "LISTENING"') do (
    echo Killing process on port 18083: PID %%a
    taskkill /F /PID %%a >nul 2>nul
)

REM Force remove existing containers and networks
echo Cleaning up existing containers and networks...
docker compose down --remove-orphans >nul 2>nul
docker rm -f library-mysql library-redis library-emqx >nul 2>nul
docker network rm zuoyouming_default >nul 2>nul

REM Wait a moment for ports to be released
timeout /t 2 /nobreak >nul

docker compose up -d
if %errorlevel% neq 0 (
    echo [ERROR] Failed to start Docker Compose
    echo.
    echo Please check if ports 3306, 6379, 18083 are available
    pause
    exit /b 1
)
echo [OK] Docker services started

echo.
echo =========================================
echo   Waiting for database to be ready...
echo =========================================
timeout /t 10 /nobreak >nul
echo [OK] Database is ready

echo.
echo =========================================
echo   Installing backend dependencies...
echo =========================================
echo.

cd backend
call mvn clean install -DskipTests -q
if %errorlevel% neq 0 (
    echo [ERROR] Failed to install backend dependencies
    pause
    exit /b 1
)
echo [OK] Backend dependencies installed

echo.
echo =========================================
echo   Installing frontend dependencies...
echo =========================================
echo.

cd ..\frontend
where pnpm >nul 2>nul
if %errorlevel% equ 0 (
    call pnpm install --silent
) else (
    call npm install --silent
)
if %errorlevel% neq 0 (
    echo [ERROR] Failed to install frontend dependencies
    pause
    exit /b 1
)
echo [OK] Frontend dependencies installed

echo.
echo =========================================
echo   SETUP COMPLETE!
echo =========================================
echo.
echo Service URLs:
echo   - Frontend: http://localhost:5173
echo   - Backend API: http://localhost:8080
echo   - Swagger Docs: http://localhost:8080/swagger-ui.html
echo   - EMQX Dashboard: http://localhost:18083
echo.
echo Next Steps:
echo   1. Open a new terminal, go to backend folder, run: mvn spring-boot:run
echo   2. Open another terminal, go to frontend folder, run: pnpm dev (or npm run dev)
echo.
echo =========================================
echo.
pause
