@echo off
rem editorial-lab — isolated Reddit -> cluster -> headline harness as a small
rem native window. One text field per thread link, "+" to add more, "Los" to run
rem the whole real pipeline (ClusterEngine + EditorialAgent) and watch the trace.
rem The window stays open between runs so Ollama + models stay warm.
rem
rem Usage: run-test.bat

cd /d "%~dp0\.."

echo Building .lab...
call mvn -q -pl .lab -am clean install -DskipTests || exit /b 1

echo Opening .lab window...
call mvn -q -pl .lab exec:exec
