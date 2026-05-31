#!/bin/bash
set -e

cd "$(dirname "$0")/.."

MODULES="core,database,reddit,agent,updater,launcher"

# Optional: single module via argument, e.g. ./test.sh agent
if [ -n "$1" ]; then
    MODULES="$1"
fi

echo "Running tests for: $MODULES"
echo "────────────────────────────────────────"

mvn test -pl "$MODULES" --no-transfer-progress 2>&1 \
    | grep -E '(Tests run:|BUILD|Reactor Summary)' \
    | sed 's/\[INFO\] //'

echo "────────────────────────────────────────"
echo "Done."
