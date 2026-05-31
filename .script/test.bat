@echo off
cd /d "%~dp0\.."

set MODULES=core,database,reddit,agent,updater,launcher

rem Optional: single module via argument, e.g. test.bat agent
if not "%~1"=="" set MODULES=%~1

echo Running tests for: %MODULES%
echo ----------------------------------------

call mvn test -pl "%MODULES%" --no-transfer-progress

echo ----------------------------------------
echo Done.
