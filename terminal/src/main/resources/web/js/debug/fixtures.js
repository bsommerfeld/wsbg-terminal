// Fixture generator for debug-preview.html — realistic sample answers in the
// exact shapes the DebugBridge Javadoc documents, so the dashboard can be
// looked at in a plain browser without starting the app. Dev-only file under
// a package-excluded path; nothing in the app imports it.

const MIN = 60_000;
const rnd = (a, b) => a + Math.random() * (b - a);
const pick = arr => arr[Math.floor(Math.random() * arr.length)];

/** Deterministic-ish seedable jitter so a reload does not reshuffle wildly. */
export function fixtures(now = Date.now(), { runner = 'mlx' } = {}) {
  return {
    overview: () => ({
      devMode: true,
      pid: 48213,
      javaVersion: '21.0.4',
      os: 'Mac OS X aarch64',
      uptimeMs: 3 * 3600_000 + 12 * MIN,
      heapUsedBytes: 1_284_000_000,
      heapMaxBytes: 4_294_967_296,
      processors: 12,
      commands: ['overview', 'sources', 'collectors', 'basin', 'runtime', 'config', 'log', 'reddit', 'subjects'],
      memory: {
        machineTotalBytes: 34_359_738_368,
        machineFreeBytes: 2_900_000_000,
        available: true,
        terminalRssBytes: 2_640_000_000,
        ollamaRssBytes: 9_820_000_000,
        totalRssBytes: 12_460_000_000,
        processes: [
          { pid: 48213, role: 'terminal', rssBytes: 2_640_000_000, own: true },
          { pid: 48260, role: 'ollama serve', rssBytes: 180_000_000, own: false },
          { pid: 48311, role: 'ollama runner', rssBytes: 9_640_000_000, own: false },
        ],
      },
      storage: {
        path: '/Users/dev/Library/Application Support/wsbg-terminal',
        totalBytes: 23_900_000_000,
        files: 1_666,
        unreadable: 0,
        sampledAtMs: now - 4_000,
        volumeTotalBytes: 494_384_795_648,
        volumeFreeBytes: 132_000_000_000,
        entries: [
          { name: 'ollama', bytes: 23_100_000_000, directory: true, files: 41 },
          { name: 'jcef-bundle', bytes: 285_000_000, directory: true, files: 620 },
          { name: 'cef', bytes: 87_000_000, directory: true, files: 310 },
          { name: 'lib', bytes: 47_000_000, directory: true, files: 58 },
          { name: 'tesseract', bytes: 42_000_000, directory: true, files: 12 },
          { name: 'logs', bytes: 2_900_000, directory: true, files: 9 },
          { name: 'instruments', bytes: 1_500_000, directory: true, files: 4 },
          { name: 'snapshots', bytes: 780_000, directory: true, files: 6 },
          { name: 'launcher', bytes: 420_000, directory: true, files: 5 },
          { name: 'archive', bytes: 258_000, directory: true, files: 7 },
          { name: 'fonts', bytes: 120_000, directory: true, files: 8 },
          { name: 'images', bytes: 96_000, directory: true, files: 3 },
          { name: 'config.toml', bytes: 9_715, directory: false, files: 1 },
          { name: 'identity-ledger.jsonl', bytes: 11_090, directory: false, files: 1 },
        ],
      },
    }),

    sources: () => {
      const spec = [
        ['reuters-rss', 'DELIVERED', 0, 14, 6 * MIN, ''],
        ['handelsblatt-rss', 'DELIVERED', 0, 9, 4 * MIN, ''],
        ['finanzen-net', 'EMPTY', 0, 0, 52 * MIN, 'feed returned 0 items'],
        ['sec-filings', 'FAILED', 5, 0, 96 * MIN, 'java.net.SocketTimeoutException: read timed out'],
        ['boersen-zeitung', 'DELIVERED', 1, 3, 11 * MIN, 'retry succeeded'],
        ['fmp-quotes', 'DELIVERED', 0, 42, 1 * MIN, ''],
        ['ecb-calendar', 'SKIPPED', 0, 0, 240 * MIN, 'cached — nothing new since last pass'],
        ['yahoo-fx', 'DELIVERED', 0, 6, 2 * MIN, ''],
      ];
      const health = spec.map(([source, lastStatus, consecutiveFailures, lastItemCount, sinceMs, lastNote]) => ({
        source, lastStatus, consecutiveFailures, lastItemCount, lastNote,
        lastRunMs: now - sinceMs + rnd(0, 20_000),
        lastSuccessMs: lastStatus === 'FAILED' && consecutiveFailures > 3 ? now - sinceMs - 40 * MIN : now - sinceMs,
        runs: Math.round(rnd(40, 220)),
        failures: consecutiveFailures ? Math.round(rnd(consecutiveFailures, consecutiveFailures + 12)) : Math.round(rnd(0, 3)),
        recentItemCounts: Array.from({ length: 24 }, (_, i) => {
          if (consecutiveFailures && i >= 24 - consecutiveFailures) return -1;
          if (lastStatus === 'EMPTY' && i > 16) return 0;
          if (lastStatus === 'SKIPPED') return 0;
          return Math.round(rnd(0, lastItemCount + 8));
        }),
      }));
      let evT = now - 35 * MIN;
      const events = Array.from({ length: 60 }, () => {
        const h = pick(health);
        evT += rnd(10_000, 45_000);
        return {
          atMs: Math.min(now, evT),
          source: h.source,
          status: h.lastStatus,
          itemCount: h.lastStatus === 'DELIVERED' ? Math.round(rnd(1, 30)) : 0,
          note: h.lastNote,
        };
      }).sort((a, b) => a.atMs - b.atMs);
      return {
        health, events, totalEvents: 1843,
        cooldowns: [{ host: 'www.sec.gov', untilMs: now + 4.5 * MIN, leftMs: 4.5 * MIN, strikes: 3 }],
        silences: [{ hostTransport: 'finanzen.net·http2', strikes: 6, mutedUntilMs: now + 22 * MIN, leftMs: 22 * MIN }],
        hostGates: 17,
      };
    },

    collectors: () => {
      const defs = [
        ['news-collector', 90_000, 1400, 12, 5],
        ['quote-collector', 60_000, 380, 40, 38],
        ['calendar-collector', 15 * MIN, 2600, 6, 0],
        ['filings-collector', 10 * MIN, 8200, 0, 0, 'SocketTimeoutException'],
      ];
      const passes = [];
      const clock = defs.map(([source, interval, dur, items, fresh, lastError]) => {
        let t = now - 32 * MIN;
        while (t < now) {
          passes.push({
            atMs: t,
            source,
            durationMs: dur * rnd(0.6, 1.6),
            items: lastError ? 0 : Math.round(items * rnd(0.4, 1.4)),
            fresh: lastError ? 0 : Math.round(fresh * rnd(0, 1.3)),
            error: lastError && Math.random() < 0.5 ? lastError : null,
          });
          t += interval * rnd(0.85, 1.25);
        }
        const mine = passes.filter(p => p.source === source);
        const last = mine[mine.length - 1];
        return {
          source,
          lastStartMs: last.atMs,
          lastDurationMs: last.durationMs,
          lastItems: last.items,
          lastFresh: last.fresh,
          lastError: lastError || null,
          nextDueMs: last.atMs + interval,
          passes: mine.length + Math.round(rnd(20, 90)),
          misses: lastError ? Math.round(rnd(1, 5)) : 0,
        };
      });
      passes.sort((a, b) => a.atMs - b.atMs);
      return { clock, passes };
    },

    basin: () => {
      const pours = [];
      let size = 640;
      for (let t = now - 30 * MIN; t < now; t += rnd(20_000, 90_000)) {
        const offered = Math.round(rnd(1, 25));
        const fresh = Math.round(offered * rnd(0, 0.6));
        size = Math.min(1200, size + fresh - Math.round(rnd(0, 4)));
        pours.push({ atMs: t, source: pick(['reuters-rss', 'handelsblatt-rss', 'fmp-quotes', 'yahoo-fx', 'boersen-zeitung']), offered, fresh, basinSize: size });
      }
      return {
        stats: {
          size, maxItems: 1200, durable: 210, live: size - 210, sentiment: 96,
          oldestPouredAtMs: now - 26 * 3600_000,
          newestPouredAtMs: now - 40_000,
          bySource: {
            'reuters-rss': 288, 'handelsblatt-rss': 201, 'fmp-quotes': 154,
            'boersen-zeitung': 96, 'yahoo-fx': 61, 'ecb-calendar': 12, 'sec-filings': 4,
          },
          ageBuckets: { '<15m': 34, '15m-1h': 121, '1-6h': 302, '6-24h': 188, '>24h': 71 },
        },
        inflowTotals: [
          { source: 'reuters-rss', pours: 142, offered: 1980, fresh: 402, lastPourMs: now - 3 * MIN },
          { source: 'handelsblatt-rss', pours: 138, offered: 1240, fresh: 289, lastPourMs: now - 5 * MIN },
          { source: 'fmp-quotes', pours: 402, offered: 16_080, fresh: 154, lastPourMs: now - 40_000 },
          { source: 'ecb-calendar', pours: 12, offered: 96, fresh: 0, lastPourMs: now - 190 * MIN },
        ],
        pours,
      };
    },

    runtime: () => {
      const declared = 32_768;
      const mlx = runner === 'mlx';
      let callT = now - 28 * MIN;
      const calls = Array.from({ length: 40 }, () => {
        const gate = Math.random() < 0.25 ? rnd(1200, 14_000) : rnd(0, 400);
        callT += rnd(15_000, 50_000);
        return {
          atMs: Math.min(now, callT),
          thread: pick(['editorial-1', 'editorial-2', 'ui-agent', 'digest-3']),
          gateWaitMs: gate,
          genMs: rnd(1800, 26_000),
          tokensIn: Math.round(rnd(2400, 15_900)),
          tokensOut: Math.round(rnd(120, 900)),
        };
      });
      return {
        model: {
          tag: mlx ? 'wsbg-analyst-mlx' : 'wsbg-analyst:q4_K_M',
          runner,
          contextTokensDeclared: declared,
          contextEnforcedByRunner: !mlx,
          truncationLimitIfGguf: declared - Math.max(Math.floor((declared - 5) / 2), 1),
          numPredict: 1024,
        },
        llmMeasured: {
          calls: 318,
          maxTokensIn: 15_902, maxTokensOut: 940,
          lastTokensIn: 9412, lastTokensOut: 402,
          totalTokensIn: 2_918_400, totalTokensOut: 118_020,
          totalGateWaitMs: 412_000, totalGenMs: 3_918_000,
        },
        llmCalls: calls,
        gate: { permits: 2, inUse: 2, interactiveWaiting: 1, backgroundWaiting: 3, interactiveStreak: 2, priority: 'interactive' },
        editorialQueue: { size: 7, inFlight: 2 },
        gauges: {
          'digest.cache.digests': 812, 'digest.cache.bodyHashes': 1204,
          'digest.shellStrikeHosts': 6, 'digest.walledHosts': 11,
          'pipeline.prep.threads': 4, 'pipeline.prep.active': 2, 'pipeline.prep.inProgress': 3,
          'pipeline.prep.rerunRequested': 0, 'pipeline.prep.deferred': 1, 'pipeline.prep.emptyExtractionCooling': 2,
          'pipeline.worker.threads': 3, 'pipeline.queue.size': 5, 'pipeline.queue.inFlight': 2,
        },
      };
    },

    config: () => {
      const entries = [
        ['agent.model', 'wsbg-analyst-mlx', 'wsbg-analyst:q4_K_M', true],
        ['agent.context.tokens', '32768', '8192', true],
        ['news.collector.interval.seconds', '90', '180', true],
        ['reddit.scan.interval.seconds', '45', '45', false],
        ['pool.max.items', '1200', '1200', false],
        ['ui.language', 'de', 'de', false],
        ['ui.theme', 'dark', 'dark', false],
        ['net.timeout.seconds', '20', '20', false],
        ['log.level', 'INFO', 'INFO', false],
        ['agent.num.predict', '1024', '512', true],
      ].map(([key, value, def, differs]) => ({ key, value, default: def, differs }));
      entries.sort((a, b) => Number(b.differs) - Number(a.differs));
      return { differing: entries.filter(e => e.differs).length, entries };
    },

    log: () => {
      const loggers = ['de.bsommerfeld.wsbg.terminal.web.impl.net.HouseFetcher',
        'de.bsommerfeld.wsbg.terminal.agent.EditorialPipeline',
        'de.bsommerfeld.wsbg.terminal.reddit.PassiveMonitorService',
        'de.bsommerfeld.wsbg.terminal.web.impl.pool.InMemoryArticlePool'];
      let t = now - 40 * MIN;
      const lines = Array.from({ length: 120 }, () => {
        const level = Math.random() < 0.06 ? 'ERROR' : Math.random() < 0.2 ? 'WARN' : 'INFO';
        t += rnd(2000, 22_000);
        return {
          atMs: Math.min(now, t),
          level,
          logger: pick(loggers),
          thread: pick(['main', 'collector-2', 'editorial-1', 'ws-push']),
          message: level === 'ERROR' ? 'fetch failed for https://www.sec.gov/cgi-bin/browse-edgar'
            : level === 'WARN' ? 'host cooled down after 3 strikes: finanzen.net'
              : 'poured 12 articles (4 fresh) from reuters-rss',
          error: level === 'ERROR' ? 'java.net.SocketTimeoutException: read timed out' : null,
        };
      });
      const warnAndErrorByLogger = {};
      for (const l of lines) if (l.level !== 'INFO') warnAndErrorByLogger[l.logger] = (warnAndErrorByLogger[l.logger] || 0) + 1;
      return {
        lines,
        totalAppended: 18_402,
        aggregate: {
          windowStartMs: lines[0].atMs,
          lines: lines.length,
          warnCount: lines.filter(l => l.level === 'WARN').length,
          errorCount: lines.filter(l => l.level === 'ERROR').length,
          warnAndErrorByLogger,
        },
      };
    },

    // The register, with both real misresolutions in it: "Gemini" (Google's
    // model) landed on a junk coin, and "IREN" got the right letters off the
    // wrong exchange. Plus legitimately foreign paper (Rheinmetall) so the
    // identity check can be seen NOT firing on a correct German listing.
    subjects: () => {
      const u = (o) => ({
        isin: null, instrument: true, dirty: false,
        evidenceCount: 0, seenEvidenceCount: 0, newsCount: 0, coveredNewsCount: 0,
        headlineCount: 0, lastHeadline: null,
        uncomposedEvidence: false, lastComposedAtMs: 0, dirtySinceMs: 0, evidenceVersion: 0,
        firstPrice: null, firstPriceAtMs: null, market: null,
        firstSeenMs: now - 6 * 3600_000, lastActivityMs: now - 4 * MIN,
        ...o,
      });
      const units = [
        u({
          id: 'GEMINI', canonicalName: 'Gemini', ticker: 'GEMINI', dirty: true,
          firstSeenMs: now - 2 * 3600_000, lastActivityMs: now - 90_000,
          evidenceCount: 14, seenEvidenceCount: 6, newsCount: 9, coveredNewsCount: 1,
          headlineCount: 1, uncomposedEvidence: true,
          lastComposedAtMs: now - 71 * MIN, dirtySinceMs: now - 24 * MIN, evidenceVersion: 31,
          lastHeadline: { text: 'Gemini fällt um 41 % - der Markt hat das Update offenbar anders gelesen', atMs: now - 71 * MIN, sentiment: 'BEARISH' },
          firstPrice: 0.000131, firstPriceAtMs: now - 2 * 3600_000,
          market: { symbol: 'GEMINI-USD', price: 0.0001042, dayChangePercent: -41.2, currency: 'USD', exchange: 'CCC' },
        }),
        u({
          id: 'IREN', canonicalName: 'IREN Limited', ticker: 'IREN', isin: 'IT0003027817',
          firstSeenMs: now - 9 * 3600_000, lastActivityMs: now - 6 * MIN,
          evidenceCount: 22, seenEvidenceCount: 22, newsCount: 12, coveredNewsCount: 8,
          headlineCount: 4, lastComposedAtMs: now - 18 * MIN, evidenceVersion: 57,
          lastHeadline: { text: 'IREN meldet Rekord-Hashrate - Nasdaq-Papier zieht nachbörslich an', atMs: now - 18 * MIN, sentiment: 'BULLISH' },
          firstPrice: 1.79, firstPriceAtMs: now - 9 * 3600_000,
          market: { symbol: 'IREN.MI', price: 1.842, dayChangePercent: 0.6, currency: 'EUR', exchange: 'MIL' },
        }),
        u({
          id: 'NVDA', canonicalName: 'NVIDIA', ticker: 'NVDA', isin: 'US67066G1040',
          lastActivityMs: now - 40_000, dirty: true, dirtySinceMs: now - 3 * MIN, uncomposedEvidence: true,
          evidenceCount: 48, seenEvidenceCount: 44, newsCount: 26, coveredNewsCount: 21,
          headlineCount: 9, lastComposedAtMs: now - 12 * MIN, evidenceVersion: 140,
          lastHeadline: { text: 'NVIDIA über 1.200 $ - die Affen im Käfig feiern die dritte Woche in Folge', atMs: now - 12 * MIN, sentiment: 'FOMO' },
          firstPrice: 1104.2, firstPriceAtMs: now - 26 * 3600_000,
          market: { symbol: 'NVDA', price: 1208.44, dayChangePercent: 2.4, currency: 'USD', exchange: 'NMS' },
        }),
        u({
          id: 'RHM', canonicalName: 'Rheinmetall', ticker: 'RHM', isin: 'DE0007030009',
          lastActivityMs: now - 22 * MIN,
          evidenceCount: 17, seenEvidenceCount: 17, newsCount: 11, coveredNewsCount: 9,
          headlineCount: 3, lastComposedAtMs: now - 46 * MIN, evidenceVersion: 61,
          lastHeadline: { text: 'Rheinmetall zieht an - neuer Rahmenvertrag beflügelt', atMs: now - 46 * MIN, sentiment: 'BULLISH' },
          firstPrice: 512.4, firstPriceAtMs: now - 30 * 3600_000,
          market: { symbol: 'RHM.DE', price: 528.8, dayChangePercent: 1.1, currency: 'EUR', exchange: 'GER' },
        }),
        u({
          id: 'PLTR', canonicalName: 'Palantir', ticker: 'PLTR', isin: 'US69608A1088',
          lastActivityMs: now - 28 * MIN, dirty: true, dirtySinceMs: now - 27 * MIN, uncomposedEvidence: true,
          evidenceCount: 31, seenEvidenceCount: 12, newsCount: 14, coveredNewsCount: 6,
          headlineCount: 2, lastComposedAtMs: now - 3 * 3600_000, evidenceVersion: 88,
          lastHeadline: { text: 'Palantir nach Behördendeal - Hopium hält', atMs: now - 3 * 3600_000, sentiment: 'BULLISH' },
          firstPrice: 24.1, firstPriceAtMs: now - 40 * 3600_000,
          market: { symbol: 'PLTR', price: 27.62, dayChangePercent: -0.8, currency: 'USD', exchange: 'NMS' },
        }),
        u({
          id: 'zinsentscheid-ezb', canonicalName: 'Zinsentscheid der EZB', ticker: null, instrument: false,
          lastActivityMs: now - 11 * MIN,
          evidenceCount: 9, seenEvidenceCount: 9, newsCount: 7, coveredNewsCount: 5,
          headlineCount: 2, lastComposedAtMs: now - 34 * MIN, evidenceVersion: 24,
          lastHeadline: { text: 'EZB lässt die Zinsen liegen - kein Blut, kein Mond', atMs: now - 34 * MIN, sentiment: 'MIXED' },
        }),
        u({
          id: 'TSLA', canonicalName: 'Tesla', ticker: 'TSLA', isin: 'US88160R1014',
          lastActivityMs: now - 2 * MIN,
          evidenceCount: 26, seenEvidenceCount: 26, newsCount: 19, coveredNewsCount: 19,
          headlineCount: 6, lastComposedAtMs: now - 8 * MIN, evidenceVersion: 102,
          lastHeadline: { text: 'Tesla verliert 4 % - der Robotaxi-Termin rutscht wieder', atMs: now - 8 * MIN, sentiment: 'BEARISH' },
          firstPrice: 262.1, firstPriceAtMs: now - 20 * 3600_000,
          market: { symbol: 'TSLA', price: 248.9, dayChangePercent: -4.1, currency: 'USD', exchange: 'NMS' },
        }),
        u({
          id: 'SMCI', canonicalName: 'Super Micro Computer', ticker: 'SMCI', isin: 'US86800U3023',
          lastActivityMs: now - 52 * MIN,
          evidenceCount: 6, seenEvidenceCount: 6, newsCount: 4, coveredNewsCount: 0,
          headlineCount: 0, lastComposedAtMs: 0, evidenceVersion: 12,
          market: { symbol: 'SMCI', price: 41.2, dayChangePercent: 0.3, currency: 'USD', exchange: 'NMS' },
        }),
        u({
          id: 'sam-altman', canonicalName: 'Sam Altman', ticker: null, instrument: false,
          lastActivityMs: now - 64 * MIN,
          evidenceCount: 4, seenEvidenceCount: 4, newsCount: 3, coveredNewsCount: 1,
          headlineCount: 1, lastComposedAtMs: now - 80 * MIN, evidenceVersion: 9,
          lastHeadline: { text: 'Altman deutet neues Modell an - die Affen spekulieren', atMs: now - 80 * MIN, sentiment: 'FOMO' },
        }),
        u({
          id: 'BAYN', canonicalName: 'Bayer', ticker: 'BAYN', isin: 'DE000BAY0017',
          lastActivityMs: now - 3 * 3600_000,
          evidenceCount: 11, seenEvidenceCount: 11, newsCount: 8, coveredNewsCount: 8,
          headlineCount: 2, lastComposedAtMs: now - 3 * 3600_000, evidenceVersion: 40,
          lastHeadline: { text: 'Bayer bleibt im Blut - Glyphosat-Urteil kassiert', atMs: now - 3 * 3600_000, sentiment: 'BEARISH' },
          firstPrice: 27.9, firstPriceAtMs: now - 50 * 3600_000,
          market: { symbol: 'BAYN.DE', price: 26.4, dayChangePercent: -1.9, currency: 'EUR', exchange: 'GER' },
        }),
      ];
      return {
        total: units.length + 34,
        dirtyCount: units.filter(x => x.dirty).length,
        instrumentCount: units.filter(x => x.instrument).length + 28,
        shown: units.length,
        units,
      };
    },

    reddit: () => {
      const lanes = [];
      const laneDefs = [['scan', 45_000, 900], ['comments', 120_000, 2200], ['gap-fill', 300_000, 5200]];
      for (const [lane, interval, dur] of laneDefs) {
        for (let t = now - 30 * MIN; t < now; t += interval * rnd(0.85, 1.2)) {
          const failed = Math.random() < 0.08;
          lanes.push({
            atMs: t, lane,
            scope: lane === 'comments' ? 'top-24h' : 'r/wallstreetbetsGER/new',
            durationMs: dur * rnd(0.5, 1.8),
            newThreads: failed ? 0 : Math.round(rnd(0, 4)),
            newUpvotes: failed ? 0 : Math.round(rnd(0, 60)),
            newComments: failed ? 0 : Math.round(rnd(0, 22)),
            scanned: failed ? 0 : Math.round(rnd(10, 90)),
            failed,
          });
        }
      }
      lanes.sort((a, b) => a.atMs - b.atMs);
      return {
        lanes,
        throttle: {
          bucketWaitMsTotal: 92_400, bucketAcquires: 1284,
          backoffSleepMsTotal: 18_000, backoffCount: 4,
          lastRatelimitRemaining: 46, lastRatelimitSeenAtMs: now - 38_000,
        },
        sourceEvents: [
          { atMs: now - 26 * MIN, kind: 'active', detail: 'json' },
          { atMs: now - 18 * MIN, kind: 'degraded', detail: 'json: 429 twice in a row' },
          { atMs: now - 18 * MIN + 900, kind: 'switch', detail: 'json -> old.reddit html' },
          { atMs: now - 17 * MIN, kind: 'demote', detail: 'json demoted for 20m' },
          { atMs: now - 4 * MIN, kind: 'healthy', detail: 'old.reddit html delivering' },
        ],
        chain: [
          { name: 'json', active: false, demotedUntilMs: now + 3 * MIN },
          { name: 'old.reddit html', active: true, demotedUntilMs: 0 },
          { name: 'rss', active: false, demotedUntilMs: 0 },
        ],
      };
    },
  };
}

/**
 * A stand-in for bridge/socket.js: the same on()/send() surface, answering
 * `debug` requests from the fixtures after a short, realistic delay.
 */
export function fakeSocket(options = {}) {
  const handlers = new Map();
  return {
    on(type, fn) { handlers.set(type, fn); },
    isOpen: () => true,
    send(type, payload) {
      if (type !== 'debug') return true;
      const now = Date.now();
      const set = fixtures(now, options);
      const command = payload.command;
      setTimeout(() => {
        const fn = set[command];
        const respType = fn ? `debug-${command}` : 'debug-unknown';
        const handler = handlers.get(respType);
        if (!handler) return;
        handler({
          command, requestId: payload.requestId, at: Date.now(),
          data: fn ? fn() : { error: 'unknown command', commands: Object.keys(set) },
        });
      }, 60 + Math.random() * 120);
      return true;
    },
    connect() {},
  };
}
