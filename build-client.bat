@echo off
rem ============================================================================
rem  ADMIN Mod - root convenience wrapper.
rem  Builds only ADMIN-Client.jar. Equivalent to: scripts\build-client.bat
rem ============================================================================
call "%~dp0scripts\build-client.bat" %*
exit /b %errorlevel%
