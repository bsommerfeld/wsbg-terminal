![Build Success](https://img.shields.io/badge/Build-Success-brightgreen) ![Tests Succeeded](https://img.shields.io/badge/Tests-Succeeded-brightgreen)


# Changelog

Alle nennenswerten Änderungen an WSBG Terminal werden hier dokumentiert.

Das Format orientiert sich an [Keep a Changelog](https://keepachangelog.com/de/1.1.0/),
die Versionierung folgt [Semantic Versioning](https://semver.org/lang/de/).

## [Unreleased]

## [1.0.0] — 2026-06-01

Erster Release. Ein Bloomberg-Terminal-inspiriertes Dashboard für r/wallstreetbetsGER,
das die Stimmung des Subreddits über lokale Ollama-Modelle zu redaktionellen Schlagzeilen
verdichtet. Alles läuft lokal und vollständig im Arbeitsspeicher — keine Cloud, keine
Datenbank, keine Persistenz von Reddit-Daten zwischen Sitzungen.

### Terminal & UI
- Bloomberg-Terminal-inspiriertes Dashboard als HTML/CSS/JS-Single-Page, gerendert in
  einem eingebetteten JCEF-(Chromium-)Browser in einem Swing-Fenster.
- Live-Anbindung der Oberfläche über lokalen HTTP-Asset-Server (`AssetServer`) und
  WebSocket-Push (`PushHub`).
- Plattform-spezifisches Fenster-Chrome: macOS behält die native Titelleiste (Drag/Resize
  gratis), Windows/Linux nutzen ein in der Seite gezeichnetes Titlebar mit Kanten-/Eck-Resize.
- Widgets: KI-Schlagzeilen (Reddit), Financial-Juice-News-Ticker, EUR/USD-FX-Badge,
  Markthandelszeiten- und Reddit-Health-Anzeige.

### Redaktionelle KI-Pipeline (agent)
- Deterministische 2-Stufen-Pipeline statt Tool-Use-Loop: Subjekt-Extraktion (Modell) →
  `TickerResolver` (Code) → Schlagzeilen-Komposition (Modell) → `HeadlineWriter` (Code).
  Leitlinie: "übersetzen, fast immer publizieren".
- Single-Model-Deployment: ein residentes multimodales `gemma4:e4b` für Chat, Vision und
  Pipeline, plus `embeddinggemma` für Embeddings (768d Cluster-Zentroide).
- Bildanalyse (Vision) über `gemma4:e4b`, Ergebnisse pro URL gecacht.
- Clustering: `PassiveMonitorService` bettet jeden neuen Thread ein und ordnet ihn per
  Cosinus-Match einem Cluster zu (EMA-Drift der Zentroide); `ClusterRebalancer` führt alle
  30 s iteratives Merging + Pruning verwaister Single-Thread-Cluster durch.
- `AgentCoordinator` debounct Cluster-Änderungen (3 s) und serialisiert die Editorial-Ticks.
- `WsbgJargon`-Glossar normalisiert Raum-Slang auf kanonische/englische Namen vor der
  Ticker-Auflösung.
- Highlight-Rubrik in zwei Stufen (`NORMAL` / `IMPORTANT`): konkrete ≥10 %-Moves,
  Pennystock-Raketen, Breaking-M&A und scharfe Makro-Katalysatoren werden rot hervorgehoben;
  Anti-Spam-Throttle stuft wiederholte IMPORTANT-Flags zum selben Ticker zurück.

### Reddit-Anbindung (reddit)
- `RedditSource`-Interface mit dynamischer Fallback-Kette (OAuth → `.json` → RSS), zur
  Laufzeit per `probe` auto-selektiert und alle 600 s neu aufgelöst — selbstheilend, ohne
  Konfiguration.
- **RSS-Pfad** als anonymer Fallback ohne App/Token/Login (StAX-Parser), funktioniert auch
  für bot-geblockte IPs.
- Gemeinsames `RedditRepository` über alle Quellen als Kontinuitätsschicht: ein
  Fallback-Wechsel liest nur Deltas, kein vollständiger Re-Scan.
- Per-Prozess-`instance:<hex>`-Token im User-Agent, damit Installationen sich kein
  IP+UA-Rate-Budget teilen.
- Poll-Daten-Parsing (`.json` + RSS) als hochsignalhafte Stimmungsquelle.
- Kurz-TTL-Snapshot-Persistenz (`RedditSnapshotStore` + `AgentSnapshotStore`): Reddit-Daten,
  Vision-Cache, veröffentlichte Schlagzeilen und voller Cluster-State werden bei schnellem
  Neustart (jünger als TTL, Default 60 min) verbatim wiederhergestellt — Schlagzeilen
  erscheinen sofort, keine Neu-Vision, Cold-Start-Fetch wird übersprungen.

### Marktdaten
- `yahoo-finance`: Ticker-Auflösung + Live-Quotes + News über auth-freie Yahoo-Endpunkte
  (v8/chart statt crumb-gesperrtem v7/quote).
- `currency`: Live-EUR/USD-Quote-Polling für das FX-Badge.
- `financial-juice`: Live-News-Fetch von FinancialJuice.

### Launcher & Auslieferung
- Eigenständiger Swing-Launcher (`launcher`), der vor dem Start Updates zieht und die
  Umgebung einrichtet (Ollama-Installation + Modell-Downloads via `setup.sh`/`setup.ps1`).
- OTA-Update über `TinyUpdateClient`: SHA-256-Manifest-Diff gegen lokale Datei-Hashes, lädt
  nur geänderte Dateien (split app.zip/deps.zip), verifiziert Hashes und räumt verwaiste
  Dateien auf.
- Launcher bleibt unsichtbar, wenn alles aktuell ist; zeigt sich nur bei echtem Download
  oder Fehler. `TerminalRaiser` hebt eine bereits laufende Instanz statt eine zweite zu starten.
- Native Installer via jpackage für Windows (exe), macOS (dmg) und Linux (deb + rpm).
- CI: Release-Workflow (TinyUpdate-Artefakte), Native-Packaging-Workflow (jpackage-Matrix)
  und PR-Build-Workflow (Test-JARs als PR-Artefakte).

### Architektur
- Java 25, Multi-Modul-Maven-Projekt mit Guice-DI (`AppModule` als Wurzel).
- Cross-Layer-Kommunikation über `ApplicationEventBus` (Guava EventBus).
- TEST-Modus (`APP_MODE=TEST`) mit `TestRedditScraper` für synthetische Daten ohne
  Reddit-API-Aufrufe.
- TOML-Konfiguration im OS-nativen App-Data-Verzeichnis (via `jshepherd`).
- `I18nService` für Mehrsprachigkeit (DE/EN).

[Unreleased]: https://github.com/bsommerfeld/wsbg-terminal/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/bsommerfeld/wsbg-terminal/releases/tag/v1.0.0
