@echo off
rem ASCII only on purpose: cmd misreads UTF-8 multibyte text in long batch files. The Korean messages live in tools\*.ps1.
rem Usage: start.bat           run in this window (closing it stops the server)
rem        start.bat /background  no window output, logs to data\logs\server.log, returns when the server ends
chcp 65001 >nul
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\start.ps1" %1
set "RC=%errorlevel%"
if /i not "%~1"=="/background" (
  echo.
  pause
)
exit /b %RC%
