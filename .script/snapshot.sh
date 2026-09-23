#!/bin/bash
set -e

# Renders the shell into fx-terminal/target/shell.png (2x) for a visual check. Opens a real
# window for a moment - it is a screenshot of the scene graph, so the native
# macOS window buttons are not part of the image.
cd "$(dirname "$0")/.."
source .script/jdk.sh

SHELL_SNAPSHOT=true mvn -q test -Dtest=ShellSnapshotIT -Dtest.excludedGroups= -Dsurefire.failIfNoSpecifiedTests=false
echo "Snapshot: $(pwd)/fx-terminal/target/shell.png"
