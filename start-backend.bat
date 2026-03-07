@echo off
chcp 65001 >nul
setlocal

cd backend

echo ==========================================
echo      Starting Backend (Spring Boot)
echo ==========================================
echo.

:: Check if pom.xml exists
if not exist "pom.xml" (
    echo [ERROR] pom.xml not found in backend directory.
    echo Please make sure you are running this script from the project root.
    pause
    exit /b 1
)

:: Check if Maven is available
mvn --version >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Maven is not installed or not in PATH.
    echo Please install Maven from https://maven.apache.org/
    pause
    exit /b 1
)

:: Check if Java is available
java -version >nul 2>&1
if %errorlevel% neq 0 (
    echo [ERROR] Java is not installed.
    echo Please install JDK 17 or later.
    pause
    exit /b 1
)

:: Check if MySQL is accessible (optional, just a warning)
echo [INFO] Checking database connection...
docker exec library-mysql mysql -uroot -proot_password -e "SELECT 1" >nul 2>&1
if %errorlevel% neq 0 (
    echo [WARN] Cannot connect to MySQL. Make sure Docker services are running.
    echo        Run 'run-system.bat' first to start all services.
    echo.
    choice /C YN /M "Continue anyway? (Y/N)"
    if errorlevel 2 exit /b 1
)

echo.
echo [INFO] Starting Spring Boot application...
echo [INFO] Backend will be available at: http://localhost:8080/api
echo [INFO] Press Ctrl+C to stop the server.
echo.

:: Run Spring Boot
mvn spring-boot:run

if %errorlevel% neq 0 (
    echo.
    echo [ERROR] Backend failed to start.
    echo Please check the error messages above.
    pause
    exit /b 1
)
