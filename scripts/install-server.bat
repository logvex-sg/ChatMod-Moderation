@echo off
rem ============================================================================
rem  ADMIN Mod - install ADMIN-Server.jar into an existing Fabric server.
rem
rem  Usage:
rem     install-server.bat "C:\path\to\fabric-server"
rem
rem  The target directory must already contain a Fabric server (fabric-server*.jar)
rem  or at least a "mods" directory. The jar is copied into "mods\".
rem ============================================================================
setlocal
cd /d "%~dp0.."

set TARGET=%~1
if "%TARGET%"=="" (
    echo [ADMIN] Usage: install-server.bat "C:\path\to\fabric-server"
    exit /b 1
)

if not exist "%TARGET%" (
    echo [ADMIN] ERROR: the target directory does not exist: %TARGET%
    exit /b 1
)

if not exist "build\libs\ADMIN-Server.jar" (
    echo [ADMIN] ADMIN-Server.jar was not found; building it first...
    call gradlew.bat buildServer
    if errorlevel 1 exit /b 1
)

if not exist "%TARGET%\mods" (
    echo [ADMIN] Creating %TARGET%\mods
    mkdir "%TARGET%\mods"
)

copy /y "build\libs\ADMIN-Server.jar" "%TARGET%\mods\ADMIN-Server.jar" >nul
if errorlevel 1 (
    echo [ADMIN] ERROR: could not copy the jar. Is the server running?
    exit /b 1
)

echo [ADMIN] Installed ADMIN-Server.jar into %TARGET%\mods
echo [ADMIN] Edit %TARGET%\config\admin-mod.json to configure the bridge, then restart the server.
endlocal
exit /b 0
