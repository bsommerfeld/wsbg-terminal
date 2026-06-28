#!/bin/bash
set -e

# Navigate to project root relative to this script
cd "$(dirname "$0")/.."

echo "Building Launcher..."
mvn clean install -pl updater,launcher -am -DskipTests

# Dev override: make the launcher run THIS repo's setup scripts instead of the
# release-cached copies under <appData>/bin (which the update phase would
# otherwise restore first). Production launches don't set this and are
# unaffected. (Read by EnvironmentSetup.resolveScript.)
export WSBG_SETUP_SCRIPT_DIR="$PWD/.script"

echo "Starting Launcher (setup scripts from $WSBG_SETUP_SCRIPT_DIR)..."
java -jar launcher/target/launcher-1.0.0.jar "$@"
