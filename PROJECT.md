# WSBG Terminal — Project Description

A Bloomberg-Terminal–inspired desktop dashboard for the German retail-trading subreddit
[**r/wallstreetbetsGER**](https://www.reddit.com/r/wallstreetbetsGER/). It continuously reads
the subreddit, clusters related discussion, and turns the "ape noise" into a live, AI-generated
news wire of trading-relevant headlines — enriched with real market data — all running **100 %
locally** on the user's machine via Ollama.

This document is the technical/developer overview. For the user-facing (German, WSBG-jargon)
pitch see [`README.md`](README.md); for authoritative build/architecture guidance for AI
assistants see [`CLAUDE.md`](CLAUDE.md).

---

## What it does

1. **Polls Reddit** (`r/wallstreetbetsGER`) on an interval, fetching threads, comment trees,
   poll data, and inline media.
2. **Clusters** related threads by semantic similarity (embeddings + cosine vs. centroid) so a
   single market topic becomes one investigation, not fifty duplicate posts.
3. **Reads the cluster** with a local multimodal LLM: extracts the market subjects under
   discussion (tickers, instruments, themes) and analyses any attached images (gain/loss
   screenshots, charts).
4. **Resolves each subject** to a validated Yahoo Finance ticker with a live quote and recent
   news, enriching the sentiment with hard data.
5. **Composes a headline per subject** — a terse, news-wire-style German line — and flags the
   ones that represent an actionable money-making opportunity (the "pennystock rocket" radar).
6. **Pushes everything to the UI**: a live headline wire, a market-mood barometer, a Financial
   Juice news ticker, a EUR/USD badge, and international market hours.

The editorial stance is deliberately **"translate, almost always publish"** — the wire mirrors
the subreddit's sentiment 1:1; thin engagement is itself a signal, not a reason to filter.

---

## Tech stack

| Concern        | Technology |
|----------------|------------|
| Language       | Java 25, multi-module Maven build (`${revision}` versioning) |
| UI runtime     | Embedded **JCEF (Chromium)** in a Swing `JFrame` — HTML/CSS/JS front-end, **no JavaFX** in the terminal |
| UI transport   | Loopback HTTP asset server + WebSocket push (`AssetServer` / `PushHub`) |
| DI             | Google **Guice** (`AppModule` is the root injector) |
| AI             | Local **Ollama**: `gemma4:e4b` (chat + vision + editorial pipeline), `embeddinggemma` (768-d embeddings), via **LangChain4j** |
| Config         | TOML at the OS app-data dir, loaded with `jshepherd` |
| Events         | Guava **EventBus** wrapper (`ApplicationEventBus`) |
| Persistence    | Mostly **in-memory**; permanent append-only JSONL for headlines; short-TTL JSON session snapshots |

There is **no SQLite / no database server** despite the `database` module name. Reddit-derived
state is intentionally process-lifetime only.

---

## Architecture at a glance

The UI is an HTML/CSS/JS single page rendered inside an embedded Chromium browser hosted in a
Swing window. State flows **Java → page** over a WebSocket via publishers, and **page → Java**
via a command bridge. The backend is an editorial pipeline that runs Reddit data through a local
LLM and Yahoo Finance enrichment.

```
Reddit  ─poll→  ClusterEngine ─assign→  ClusterRegistry ─dirty→  EditorialAgent
 (source                                                            │
  fallback                                          subject extract │ (LLM)
  chain)                                            TickerResolver  │ (Yahoo)
                                                    compose/subject │ (LLM)
                                                    HeadlineWriter  │ (QA gate)
                                                            │
                                          AgentRepository (24h wire) + HeadlineArchive (permanent)
                                                            │
                                              Publishers → WebSocket → HTML UI
```

### Module dependency graph

```
launcher → updater
terminal → agent, database, reddit, financial-juice, currency, core
.lab     → agent, reddit, database, yahoo-finance, core   (hidden dev harness, artifactId `lab`)
agent    → reddit, yahoo-finance, database, core
database → core
reddit   → core
financial-juice → core
currency → core
yahoo-finance → core
```

> Note: the repo also contains newer/peripheral modules (`source-api`, `aggregator`,
> `embedding`, `finanznachrichten`) representing in-progress source-abstraction and
> news-aggregation work; the graph above reflects the core terminal wiring.

### Key modules

- **core** — shared domain objects (`RedditThread`, `RedditComment`, `FjNewsItem`), config POJOs,
  the `Model` enum, the event bus, and `I18nService`. No framework deps beyond Guava EventBus.
- **reddit** — Reddit fetching behind a `RedditSource` interface. The bound implementation is a
  **dynamic fallback chain** (OAuth → anonymous `.json` → RSS) that probes at runtime and
  re-resolves every 600 s, so each install self-selects a working path and self-heals. The
  anonymous `.json` delegate rides the shared `WebFetcher` chain (`browser → direct`): the
  **browser** transport fetches Reddit's `.json` through the embedded Chromium runtime so the
  request goes out as ordinary browser traffic (real session + cookies) — the supported path
  where a bare client gets a 403 — with plain HTTP as the per-request fallback; **RSS** is the
  always-reachable anonymous floor. All sources share one `RedditRepository` so a fallback switch
  continues from existing data instead of re-scanning, and one `WebFetcher` transport seam (the
  same Yahoo uses) so a new fetcher — e.g. a future BYOK API strategy — is a wiring change, not a
  rebuild.
- **database** — in-memory stores plus permanent headline history:
  - `RedditRepository` — threads + comment trees (session-only).
  - `AgentRepository` — the live 24 h headline wire, re-seeded from the archive on startup.
  - `HeadlineArchive` — **permanent, append-only** JSONL (`archive/headlines.jsonl`), indexed in
    memory; the `search()` / `byTicker()` / `recent()` primitives for search & watchlist.
  - `WatchlistStore` — persisted user ticker watchlist.
- **yahoo-finance** — resolves tickers, live quotes, and news (consumed by `TickerResolver`).
- **financial-juice** / **currency** — live news ticker and EUR/USD FX badge.
- **agent** — the editorial pipeline (LangChain4j + Ollama). Central classes: `AgentBrain`
  (model access + vision cache), `PassiveMonitorService` (scheduled poll loop), `ClusterEngine`
  (the single source of truth for cluster assignment), `ClusterRegistry` (active clusters),
  `SubjectRegistry` / `SubjectUnit` / `SubjectAttributor` (the feed-wide subject layer — the
  editorial atom), `EditorialAgent` (the per-subject-unit editorial tick, `runUnitTick`),
  `TickerResolver` (subject → validated Yahoo ticker), and `HeadlineWriter` (QA gate + publish).
- **terminal** — process entry point (`AppMain`), the JCEF window, the HTTP/WebSocket servers, and
  the Java↔page bridges; Guice wiring lives here.
- **`.lab`** — a hidden dev harness (a small native Swing window) that runs the **real**
  clustering + editorial pipeline over hand-entered thread links and streams the trace, keeping
  Ollama warm between runs. Replaces the removed synthetic `APP_MODE=TEST`.
- **launcher / updater** — standalone JavaFX launcher (Ollama setup + app updates) and the GitHub
  release update client.

---

## The editorial pipeline (the core)

The agent is **not** a tool-use loop. It is a fixed, deterministic pipeline whose editorial atom
is the feed-wide **subject unit** (not the cluster):

1. **Poll** — the Reddit source polls every `update-interval-seconds` (default 60 s).
2. **Cluster** — for each new/updated thread, `ClusterEngine.assign` embeds
   `title + body + visionDescription` and joins the nearest cluster centroid (cosine ≥
   similarity-threshold, default 0.55) with EMA centroid drift, or creates a new cluster. Clusters
   are assign-only **ingestion buckets** — there is no cluster merge/prune step.
3. **Debounce** — every registry change triggers `AgentCoordinator` (3 s debounce → one
   `EditorialAgent.runUnitTick`).
4. **Attribute** — per dirty cluster: `ReportBuilder` brief → **subject extraction** (one LLM call,
   uncapped) → `TickerResolver` (Yahoo ticker + quote + news per subject) → `SubjectAttributor`
   folds the evidence into the feed-wide `SubjectRegistry`, then `mergeIdentities` folds name units
   into their ticker unit (cross-thread consolidation happens here, at the subject level).
5. **Compose** — for each dirty `SubjectUnit`, **one headline composition call** (single-object
   JSON, never a batched array — a 4 B model degenerates on long arrays), tagged NEW/UPDATE against
   the unit's own prior headlines.
6. **Publish** — `HeadlineWriter.publishUnit` QA-gates the draft (identical-text dedupe; ticker +
   snapshot from the resolver-validated unit, never the model), persists it to `AgentRepository`
   (and the permanent archive) keyed by the unit id, and posts an `AgentStreamEndEvent` the UI
   publisher picks up.

### Highlight rubric (the "rocket radar")

Each headline gets a two-tier `HeadlineHighlight`:

- **NORMAL** — routine discussion / sentiment / memes / past-gain bragging (the default).
- **IMPORTANT** — a concrete, actionable money-making opportunity an ape would chase, across any
  instrument and any kind of play. Engagement is explicitly **irrelevant** — a quiet one-liner
  can be the best lead. IMPORTANT rows render with a red wash in the UI. (An anti-spam throttle
  downgrades a ticker that was just flagged.)

---

## Data & persistence model

- **Reddit-derived state is ephemeral.** Threads, comments, clusters, and the vision cache live
  only for the process lifetime — persisting Reddit snapshots produced ghost clusters when posts
  vanished from the live feed.
- **Headlines are permanent.** They're the app's own output, archived append-only in
  `HeadlineArchive` and never deleted; the live wire re-seeds from the last 24 h on startup.
- **Short-TTL session snapshots** (`reddit-snapshot.json` / `agent-snapshot.json`, written every
  5 min + on shutdown) let a quick restart resume verbatim — headlines reappear instantly, vision
  isn't recomputed, clusters resume with stable IDs — but only if younger than the TTL (default
  60 min), which guards against ghost clusters.

