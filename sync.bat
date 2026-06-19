@echo off
chcp 65001 >nul
title Atlas 开发同步脚本

:: ══════════════════════════════════════════════════════════
:: sync.bat — Atlas 多机器开发同步脚本
:: 用  法: sync.bat [push|pull|status|help]
:: 默认行为:先 pull 后 push(双向同步)
:: 要  求: 第一个参数
::  - push   :只 push(提交本地 → 远程)
::  - pull   :只 pull(拉取远程 → 本地)
::  - status :只查看差异
::  - help   :显示帮助
:: ══════════════════════════════════════════════════════════

setlocal enabledelayedexpansion

:: 配置区 ── 按需修改 ────────────────────────────────────
set PRIVATE_REMOTE=origin
set PRIVATE_BRANCH=master
set LOG_FILE=sync.log
:: ─────────────────────────────────────────────────────────

set ACTION=%1
if "%ACTION%"=="" set ACTION=pushpull

call :log "═══════════════════════════════════════"
call :log "Atlas 同步开始: %DATE% %TIME%"

if /i "%ACTION%"=="help" goto :help
if /i "%ACTION%"=="status" goto :status
if /i "%ACTION%"=="pull" goto :pull
if /i "%ACTION%"=="push" goto :push
if /i "%ACTION%"=="pushpull" goto :pushpull

:pushpull
  call :log "[1/3] 检查远程状态..."
  call :check_remote
  if errorlevel 1 goto :error

  call :log "[2/3] 拉取远程变更..."
  call :do_pull
  if errorlevel 1 goto :error

  call :log "[3/3] 推送本地变更..."
  call :do_push
  if errorlevel 1 goto :error

  call :log "✓ 同步完成"
  goto :end

:pull
  call :check_remote
  call :do_pull
  goto :end

:push
  call :check_remote
  call :do_push
  goto :end

:status
  call :log "=== 状态检查 ==="
  call :log "远程: %PRIVATE_REMOTE%"
  call :log "分支: %PRIVATE_BRANCH%"
  echo.
  call :log "本地未提交变更:"
  git status --short
  echo.
  call :log "与远程差异:"
  git log ..%PRIVATE_REMOTE%/%PRIVATE_BRANCH% --oneline --left-right 2>nul || call :log "  无差异或第一次同步"
  goto :end

:help
  echo.
  echo ════════════════ Atlas 同步脚本 ════════════════
  echo.
  echo   sync.bat [push^|pull^|status^|help]
  echo.
  echo   默认(无参数): pull + push 双向同步
  echo   push          只推送本地提交到远程
  echo   pull          只拉取远程到本地
  echo   status        查看本地/远程差异
  echo   help          显示这个帮助
  echo.
  echo   配置:
  echo     PRIVATE_REMOTE=origin (私人仓库 remote 名)
  echo     PRIVATE_BRANCH=master
  echo.
  echo   写 commit:
  echo     git add -A
  echo     git commit -m "feat: xxx"
  echo     sync.bat
  echo.
  ═══════════════════════════════════════════════════
  goto :end

:: ────────── 子过程 ──────────

:do_pull
  git pull %PRIVATE_REMOTE% %PRIVATE_BRANCH%
  if errorlevel 1 (
    call :log "✗ Pull 失败: 检查冲突或网络"
    exit /b 1
  )
  call :log "✓ Pull 成功"
  exit /b 0

:do_push
  git push %PRIVATE_REMOTE% %PRIVATE_BRANCH%
  if errorlevel 1 (
    call :log "✗ Push 失败: 检查冲突或网络"
    exit /b 1
  )
  call :log "✓ Push 成功"
  exit /b 0

:check_remote
  git remote -v | findstr "%PRIVATE_REMOTE%" >nul
  if errorlevel 1 (
    call :log "✗ 远程 %PRIVATE_REMOTE% 不存在"
    call :log "  请先: git remote add origin https://github.com/Zuricheth/atlas-private.git"
    exit /b 1
  )
  exit /b 0

:log
  echo [%TIME:~0,8%] %*
  echo [%DATE% %TIME%] %* >> %LOG_FILE%
  exit /b 0

:error
  call :log "✗ 同步失败,请解决冲突后重试"
:end
  call :log "───────────────────────────────────────────"
  echo.
  endlocal
