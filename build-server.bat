@echo off
rem ============================================================================
rem  ADMIN Mod - root convenience wrapper.
rem  Builds only ADMIN-Server.jar. Equivalent to: scripts\build-server.bat
rem ============================================================================
call "%~dp0scripts\build-server.bat" %*
exit /b %errorlevel%
