@echo off
setlocal
cd /d "%~dp0"
title Mobiletools - Stop Local Services

if not exist "%~dp0scripts\stop-project.ps1" (
    echo Required script was not found: scripts\stop-project.ps1
    pause
    exit /b 1
)
where.exe powershell.exe >nul 2>nul
if errorlevel 1 (
    echo Windows PowerShell was not found. Install or enable Windows PowerShell 5.1.
    pause
    exit /b 1
)

echo Stopping Mobiletools services...
echo.
powershell.exe -NoLogo -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\stop-project.ps1"
set "EXIT_CODE=%ERRORLEVEL%"

echo.
if not "%EXIT_CODE%"=="0" (
    echo Shutdown was incomplete. Review the PowerShell output and the process manifest.
) else (
    echo Project shutdown completed.
)
echo.
pause
exit /b %EXIT_CODE%
