@echo off
cd /d "%~dp0\.."

echo Starting WSBG Terminal...
mvn clean javafx:run -pl ui
