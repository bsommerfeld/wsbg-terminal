@echo off
REM Starts the installed terminal. Ships in the update package under bin\, next
REM to lib\ with every module the terminal needs and Mesa, the OpenGL fallback
REM for machines whose driver has no OpenGL 4.1. Java comes from JAVA_HOME (a
REM launcher points it at its bundled runtime), else from the PATH; it has to be
REM Java 27 or newer. ALL-MODULE-PATH: see launch.sh.
setlocal
set "APP_DIR=%~dp0.."
set "JAVA=java"
if defined JAVA_HOME set "JAVA=%JAVA_HOME%\bin\java"

"%JAVA%" ^
    --enable-native-access=javafx.graphics,de.bsommerfeld.wsbg.orb ^
    -Dde.bsommerfeld.wsbg.orb.mesa="%APP_DIR%\lib" ^
    --module-path "%APP_DIR%\lib" ^
    --add-modules ALL-MODULE-PATH ^
    --module de.bsommerfeld.wsbg.terminal/de.bsommerfeld.wsbg.terminal.TerminalApp ^
    %*
