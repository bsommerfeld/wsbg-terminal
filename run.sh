#!/bin/bash
set -e

echo "Building WSBG Terminal (PROD)..."
export APP_MODE=PROD
mvn clean install -DskipTests

echo "Starting WSBG Terminal UI (PROD MODE)..."
mvn -pl ui javafx:run
