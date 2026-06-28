@echo off
cd /d "%~dp0\.."

echo Building Launcher...
call mvn clean install -pl updater,launcher -am -DskipTests

REM Dev override: make the launcher run THIS repo's setup scripts instead of
REM the release-cached copies under %%LOCALAPPDATA%%\wsbg-terminal\bin (which
REM the update phase would otherwise restore first). Process-local; production
REM launches don't set this. (Read by EnvironmentSetup.resolveScript.)
set "WSBG_SETUP_SCRIPT_DIR=%CD%\.script"

echo Starting Launcher (setup scripts from %WSBG_SETUP_SCRIPT_DIR%)...
java -jar launcher\target\launcher-1.0.0.jar %*
