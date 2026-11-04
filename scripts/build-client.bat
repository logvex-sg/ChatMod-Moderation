@echo off
rem ============================================================================
rem  ADMIN Mod - build only the client jar (ADMIN-Client.jar)
rem ============================================================================
setlocal
cd /d "%~dp0.."

echo [ADMIN] Building ADMIN-Client.jar...
call gradlew.bat buildClient %*
if errorlevel 1 (
    echo [ADMIN] BUILD FAILED.
    exit /b 1
)

if exist "build\libs\ADMIN-Client.jar" (
    echo [ADMIN] OK: build\libs\ADMIN-Client.jar
) else (
    echo [ADMIN] ERROR: ADMIN-Client.jar was not produced.
    exit /b 1
)
endlocal
exit /b 0
