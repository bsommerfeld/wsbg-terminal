#!/bin/bash
set -e

# Navigate to project root relative to this script
cd "$(dirname "$0")/.."
source .script/jdk.sh

echo "Starting WSBG Terminal (JDK $("$JAVA_HOME/bin/java" -version 2>&1 | head -1))..."
# The app needs the signals library installed: javafx:run runs on the app
# module alone, outside the reactor.
mvn -q install -DskipTests
mvn -q -f fx-terminal javafx:run
