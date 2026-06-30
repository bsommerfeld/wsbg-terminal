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

## Throughput

- **LLM gate split (1 prep + 1 compose)** — at cold start, prep (extraction + vision) floods
  the 2-slot gate ~20:5 vs compose → compose starves → ~1 headline/min (50–70s gate-waits).
  Fix: two `Semaphore(1)` (or a compose-priority gate) so compose always has a slot and
  headlines flow steadily. Same GPU load (2 concurrent, NUM_PARALLEL=2). (May land pre-release.)
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
