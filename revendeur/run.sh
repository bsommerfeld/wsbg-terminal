#!/bin/bash
set -e

echo "Building WSBG Terminal..."
mvn clean install -DskipTests

echo "Starting WSBG Terminal UI..."
mvn -pl ui javafx:run
