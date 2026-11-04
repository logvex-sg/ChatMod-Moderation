@echo off
rem ============================================================================
rem  ADMIN Mod - run the automated test suite
rem ============================================================================
setlocal
cd /d "%~dp0.."

echo [ADMIN] Running tests...
call gradlew.bat test %*
if errorlevel 1 (
    echo [ADMIN] TESTS FAILED.
    exit /b 1
)
echo [ADMIN] All tests passed.
endlocal
exit /b 0
