#!/bin/bash
# Offline-Prüfstand — drei Kennzahlen, ein Befehl, KEIN App-Start:
#   D1  Grammatik   (GrammarBenchIT)      — Archiv-Basiswerte + Live-Replay des Compose-Prompts
#   D2  Subjekte    (SubjectBenchIT)      — Müll-Anteil der Units + Wirkung der neuen Gates
#   D3  Tagger      (TaggerArbiterBenchIT)— Arbiter-Präzision über die gelabelten Sense-Fälle
#
# Ollama wird direkt angesprochen (localhost:11434); ohne erreichbares Ollama
# laufen die Offline-Teile trotzdem und die Live-Teile melden sich als übersprungen.
#
# Nutzung:
#   ./.script/bench.sh                          # alle drei Kennzahlen
#   ./.script/bench.sh -Dbench.model=gemma4:26b # anderes Modell für die Live-Teile
#   ./.script/bench.sh -Dbench.compose.n=40     # mehr Compose-Replays für D1
#   ./.script/bench.sh --basin /pfad/zum/basin  # zusätzlich den BasinBenchIT-Replay fahren
set -e
cd "$(dirname "$0")/.."

BASIN=""
ARGS=()
while [ $# -gt 0 ]; do
    case "$1" in
        --basin) BASIN="$2"; shift 2 ;;
        *) ARGS+=("$1"); shift ;;
    esac
done

mvn test -pl agent --no-transfer-progress \
    -Dtest='GrammarBenchIT,SubjectBenchIT,TaggerArbiterBenchIT' \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Dtest.excludedGroups= \
    -Dbench.enabled=true \
    "${ARGS[@]}" 2>&1 \
    | grep -E '^\[BENCH|^    |^Tests run:|ERROR|BUILD' \
    | sed 's/\[INFO\] //'

if [ -n "$BASIN" ]; then
    echo "────────────────────────────────────────"
    mvn test -pl agent --no-transfer-progress \
        -Dtest=BasinBenchIT \
        -Dsurefire.failIfNoSpecifiedTests=false \
        -Dtest.excludedGroups= \
        -Dtagging.basin="$BASIN" \
        "${ARGS[@]}" 2>&1 \
        | grep -vE '^\[INFO\]|^\[WARNING\]|Download|Progress'
fi
