@echo off
setlocal
cd /d "%~dp0"
title Mobiletools - Local Development

if not exist "%~dp0scripts\start-project.ps1" (
    echo Required script was not found: scripts\start-project.ps1
    pause
    exit /b 1
)
where.exe powershell.exe >nul 2>nul
if errorlevel 1 (
    echo Windows PowerShell was not found. Install or enable Windows PowerShell 5.1.
    pause
    exit /b 1
)

echo Starting Mobiletools local services...
echo Frontend: http://127.0.0.1:5173/
echo Backend health: http://127.0.0.1:8080/api/health
echo.
echo Startup may prompt for MySQL and initial administrator credentials.
echo Existing accounts and database records are not reset by this launcher.
echo.
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\start-project.ps1"
set "EXIT_CODE=%ERRORLEVEL%"

echo.
if not "%EXIT_CODE%"=="0" (
    echo Startup failed. Review the error above and .runtime\project\logs\
) else (
    echo Startup completed. If the browser did not open, visit http://127.0.0.1:5173/
)
echo.
pause
exit /b %EXIT_CODE%
