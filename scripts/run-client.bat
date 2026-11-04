@echo off
rem ============================================================================
rem  ADMIN Mod - run the Fabric client in the development environment.
rem  A graphical desktop is required.
rem ============================================================================
setlocal
cd /d "%~dp0.."

echo [ADMIN] Starting the Minecraft client (development run)...
echo [ADMIN] Press the "]" key in game to open the dashboard.
echo.
call gradlew.bat :client:runClient %*
set EXITCODE=%errorlevel%
endlocal & exit /b %EXITCODE%
