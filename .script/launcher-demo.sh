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

# The sibling modules come FIRST, ahead of the resolved jars. build-classpath
# resolves 'updater' out of ~/.m2, i.e. whatever the last 'mvn install' left
# there - so a class added since (or changed since) is missing or stale while
# the launcher's own classes are freshly compiled. That mix fails at runtime
# with a NoClassDefFoundError deep inside a screen, which reads like a bug in
# the screen. A demo must show the working tree, so the working tree wins.
java -cp "launcher/target/test-classes:launcher/target/classes:updater/target/classes:$(cat "$CP_FILE")" \
     "$@" de.bsommerfeld.updater.launcher.LauncherDemo
