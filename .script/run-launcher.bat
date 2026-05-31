@echo off
cd /d "%~dp0\.."

echo Building Launcher...
call mvn clean install -pl updater,launcher -am -DskipTests

echo Starting Launcher...
java -jar launcher\target\launcher-1.0.0.jar %*
