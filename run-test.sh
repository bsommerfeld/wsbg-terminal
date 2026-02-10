#!/bin/bash
set -e

echo "Building WSBG Terminal (TEST)..."
export APP_MODE=TEST
mvn clean install -DskipTests

echo "Starting WSBG Terminal UI (TEST MODE - NO DATA/SAVE)..."
mvn -pl ui javafx:run
