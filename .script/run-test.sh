#!/bin/bash
set -e

# editorial-lab — isolated Reddit → cluster → headline harness as a small native
# window. One text field per Reddit thread link, "+" to add more, "Los" to run
# the whole real pipeline (ClusterEngine + EditorialAgent) and watch the trace.
# The window stays open between runs so Ollama + models stay warm; closing it
# shuts Ollama down.
#
# Usage:
#   .script/run-test.sh

cd "$(dirname "$0")/.."

echo "Building .lab..."
mvn -q -pl .lab -am clean install -DskipTests

echo "Opening .lab window..."
mvn -q -pl .lab exec:exec
