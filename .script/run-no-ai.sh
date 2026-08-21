#!/bin/bash
# Dev run WITHOUT the model.
#
# Same build and launch as .script/run.sh, only with WSBG_NO_AI=true in the
# environment: the terminal comes up completely, but no Ollama is started,
# adopted or called (AiSwitch -> AgentBrain.start + ChatGateway). The AI lanes
# behave as they do against a dead endpoint - they go quiet, the rest keeps
# running. This is the run that survives on battery.
set -e

# Navigate to project root relative to this script
cd "$(dirname "$0")/.."

# An Ollama left over from an earlier run (a crash, a dev run cut off at the
# terminal) keeps its model resident - exactly the drain this script exists to
# avoid, and nothing will adopt it today because we never ask for a server.
# Only ever OUR OWN instance is touched: the port is 11500, deliberately never
# Ollama's default 11434, and the binary has to live inside our app data dir.
OLLAMA_DIR="$HOME/Library/Application Support/wsbg-terminal/ollama"
for pid in $(lsof -ti "tcp:11500" -sTCP:LISTEN 2>/dev/null || true); do
  cmd="$(ps -o comm= -p "$pid" 2>/dev/null || true)"
  case "$cmd" in
    "$OLLAMA_DIR"/*)
      echo "Stopping our leftover Ollama on 11500 (PID $pid)..."
      kill "$pid" 2>/dev/null || true
      ;;
    *)
      echo "Port 11500 is held by something that is not our Ollama (PID $pid: ${cmd:-unknown}) - left alone."
      ;;
  esac
done

echo "Starting WSBG Terminal with the AI switched off (WSBG_NO_AI=true)..."
export WSBG_NO_AI=true
exec ./.script/run.sh
