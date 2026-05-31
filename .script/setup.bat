@echo off
echo Starting WSBG Terminal Setup...
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "setup.ps1"
if %errorlevel% neq 0 (
    echo Setup failed with error code %errorlevel%.
    pause
    exit /b %errorlevel%
)
echo Setup completed successfully.
pause
