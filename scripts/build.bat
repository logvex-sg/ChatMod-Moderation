@echo off
rem ============================================================================
rem  ADMIN Mod - build everything (server + client jars)
rem  Requires: JDK 21 on PATH, network access on the first run.
rem ============================================================================
setlocal

cd /d "%~dp0.."

echo [ADMIN] Checking Java...
where java >nul 2>nul
if errorlevel 1 (
    echo [ADMIN] ERROR: java was not found on PATH.
    echo [ADMIN] Install a JDK 21 and add its bin directory to PATH.
    exit /b 1
)

for /f "tokens=3" %%v in ('java -version 2^>^&1 ^| findstr /i "version"') do set JAVA_VER=%%v
echo [ADMIN] Java: %JAVA_VER%
echo %JAVA_VER% | findstr /r "^\"21" >nul
if errorlevel 1 (
    echo [ADMIN] WARNING: this project targets Java 21. Other versions may fail to build.
)

echo [ADMIN] Building...
call gradlew.bat clean build %*
if errorlevel 1 (
    echo [ADMIN] BUILD FAILED.
    exit /b 1
)

echo.
echo [ADMIN] Build finished. Artifacts:
if exist "build\libs\ADMIN-Server.jar" echo   build\libs\ADMIN-Server.jar
if exist "build\libs\ADMIN-Client.jar" echo   build\libs\ADMIN-Client.jar
echo.
endlocal
exit /b 0
