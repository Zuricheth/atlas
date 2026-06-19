@echo off
setlocal EnableExtensions
set "ATLAS_SERVER_PORT=8080"
set "VITE_DEV_PORT=5173"
set "VITE_DEV_HOST=0.0.0.0"
set "VITE_API_ORIGIN=http://localhost:8080"
cd /d "E:\项目\atlas-backend"
echo Starting Atlas backend > "E:\项目\atlas-backend.log" 2>&1
call "E:\项目\.tools\apache-maven-3.9.16\bin\mvn.cmd" spring-boot:run -Dspring-boot.run.profiles=local >> "E:\项目\atlas-backend.log" 2>&1
