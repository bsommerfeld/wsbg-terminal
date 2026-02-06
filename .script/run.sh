#!/bin/bash

# Navigate to project root relative to this script
cd "$(dirname "$0")/.."

echo "Starting WSBG Terminal..."
mvn clean javafx:run -pl ui
