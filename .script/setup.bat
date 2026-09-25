@echo off
echo Starting WSBG Terminal Setup...
cd /d "%~dp0"
powershell -NoProfile -ExecutionPolicy Bypass -File "setup.ps1"
REM Exit code 10 = finished with warnings (degraded steps), not a failure.
REM Keep in sync with setup.ps1 / EnvironmentSetup.EXIT_WITH_WARNINGS.
if %errorlevel% equ 10 (
    echo Setup completed with warnings - see output above.
    pause
    exit /b 0
)
if %errorlevel% neq 0 (
    echo Setup failed with error code %errorlevel%.
    pause
    exit /b %errorlevel%
)
echo Setup completed successfully.
pause
