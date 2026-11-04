@echo off
rem ============================================================================
rem  ADMIN Mod - run a dedicated Fabric server in the development environment.
rem  The first run downloads Minecraft and Fabric; later runs are fast.
rem ============================================================================
setlocal
cd /d "%~dp0.."

echo [ADMIN] Starting the dedicated server (development run)...
echo [ADMIN] Stop it with the "stop" command in this console.
echo.
call gradlew.bat :server:runServer %*
set EXITCODE=%errorlevel%
endlocal & exit /b %EXITCODE%
