@echo off
chcp 65001 >nul
setlocal

cd frontend

echo ==========================================
echo      Starting Frontend (Vue 3)
echo ==========================================
echo.

:: Check if package.json exists
if not exist "package.json" (
    echo [ERROR] package.json not found in frontend directory.
    echo Please make sure you are running this script from the project root.
    pause
    exit /b 1
)

:: Check if Node.js is available
node --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Node.js is not installed.
    echo Please install Node.js from https://nodejs.org/
    pause
    exit /b 1
)

:: Check if npm is available
npm --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] npm is not installed.
    echo Please install Node.js from https://nodejs.org/
    pause
    exit /b 1
)

:: Check if node_modules exists, if not install dependencies
if not exist "node_modules" (
    echo [INFO] Installing dependencies...
    npm install
    if %errorlevel% neq 0 (
        echo [ERROR] Failed to install dependencies.
        pause
        exit /b 1
    )
    echo [OK] Dependencies installed.
    echo.
)

:: Check if backend is running
echo [INFO] Checking if backend is running...
curl -s http://localhost:8080/api/health >nul 2>&1
if %errorlevel% neq 0 (
    echo [WARN] Backend may not be running at http://localhost:8080
    echo        Make sure to start the backend first.
    echo.
)

echo [INFO] Starting Vue development server...
echo [INFO] Frontend will be available at: http://localhost:3000
echo [INFO] Press Ctrl+C to stop the server.
echo.

:: Run Vue dev server
npm run dev

if %errorlevel% neq 0 (
    echo.
    echo [ERROR] Frontend failed to start.
    echo Please check the error messages above.
    pause
    exit /b 1
)
