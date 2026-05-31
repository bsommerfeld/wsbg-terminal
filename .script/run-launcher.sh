#!/bin/bash
set -e

# Navigate to project root relative to this script
cd "$(dirname "$0")/.."

echo "Building Launcher..."
mvn clean install -pl updater,launcher -am -DskipTests

echo "Starting Launcher..."
java -jar launcher/target/launcher-1.0.0.jar "$@"
