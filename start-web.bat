@echo off
setlocal
cd /d "%~dp0web"

if not exist "node_modules" (
    echo [1/2] Installing frontend dependencies...
    call npm install --no-fund --no-audit
)

if not exist "node_modules" (
    echo [ERROR] npm install failed.
    echo         Check that Node 18+ is on PATH.
    pause
    exit /b 1
)

echo [2/2] Frontend: http://localhost:5173
echo       Make sure the backend is already running on port 8082.
start "" http://localhost:5173
call npm run dev -- --strictPort

pause
