@echo off
cd /d "%~dp0\.."

echo Building WSBG Terminal (PROD)...
set APP_MODE=PROD
call mvn clean install -DskipTests

echo Starting WSBG Terminal UI (PROD MODE)...
call mvn -pl terminal javafx:run