---

## AI models

Single-model deployment by design (no swappable alternatives):

- **`gemma4:e4b`** (`Model.REASONING_POWER`) — chat, vision, and the editorial pipeline. One
  resident multimodal model. (The `-mlx` build is text-only and deliberately unused.)
- **`embeddinggemma:latest`** (`Model.EMBEDDING`) — 768-d vectors for cluster centroids.

`AgentBrain.resolveModel()` falls back to any installed model from the same family prefix if the
exact tag is missing.

---

## UI

A small HTML/CSS/JS single page under `terminal/src/main/resources/web/`, served over loopback
HTTP and fed live state over a WebSocket, displayed inside the embedded Chromium browser. Window
chrome is platform-split (decorated frame with hidden title bar on macOS; fully undecorated with
page-painted drag/resize on Windows/Linux). Widgets:

- **Reddit headlines** — the AI-generated wire (IMPORTANT tier rendered with a red wash).
- **Market-mood barometer** — a live "% BULLISH/BEARISH" pill computed over the 24 h wire.
- **Financial Juice ticker** + **EUR/USD badge** — live external news and FX.
- **Market hours** — international sessions incl. holidays.

To add a widget: markup in `index.html`, a render module under `web/js/widgets/`, a `socket.on`
wiring in `main.js`, and a Java publisher in `ui/bridge/` that pushes its payload over `PushHub`.

