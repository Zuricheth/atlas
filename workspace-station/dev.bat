@echo off
chcp 65001 >nul
title Workspace Station - Dev Mode

echo ====================================
echo   Workspace Station - 开发模式
echo ====================================
echo.

:: 检查依赖
where node >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [错误] 未检测到 Node.js
    pause
    exit /b 1
)

:: 启动前先同步代码（可选）
echo [信息] 开发模式下启动 Workspace Station...
echo [信息] 自动监听文件变更，修改后重启服务
echo.

:: 使用 nodemon 如果存在，否则用 node
where nodemon >nul 2>&1
if %ERRORLEVEL% equ 0 (
    echo [信息] 检测到 nodemon，启用热重载
    echo.
    nodemon server.js
) else (
    echo [信息] 未安装 nodemon，如需热重载请运行: npm install -g nodemon
    echo.
    node server.js
)

if %ERRORLEVEL% neq 0 (
    echo [错误] 服务启动失败
    pause
    exit /b 1
)

pause
