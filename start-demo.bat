@echo off
cd /d "%~dp0"
echo Starting backend and frontend in two windows...
start "cosme-backend" cmd /c start-server.bat
timeout /t 4 /nobreak >nul
start "cosme-web" cmd /c start-web.bat
echo Done. Backend: http://127.0.0.1:8082   Frontend: http://localhost:5173
timeout /t 3 /nobreak >nul
