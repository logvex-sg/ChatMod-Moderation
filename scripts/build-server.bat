@echo off
rem ============================================================================
rem  ADMIN Mod - build only the dedicated-server jar (ADMIN-Server.jar)
rem ============================================================================
setlocal
cd /d "%~dp0.."

echo [ADMIN] Building ADMIN-Server.jar...
call gradlew.bat buildServer %*
if errorlevel 1 (
    echo [ADMIN] BUILD FAILED.
    exit /b 1
)

if exist "build\libs\ADMIN-Server.jar" (
    echo [ADMIN] OK: build\libs\ADMIN-Server.jar
) else (
    echo [ADMIN] ERROR: ADMIN-Server.jar was not produced.
    exit /b 1
)
endlocal
exit /b 0
