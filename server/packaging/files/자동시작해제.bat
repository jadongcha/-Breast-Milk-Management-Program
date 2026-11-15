@echo off
rem ASCII only on purpose: cmd misreads UTF-8 multibyte text in long batch files. The Korean messages live in tools\*.ps1.
chcp 65001 >nul
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\unauto.ps1"
set "RC=%errorlevel%"
echo.
pause
exit /b %RC%
