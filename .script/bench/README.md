# UI-Messbank

Misst die Terminal-UI reproduzierbar in beiden Engines: **JCEF** (heutiges Swing-Fenster, Software-OSR)
und **Shell** (`shell/`, Tauri mit System-WebView, Backend ohne Fenster über `WSBG_SHELL=external`).
Dasselbe Skript (`bench.template.js`) läuft in der Seite: Intro abwarten, Leerlauf, Leerlauf mit
pausierten Animationen, Scroll-Burst, Theme-Wechsel, fünf kleine Dauer-Animationen, Leerlauf ohne
Sampler. Es meldet Phasenmarken und rAF-Abstände an einen lokalen Sammler; parallel werden RSS und
CPU-Zeit aller beteiligten Prozesse sekündlich gesampelt. Im JCEF-Modus schreibt zusätzlich der
Frame-Profiler (`WSBG_FRAME_PROFILE`) die gelieferten Frames.

Voraussetzungen: `mvn -q -pl terminal compile` (Backend-Klassen), `cargo build` in `shell/`, Node 22+,
kein laufendes Terminal (Single-Instance-Port 19337, DevTools 9222).

```bash
# offline (keine Datenquellen), JCEF gegen Shell, Shell einmal mit 120-Hz-Schalter
.script/bench/run-series.sh "jcef jcef-offline" "shell shell-offline" "shell shell-offline-hz --hz120"

# mit echtem Workload: 150 s aufwärmen, dann 120 s Ruckler-Fenster mit Dauer-Animation
.script/bench/run-series.sh "jcef jcef-prod --prod --warm 150 --long 120" "shell shell-prod --prod --warm 150 --long 120 --hz120"

node .script/bench/compare.mjs jcef-offline shell-offline shell-offline-hz
```

Ergebnisse liegen unter `.script/bench/runs/<label>/` (`report.txt`, `data.json`, Logs). Der Ordner ist
git-ignoriert.
