#!/bin/bash
set -e

# Navigate to project root relative to this script
cd "$(dirname "$0")/.."

echo "Building WSBG Terminal (PROD)..."
export APP_MODE=PROD
mvn clean install -DskipTests

echo "Starting WSBG Terminal UI (PROD MODE)..."
mvn -pl ui javafx:run
