@echo off
rem ============================================================================
rem  ADMIN Mod - root convenience wrapper.
rem  Runs the test suite. Equivalent to: scripts\test.bat
rem ============================================================================
call "%~dp0scripts\test.bat" %*
exit /b %errorlevel%
