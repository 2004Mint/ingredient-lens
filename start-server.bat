@echo off
chcp 65001 >nul
cd /d "%~dp0server"

if not exist "target\cosme-backend-1.0.0.jar" (
    echo [1/2] 首次运行，正在编译后端...
    call mvn -B -q -DskipTests package
    if not exist "target\cosme-backend-1.0.0.jar" (
        echo 编译失败，请确认已安装 JDK 11+ 与 Maven。
        pause
        exit /b 1
    )
)

echo [2/2] 启动后端  http://127.0.0.1:8082
echo 关闭此窗口即可停止服务。
java -jar target\cosme-backend-1.0.0.jar
pause
