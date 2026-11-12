@echo off
setlocal
cd /d "%~dp0server"

set "JAR=target\cosme-backend-1.0.0.jar"

if not exist "%JAR%" (
    echo [1/2] Building backend, first run may take a minute...
    call mvn -B -q -DskipTests package
)

if not exist "%JAR%" (
    echo [ERROR] Build failed.
    echo         Check that JDK 11+ and Maven 3.6+ are on PATH.
    pause
    exit /b 1
)

echo [2/2] Backend: http://127.0.0.1:8082
echo       Close this window or press Ctrl+C to stop.
java -jar "%JAR%"

pause
