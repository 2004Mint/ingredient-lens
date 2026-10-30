@echo off
chcp 65001 >nul
cd /d "%~dp0web"

if not exist "node_modules" (
    echo 首次运行，正在安装前端依赖...
    call npm install --no-fund --no-audit
)

echo 启动前端  http://localhost:5173
echo 请先确保后端已在 8082 端口运行（双击 start-server.bat）。
start "" http://localhost:5173
call npm run dev
pause
