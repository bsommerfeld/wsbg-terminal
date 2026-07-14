#!/bin/bash
set -e

# Navigate to project root relative to this script
cd "$(dirname "$0")/.."

echo "Building WSBG Terminal..."
mvn clean install -DskipTests

echo "Starting WSBG Terminal UI..."
# Dev runs used to log ONLY to the console — every post-mortem needed manual
# copy-paste. Mirror stdout/stderr into the session log dir like the launcher
# does, and keep a stable dev-latest.log pointer for tooling.
LOG_DIR="$HOME/Library/Application Support/wsbg-terminal/logs/terminal"
mkdir -p "$LOG_DIR"
LOG_FILE="$LOG_DIR/dev-$(date +%Y-%m-%d_%H-%M-%S).log"
ln -sf "$LOG_FILE" "$LOG_DIR/dev-latest.log"
echo "Logging to $LOG_FILE"
mvn -pl terminal exec:exec 2>&1 | tee "$LOG_FILE"
