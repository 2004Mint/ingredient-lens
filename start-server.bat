@echo off
setlocal
cd /d "%~dp0server"

rem Default: run from source with Maven - no packaging step, no jar lock.
rem Usage: start-server.bat        -> mvn spring-boot:run
rem        start-server.bat jar    -> build (if needed) and run the jar

if /i "%~1"=="jar" goto :runjar

echo [1/1] Starting backend from source: mvn spring-boot:run
echo       Backend: http://127.0.0.1:8082
echo       Close this window or press Ctrl+C to stop.
call mvn -B spring-boot:run
goto :done

:runjar
set "JAR=target\cosme-backend-1.0.0.jar"

if not exist "%JAR%" (
    echo [1/2] Building backend, first run may take a minute...
    call mvn -B -q -DskipTests package
)

if not exist "%JAR%" (
    echo [ERROR] Build failed.
    echo         Check that JDK 11+ and Maven 3.6+ are on PATH.
    echo         If the jar exists but cannot be replaced, stop the running backend first.
    pause
    exit /b 1
)

echo [2/2] Backend: http://127.0.0.1:8082
echo       Close this window or press Ctrl+C to stop.
java -jar "%JAR%"
goto :done

:done
pause
