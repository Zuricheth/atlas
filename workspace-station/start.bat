@echo off
chcp 65001 >nul
title Workspace Station

echo ====================================
echo   Workspace Station - 项目工作台
echo ====================================
echo.

:: 检查依赖
where node >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [错误] 未检测到 Node.js，请先安装 Node.js
    pause
    exit /b 1
)

:: 启动服务
echo [信息] 正在启动 Workspace Station...
echo [信息] 默认访问地址: http://127.0.0.1:8765
echo.

node server.js

if %ERRORLEVEL% neq 0 (
    echo [错误] 服务启动失败，请检查端口 8765 是否被占用
    pause
    exit /b 1
)

pause
