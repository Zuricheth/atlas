@echo off
setlocal EnableExtensions
set "ATLAS_SERVER_PORT=8080"
set "VITE_DEV_PORT=5173"
set "VITE_DEV_HOST=0.0.0.0"
set "VITE_API_ORIGIN=http://localhost:8080"
cd /d "E:\项目\atlas-frontend"
echo Starting Atlas frontend > "E:\项目\atlas-frontend.log" 2>&1
call npm.cmd run dev -- --host 0.0.0.0 --port 5173 >> "E:\项目\atlas-frontend.log" 2>&1
