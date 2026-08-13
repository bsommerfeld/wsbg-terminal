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
      commands: ['overview', 'sources', 'collectors', 'basin', 'runtime', 'config', 'log', 'reddit'],
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
          'digest.queue.depth': 34, 'digest.queue.capacity': 64, 'digest.queue.active': 4,
          'digest.discarded': 3,
          'digest.cache.digests': 812, 'digest.cache.fulltexts': 402, 'digest.cache.bodyHashes': 1204,
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
