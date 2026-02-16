#!/bin/bash
set -e

# Navigate to project root relative to this script
cd "$(dirname "$0")/.."

echo "Building WSBG Terminal (TEST)..."
export APP_MODE=TEST
mvn clean install -DskipTests

echo "Starting WSBG Terminal UI (TEST MODE - NO DATA/SAVE)..."
mvn -pl ui javafx:run
