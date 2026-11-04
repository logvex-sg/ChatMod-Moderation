@echo off
rem ============================================================================
rem  ADMIN Mod - root convenience wrapper.
rem  Builds both distributables. Equivalent to: scripts\build.bat
rem ============================================================================
call "%~dp0scripts\build.bat" %*
exit /b %errorlevel%
