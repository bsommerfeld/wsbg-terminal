# Post-Release Backlog

> Captured 2026-06-30 from the release-candidate quality audit (run31) and the
> deferred items. The RC itself is functionally solid (German headlines, L&S EUR
> prices, 0 whiffs, 0 Yahoo rate-limit, 1:1 mirror). Everything below is quality /
> architecture work to do **after** a slow release. The headline COMPOSER is good —
> these are upstream (extraction / resolution) and throughput items.

## ⚠️ URGENT — Cluster-context subject CONSOLIDATION ("Kontext")

The single biggest quality + architecture boost. **Over-extraction**: one thread
produces several near-identical headlines, one per extracted subject.

Live examples from run31:
- SaaS thread "War's schon mit der SaaS Ralley?" → **SAP + ServiceNow + Adobe + HUBC**,
  all saying "kritisiert die Investition, Cyber Security ist besser". Repetitive.
- "Juli-Saisonalität bei Apple, MSFT & KKR" → **AAPL + MSFT**, both "Free-Money-Glitch".
- (Earlier: a D-Wave/NSF-funding thread → D-Wave(QBTS) + National Science Foundation +
  Chips&Science Act, each headlining the same story.)

**Fix:** the cluster/thread IS the event context → pick the PRIMARY subject (in the
thread title + tradeable), demote the rest to context/evidence → one richer headline
per event. Also lets the resolver pull cluster context in to sort clusters better.
Makes the gold subject highlight consistent (one clean subject). The "Subjekte +
Threads" UI toggle is already removed (locked subjects-only); consolidation subsumes it.

## Resolution quality

- **Theme/concept → wrong ticker** (the THEME_WORDS / fuzzy guard has gaps):
  `Wall Street → WSSE`, `Venezuela → BVL.CR` (Costa Rica), `Services sector → ^BKFS.KW`,
  `Trade Republic → a derivative` (it's PRIVATE, not listed), `Cyber Security → HUBC`
  (Hub Cyber Security — the room meant the sector, not that small-cap).
- **Missed known tickers:** `MicroStrategy → name:micro strategy` (should be MSTR + price),
  `Yen → name:yen` (no FX quote — could be JPY=X).
- **L&S resolver chain ("Item 2") — needs rework before re-attempting.** Reverted from the
  RC because it (a) added a Yahoo `lookupByIsin` call per equity → tipped Yahoo into
  rate-limiting → companies un-enriched, and (b) L&S-by-name "first result" grabbed wrong
  companies for ambiguous names (`Meta → Metaplanet Inc.`, a Japanese firm). A correct
  version must avoid the extra Yahoo call and disambiguate the name. The seam
  (`NameToIsin`/`WsoLsNameToIsin`) lives in the git stash / history.

## Price — L&S as the universal EUR source

See `lang-schwarz/ROADMAP.md`. L&S carries equities/ETF/`WÄHRUNG`(crypto)/`ROHSTOFF`/
derivatives in EUR, classified by `categorySymbol`. Vision: L&S is the universal EUR
price source, Yahoo the type-router + news. Killer feature: **exact derivative pricing**
via a WKN/ISIN extracted from a post (regex) or Vision-OCR of a position screenshot.
Note: the L&S name-search can be simplified to "take the first result" (~90% coverage).

## News

- The slim compose output **dropped `sourceNewsIds`**, so a headline no longer cites WHICH
  news it leaned on (0 citations in run31). The `newsEnriched` flag (unit has news) still
  drives the subtle "News" provenance tag, and news still rides the brief — but if explicit
  per-headline news attribution matters, re-introduce a minimal citation without re-fattening
  the compose JSON (e.g. derive coverage from the brief, not the model output).
- News DENSITY of headlines is low when the threads are pure discussion/sentiment (run31 was
  mostly that) — correct behaviour. News-EVENT threads (Redwire, D-Wave) produce news-anchored
  lines, but those didn't surface in the run31 sample (queue / extraction-empties).
- **German-stock news GAP — add a Deutsche Börse / XETRA news source.** The NewsAggregator is
  Yahoo-only (NASDAQ disabled), and **Yahoo does NOT carry German small-cap news.** Live proof:
  „Meta Wolf AG" (`WOLF.DE`) ran **+25.8 %** with a real catalyst — XETRA had the news (2026
  AGM: completed transformation into the ceramic-tech company CERAM TECH) — but Yahoo had
  nothing, so the headline said „ohne einen klaren Katalysator" AND stayed NORMAL (no catalyst →
  no red). **Deutsche Börse has a keyless news SEARCH** (`live.deutsche-boerse.com/nachrichten/…`).
  Wire a `DeutscheBoerseNewsClient implements NewsSource` (the aggregator is a Guice multibinding —
  adding a source is one bind line) that searches **by NAME** (the user says name is enough;
  ISIN later for precision), fanning alongside Yahoo. `DeutscheBoerseClient` (price) already exists
  in the repo as a reference. (See also the unwired `finanznachrichten` RSS module.)
  - **Triangulate several German sources, not just one.** XETRA/Deutsche Börse, **boerse.de**, and
    **wallstreet-online (WSO)** all carry the same German-stock news. **WSO is the quickest win —
    it's ALREADY integrated** (`WallstreetOnlineClient`, used for ISIN resolution), so adding a
    news method is a small extension. Bind each as a `NewsSource`; the aggregator fans the query
    across all + dedups by uuid. More sources = better COVERAGE (some catalyst for any stock).
  - **Constraint = gemma4's tolerance, so curate, don't firehose.** More news → bigger compose
    brief → more prefill time (the ~25s/call cost) AND more degeneration-loop risk. So mix MANY
    sources for coverage, but feed the model a **relevance-ranked top-N** (the SubjectUnit already
    caps news ~12 by uuid) — density of SOURCES, bounded count IN the brief. The "weighty
    relevance" ranking is the lever: rank the deduped pool so the top-N are the real catalysts,
    then tune the cap against what gemma4 still handles without slowing/looping.
