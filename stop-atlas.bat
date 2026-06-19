@echo off
setlocal EnableExtensions

set "ROOT=%~dp0"
set "CONFIG_FILE=%ROOT%config.env"

if exist "%CONFIG_FILE%" (
  for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%CONFIG_FILE%") do (
    if not "%%A"=="" set "%%A=%%B"
  )
)

if not defined ATLAS_SERVER_PORT set "ATLAS_SERVER_PORT=8080"
if not defined VITE_DEV_PORT set "VITE_DEV_PORT=5173"

echo ========================================
echo Atlas One-Click Stop
echo ========================================
echo.
echo Releasing processes on ports %ATLAS_SERVER_PORT% and %VITE_DEV_PORT%...
echo.

set "FOUND=0"
for %%P in (%ATLAS_SERVER_PORT% %VITE_DEV_PORT%) do (
  for /f "tokens=5" %%A in ('netstat -ano ^| findstr ":%%P" ^| findstr "LISTENING"') do (
    set "FOUND=1"
    echo Stopping port %%P process PID=%%A
    taskkill /F /PID %%A >nul 2>nul
  )
)

echo.
if "%FOUND%"=="0" (
  echo No Atlas processes were found on ports 8080 or 5173.
) else (
  echo Stop commands were sent.
)
echo.
echo If any console windows remain, close windows titled:
echo   Atlas Backend :%ATLAS_SERVER_PORT%
echo   Atlas Frontend :%VITE_DEV_PORT%
echo.
pause
endlocal