> **Why windowed JCEF, not JavaFX or OSR:** off-screen (windowless) JCEF crashes on Apple Silicon
> (no arm64 JOGL native), and a heavyweight AWT canvas cannot live in a JavaFX scene graph — hence
> a Swing window hosting a windowed/heavyweight Chromium browser. (A custom software-OSR renderer
> is used for the hidden Reddit fetch transport, sidestepping JOGL.)

---

## Build & run

```bash
# Build everything (skipping tests)
mvn clean install -DskipTests

# Run the JCEF/Swing terminal
./.script/run.sh

# Isolated editorial harness (the .lab module): native window to run the real
# clustering + editorial pipeline over hand-entered Reddit thread links
./.script/run-test.sh
```

### Testing

```bash
# All unit tests across all modules
./.script/test.sh

# A single module
./.script/test.sh agent

# Include integration tests (requires Ollama installed)
mvn test -pl agent -Dtest.excludedGroups=

# Live end-to-end pipeline smoke test (requires a live Ollama server)
PIPELINE_SMOKE=true mvn test -pl agent -Dtest=PipelineSmokeIT -Dtest.excludedGroups=
```

Integration tests are tagged `@Tag("integration")` and excluded from normal `mvn test` runs;
`PipelineSmokeIT` is additionally gated by the `PIPELINE_SMOKE` env var.

---

## Configuration

User config is a TOML file at the OS-native app-data directory
(`~/Library/Application Support/wsbg-terminal/config.toml` on macOS), loaded via `jshepherd` into
`GlobalConfig` (with `AgentConfig`, `RedditConfig`, `HeadlineConfig`, `UserConfig` sub-configs).
Model choice is managed centrally and not exposed to end users.

---

## Deployment model

Currently **per-user / local**: each install runs on its own machine, its own IP, and its own
local Ollama, with its own rate budget. A centralized hosted model (a home Mac Mini serving a
larger model as a paid wire) is a future vision, not the current design. Monetization today is
donation-based.

---

## Requirements

- **OS** — macOS (Apple Silicon preferred), Windows, or Linux.
- **RAM** — 16 GB minimum.
- **Disk** — ≈ 15 GB free (local LLM + embedding model).
- **Compute** — Apple Silicon (M1+) or a multi-core CPU with GPU support; CPU-only generation is
  slow.
- **Ollama** — installed automatically by the launcher's setup scripts.

---

**Not investment advice.** 🖍️
