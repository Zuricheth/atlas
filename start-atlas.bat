@echo off
setlocal EnableExtensions

set "ROOT=%~dp0"
set "CONFIG_FILE=%ROOT%config.env"

if exist "%CONFIG_FILE%" (
  echo Loading local config: %CONFIG_FILE%
  for /f "usebackq eol=# tokens=1,* delims==" %%A in ("%CONFIG_FILE%") do (
    if not "%%A"=="" set "%%A=%%B"
  )
  echo.
)

if not defined ATLAS_SERVER_PORT set "ATLAS_SERVER_PORT=8080"
if not defined VITE_DEV_HOST set "VITE_DEV_HOST=0.0.0.0"
if not defined VITE_DEV_PORT set "VITE_DEV_PORT=5173"
if not defined VITE_API_ORIGIN set "VITE_API_ORIGIN=http://localhost:%ATLAS_SERVER_PORT%"

set "BACKEND_DIR=%ROOT%atlas-backend"
set "FRONTEND_DIR=%ROOT%atlas-frontend"
if not defined MAVEN_CMD (
  if exist "%ROOT%.tools\apache-maven-3.9.16\bin\mvn.cmd" (
    set "MAVEN_CMD=%ROOT%.tools\apache-maven-3.9.16\bin\mvn.cmd"
  ) else (
    set "MAVEN_CMD=%BACKEND_DIR%\mvnw.cmd"
  )
)

set "BACKEND_PORT=%ATLAS_SERVER_PORT%"
set "FRONTEND_PORT=%VITE_DEV_PORT%"

echo ========================================
echo Atlas One-Click Start
echo ========================================
echo.
echo Backend:  http://localhost:%BACKEND_PORT%
echo Frontend: http://localhost:%FRONTEND_PORT%
echo H2 UI:    http://localhost:%BACKEND_PORT%/h2-console
echo.

where java >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Java was not found. Please install JDK 17 first.
  pause
  exit /b 1
)

where node >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Node.js was not found. Please install Node.js first.
  pause
  exit /b 1
)

where npm.cmd >nul 2>nul
if errorlevel 1 (
  echo [ERROR] npm.cmd was not found. Please check your Node.js installation.
  pause
  exit /b 1
)

call :checkPort %BACKEND_PORT%
if errorlevel 1 (
  pause
  exit /b 1
)

call :checkPort %FRONTEND_PORT%
if errorlevel 1 (
  pause
  exit /b 1
)

echo [1/2] Starting Spring Boot backend with local H2 profile...
start "Atlas Backend :%BACKEND_PORT%" /D "%BACKEND_DIR%" cmd /k ""%MAVEN_CMD%" spring-boot:run -Dspring-boot.run.profiles=local"

echo [2/2] Starting Vite frontend...
start "Atlas Frontend :%FRONTEND_PORT%" /D "%FRONTEND_DIR%" cmd /k "npm.cmd run dev -- --host %VITE_DEV_HOST% --port %FRONTEND_PORT%"

echo.
echo ========================================
echo Start commands were sent.
echo.
echo Open:
echo   Frontend: http://localhost:%FRONTEND_PORT%
echo   Backend:  http://localhost:%BACKEND_PORT%/api
echo   H2 UI:    http://localhost:%BACKEND_PORT%/h2-console
echo.
echo H2 Console:
echo   JDBC URL: jdbc:h2:file:./data/atlas
echo   User:     sa
echo   Password: empty
echo.
echo Stop:
echo   Double click stop-atlas.bat
echo ========================================
echo.

endlocal
exit /b 0

:checkPort
set "PORT=%~1"
for /f "tokens=5" %%A in ('netstat -ano ^| findstr ":%PORT%" ^| findstr "LISTENING"') do (
  echo [ERROR] Port %PORT% is already in use. PID=%%A
  echo         Run stop-atlas.bat first, or free this port manually.
  exit /b 1
)
exit /b 0
