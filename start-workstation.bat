@echo off
setlocal EnableExtensions

set "ROOT=%~dp0"
set "STATION_DIR=%ROOT%workspace-station"
set "WORKSTATION_PORT=8765"

echo ========================================
echo Workspace Station
echo ========================================
echo.
echo URL: http://127.0.0.1:%WORKSTATION_PORT%/
echo.

where node >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Node.js was not found. Please install Node.js first.
  pause
  exit /b 1
)

cd /d "%STATION_DIR%"
start "" /b cmd /c "timeout /t 1 /nobreak >nul & start http://127.0.0.1:%WORKSTATION_PORT%/"
node server.js

endlocal
