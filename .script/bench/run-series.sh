#!/bin/bash
# Runs benchmark configurations back to back (bash: proper word splitting). Args: "<mode> <label> [flags]" ...
S="$(cd "$(dirname "$0")" && pwd)"; export WSBG_BENCH_OUT="${WSBG_BENCH_OUT:-$S/runs}"
ORCH=${ORCH:-orchestrate.mjs}
mkdir -p "$WSBG_BENCH_OUT"
# The backend is launched directly with java (no mvn in the measured tree): resolve its runtime classpath once.
if [ ! -s "$WSBG_BENCH_OUT/cp.txt" ]; then
  (cd "$S/../.." && mvn -q -pl terminal dependency:build-classpath -Dmdep.outputFile="$WSBG_BENCH_OUT/cp.txt" -Dmdep.includeScope=runtime >/dev/null 2>&1) || { echo "classpath resolution failed"; exit 1; }
fi
for run in "$@"; do
  read -r -a parts <<< "$run"
  echo "=== $(date +%T) starting: ${parts[*]}"
  # The display must stay awake: WebKit stops rendering (and rAF) for an occluded
  # or dark screen, which would silently turn a phase into a measurement of nothing.
  if command -v caffeinate >/dev/null 2>&1; then
    caffeinate -dis node "$S/$ORCH" "${parts[@]}" > /dev/null 2>&1
  else
    node "$S/$ORCH" "${parts[@]}" > /dev/null 2>&1
  fi
  echo "=== $(date +%T) finished: ${parts[1]} (exit $?)"
  sleep 3
done
echo ALL-DONE
