@echo off
cd /d "%~dp0\.."

REM The project compiles to release 25, so it needs a JDK 25+. If the machine's
REM default JAVA_HOME points at an older JDK (a lingering jdk-24 is common),
REM `mvn clean install` fails with "Releaseversion 25 nicht unterstuetzt" and the
REM launch step then just raises any already-running instance — looking like it
REM "started" while actually running stale/old code.
REM
REM Pick the newest jdk-* under Program Files\Java so the build uses a capable
REM compiler regardless of the global JAVA_HOME. This `set` is process-local: it
REM does NOT change the machine's JAVA_HOME or affect other projects. If nothing
REM is found, the existing JAVA_HOME/PATH is used unchanged.
for /f "delims=" %%d in ('dir /b /ad /o-n "C:\Program Files\Java\jdk-2*" 2^>nul') do (
    if exist "C:\Program Files\Java\%%d\bin\javac.exe" (
        set "JAVA_HOME=C:\Program Files\Java\%%d"
        goto :jdk_done
    )
)
:jdk_done
if defined JAVA_HOME echo Using JAVA_HOME=%JAVA_HOME%

echo Building WSBG Terminal (PROD)...
set APP_MODE=PROD
call mvn clean install -DskipTests

echo Starting WSBG Terminal UI (PROD MODE)...
call mvn -pl terminal exec:exec