- **News must carry MORE relevance weight — but as CONCRETISATION, not addition.** The current
  sentiment headlines are GOOD; don't break them. "Enrich" here means: when the community is
  ABSTRACT ("massiver Kursanstieg, kein klarer Katalysator"), the news makes it CONCRETE — name
  the actual catalyst woven into the same line (e.g. „Meta Wolf AG +25,8 % nach Abschluss der
  Wandlung zum Keramik-Technologie­unternehmen CERAM TECH"). NOT a separate additive news line —
  replace the room's "we don't know why" with the verified "why". This also fixes the highlight:
  with the catalyst in hand, a big one-sided move can finally earn red.
  - **The key is to PAINT THE PICTURE — rich, specific detail, not dry attribution.** This is a
    STYLE principle, not a "+ source" tag. The user's analogy: not "Sie hat einen Schal an" but
    "Sie trägt ein blau-gestreiftes Seidengewand, um den Hals gewickelt, mit einer Schleife
    festgebunden." So NOT „… basierend auf Nachrichten" / „Long aufgrund von …" (flat, journalistic-
    dry) — instead weave the concrete specifics OF the news (the actual event, the actual numbers,
    the actual players) INTO the line so it reads vivid and definite, the way good prose upgrades a
    "scarf" into the silk garment. The news supplies the texture; the line stays one sentence.
- **News-grounded INFERENCE is now allowed — relax the old "never interpret a reason" rule.**
  Rationale (user): people don't trade because it "could be cool", they trade because they
  followed the **news + geopolitics**. So when VERIFIED news sits next to the room's reaction, we
  may **infer the connection** — conclude that the room is reacting to *that* event and say so —
  even if no comment names it explicitly. This is NOT the old "er meint bestimmt …"
  word-in-the-mouth hallucination (that guard stays for the NO-news case): inference grounded in a
  verified catalyst is legitimate, because the catalyst is real and the reaction is the data.
  Prompt change: in `headline-compose-unit(.de).txt`, soften the Leitfrage/rule-5 "NEVER invent,
  guess or interpret a reason" to "never invent a reason WITHOUT verified news; WITH verified news,
  connect the room's reaction to it." Strike the over-strict no-inference framing for that case.

## Throughput

- **LLM gate — compose-priority gate: DONE (landed pre-release).** At cold start prep (extraction
  + vision) flooded the shared 2-slot gate ~20:5 vs compose → compose starved → ~1 headline/min
  (50–70s gate-waits). Now a `ReentrantLock`+2-`Condition` pool: compose-first (a freed slot goes
  to a waiting compose; prep yields), prep uses BOTH slots when nothing's composing (fast cold-start
  fill). Same GPU load (NUM_PARALLEL=2). (A hard 1+1 `Semaphore` split was tried first but halved
  prep at cold start → first headline came LATER; the priority gate fixes that.)
- **Ollama `keep_alive` = -1 (keep gemma resident) + harden the cleanup.** Default keep_alive is
  5 min, so a >5 min steady-state lull unloads the model → the next headline pays a ~seconds reload.
  Rare during active market hours, so deferred. `OLLAMA_KEEP_ALIVE=-1` (server env in
  `OllamaServerManager`; langchain4j sends no per-request keep_alive, so the server default applies)
  keeps it loaded forever — BUT then a crash that orphans the server holds the RAM until manually
  killed. Current cleanup is mostly safe (robust `stop()` with child-kill + on startup an orphaned
  isolated Ollama is REUSED, not leaked), but the gap is "hard crash + no restart". Before flipping
  to -1, add a **Runtime shutdown hook** calling `serverManager.stop()` (covers SIGTERM / normal
  exit; not `kill -9`). User: "-1 ist sinnvoll, nur guten Cleanup brauchen wir."
- **Compose-loop residual:** ~3/11 first composes still degenerate to the cap (out=1024); the
  1:1 retry catches them (0 whiffs) but each costs an extra LLM call. Model-degeneration on
  big briefs — tune the brief cap / numPredict further.
- **Extraction empties residual:** a few clusters still return 0 subjects (degeneration); the
  char-budget chunk + retry reduced but didn't eliminate it.

## UI / composition polish

- **Gold subject highlight** is built but gated off (`GOLD_SUBJECTS=false` in `reddit.js`) —
  re-enable after consolidation gives one clean subject per event. The canonical name should
  then be the extracted name (the model's short form), not Yahoo's legal name ("Salesforce,
  Inc."), so the gild matches the line.
- Minor compose quirks: a meme target ("Richtung 40") can be framed as a price ("40 EUR");
  thin threads yield thin lines (1:1-correct but could be richer).
