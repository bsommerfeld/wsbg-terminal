#!/usr/bin/env bash
# Plays the complete first-run launcher flow with every side effect faked -
# nothing is downloaded, installed or started. For screen recordings.
#
#   ./.script/launcher-demo.sh                 # normal pace
#   ./.script/launcher-demo.sh -Ddemo.pace=0.5 # twice as fast
#   ./.script/launcher-demo.sh -Ddemo.ram=8    # fake an 8 GB machine
set -euo pipefail

cd "$(dirname "$0")/.."

mvn -q -pl launcher -am test-compile -DskipTests

CP_FILE="$(mktemp)"
mvn -q -pl launcher dependency:build-classpath -Dmdep.outputFile="$CP_FILE"

java -cp "launcher/target/test-classes:launcher/target/classes:$(cat "$CP_FILE")" \
     "$@" de.bsommerfeld.updater.launcher.LauncherDemo
