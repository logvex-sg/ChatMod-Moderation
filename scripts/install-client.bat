@echo off
rem ============================================================================
rem  ADMIN Mod - install ADMIN-Client.jar into a Minecraft launcher profile.
rem
rem  Usage:
rem     install-client.bat "C:\Users\you\AppData\Roaming\.minecraft"
rem
rem  The jar is copied into "<game-dir>\mods". Fabric Loader and Fabric API for
rem  Minecraft 1.21.1 must already be installed in the launcher profile.
rem ============================================================================
setlocal
cd /d "%~dp0.."

set GAMEDIR=%~1
if "%GAMEDIR%"=="" (
    echo [ADMIN] Usage: install-client.bat "%%APPDATA%%\.minecraft"
    exit /b 1
)

if not exist "%GAMEDIR%" (
    echo [ADMIN] ERROR: the game directory does not exist: %GAMEDIR%
    exit /b 1
)

if not exist "build\libs\ADMIN-Client.jar" (
    echo [ADMIN] ADMIN-Client.jar was not found; building it first...
    call gradlew.bat buildClient
    if errorlevel 1 exit /b 1
)

if not exist "%GAMEDIR%\mods" (
    echo [ADMIN] Creating %GAMEDIR%\mods
    mkdir "%GAMEDIR%\mods"
)

copy /y "build\libs\ADMIN-Client.jar" "%GAMEDIR%\mods\ADMIN-Client.jar" >nul
if errorlevel 1 (
    echo [ADMIN] ERROR: could not copy the jar. Is Minecraft running?
    exit /b 1
)

echo [ADMIN] Installed ADMIN-Client.jar into %GAMEDIR%\mods
echo [ADMIN] Start Minecraft with the Fabric 1.21.1 profile and press "]" in game.
endlocal
exit /b 0
