package de.bsommerfeld.wsbg.terminal.ui.net;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.bsommerfeld.wsbg.terminal.ui.CefHost;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fetches URLs through the embedded Chromium runtime instead of a plain HTTP
 * client, so every request goes out as ordinary browser traffic — Chromium
 * manages the TLS/HTTP session, cookies, and any JS the host serves, exactly as
 * it would for a user browsing the site. Some hosts return 403/429 to a bare
 * headless Java client but serve a real browser session normally; routing
 * through Chromium keeps us on the supported, browser-equivalent path.
 *
 * <h3>The same-origin trick</h3>
 * A hidden, never-displayed browser is anchored at {@code anchorUrl} (e.g.
 * {@code https://www.reddit.com/}). Once that document is loaded, the browser's
 * main-frame origin <em>is</em> that host, so an in-page {@code fetch()} of any
 * URL on the same origin is a same-origin request — no CORS preflight, no
 * {@code Access-Control-Allow-Origin} requirement, and full access to every
 * response header (including {@code x-ratelimit-*}). The fetch runs on
 * Chromium's network stack with the document's cookies, so it is handled the
 * same way the host treats an ordinary browser session rather than the bare JDK
 * transport.
 *
 * <h3>Return channel</h3>
 * Injected JS hands results back through the {@code window.wsbgFetchQuery(...)}
 * message router registered by {@link CefHost}. Bodies are chunked (a listing
 * or deep-comment fetch can be several MB, past a single IPC message) and
 * reassembled here, keyed by a per-request id. A short client tag prefixes
 * every message so multiple {@code CefFetchClient}s (e.g. one per host) sharing
 * the one router ignore each other's traffic.
 *
 * <h3>Threading</h3>
 * {@link #fetch} is safe to call from any (non-EDT) thread and from several
 * threads at once: requests are correlated by id, and the underlying browser
 * runs each {@code fetch()} asynchronously. Browser creation is marshalled onto
 * the EDT. The first call blocks until the anchor document has finished loading.
 */
public final class CefFetchClient {

    private static final Logger LOG = LoggerFactory.getLogger(CefFetchClient.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final CefHost cefHost;
    private final String anchorUrl;
    private final String verifyUrl;
    private final String label;
    /** fetch() credentials mode: "include" (send cookies — needed for cookie-gated APIs)
     *  or "omit" (no cookies — required for a cross-origin host that answers ACAO:*). */
    private final String credentials;
    private final String clientTag = FetchWireProtocol.randomTag();

    /** Re-anchor at most this often, so a run of blocked responses can't loop-reload. */
    private static final long RELOAD_COOLDOWN_MS = 60_000;
    /**
     * How many fetches in a row may come back with NO answer before the tab is
     * torn down instead of merely re-anchored. Two: the first silence gets a
     * fresh document, and if that changes nothing the tab itself is the problem.
     */
    private static final int SILENT_STRIKES_BEFORE_REBUILD = 2;
    /** Warmup poll cadence — the quick base used while a Cloudflare JS interstitial resolves. */
    private static final long WARMUP_POLL_MS = 2_500;
    /**
     * Ceiling for the exponential back-off applied when Reddit is actively
     * throttling (503/429). We must never hammer an endpoint that's telling us
     * to wait — that's how an IP/user gets blocked, not unblocked.
     */
    private static final long WARMUP_MAX_BACKOFF_MS = 60_000;
    /**
     * Total time one warmup run polls before giving up (re-armable). The fast
     * interstitial case resolves in seconds; a sustained throttle backs off and
     * hits this budget, at which point the source layer's fallback (RSS) has long
     * since taken over and demoted this path.
     */
    /**
     * 2 min since 2026-07-14 (was 5): a healthy anchor verifies in ≤20 s; the
     * budget only ever runs out on never-ready shells (news.google.com), and
     * every minute of it costs callers the full READY_WAIT per query before
     * the fail-fast latch arms.
     */
    private static final long WARMUP_BUDGET_MS = 2 * 60_000;
    private static final Duration WARMUP_FETCH_TIMEOUT = Duration.ofSeconds(15);
    /**
     * How long the warmup poller waits for the anchor's FIRST load event before
     * probing anyway. Probing a browser whose document isn't up yet cannot
     * succeed - {@code executeJavaScript} lands nowhere, nothing ever calls
     * back, and the probe burns the full {@link #WARMUP_FETCH_TIMEOUT}. That
     * was the standing ~15 s tax on every new host (2026-08-03): the first
     * caller's {@link #ensureReady} kicks the warmup at browser-creation time,
     * ~0.6 s before the anchor is through. Waiting for the document first turns
     * "ready after 2 probe(s)" (~17.5 s) into "ready after 1 probe" (~1 s).
     * Bounded, not blocking: a page that never loads still gets probed, and the
     * warmup budget stays the real ceiling.
     */
    private static final long ANCHOR_LOAD_WAIT_MS = 20_000;

    private final AtomicBoolean started = new AtomicBoolean(false);
    /** Router handler + load listener registered exactly once — a failed browser
     *  creation must NOT re-register them on retry (they'd accumulate, audit C5). */
    private final AtomicBoolean handlersRegistered = new AtomicBoolean(false);
    /** Kept so {@link #dispose()} can unhook them from the shared CefHost plumbing. */
    private volatile CefMessageRouterHandlerAdapter routerHandler;
    private volatile java.util.function.BiConsumer<CefBrowser, Integer> loadEndListener;
    /** Set once by {@link #dispose()}; a closed client accepts no new fetches. */
    private final AtomicBoolean closed = new AtomicBoolean(false);
    /** Fetches currently in flight — a client is only evictable at zero. */
    private final java.util.concurrent.atomic.AtomicInteger inFlight =
            new java.util.concurrent.atomic.AtomicInteger();
    /**
     * Wall-clock of the last fetch that actually ANSWERED — the LRU/idle-TTL
     * signal. Stamping on the attempt (as it did until 2026-08-09) let a mute
     * tab keep itself alive forever: the poller refreshed the corpse's stamp
     * every 30 s, so the idle sweep never reached it.
     */
    private volatile long lastUsedAt = System.currentTimeMillis();
    /** Consecutive fetches that produced no answer at all — see {@link #noteSilence}. */
    private final java.util.concurrent.atomic.AtomicInteger silentStrikes =
            new java.util.concurrent.atomic.AtomicInteger();
    private final AtomicBoolean warmupRunning = new AtomicBoolean(false);
    /**
     * Counted down by the first load-end on our browser: from then on a probe
     * has a document to run in. Never reset - a re-anchor reloads an existing
     * document, it doesn't take the page away.
     */
    private final CountDownLatch anchorLoadedLatch = new CountDownLatch(1);
    private volatile CountDownLatch readyLatch = new CountDownLatch(1);
    private volatile boolean ready = false;
    /**
     * Wall-clock ms the last FULL warmup budget expired without success (0 =
     * never). While set, {@link #ensureReady} fails FAST instead of stalling
     * every caller 45 s — a known-dead anchor must not train up scheduler
     * threads (audit C2). Cleared the moment a warmup probe succeeds.
     */
    private volatile long warmupExhaustedAt = 0L;
    /**
     * Wall-clock ms until which the warmup thread is sleeping off a restricted
     * status (0 = not backing off). While in the future, the anchor is being
     * ACTIVELY refused right now and no caller should wait on the latch at all
     * — see {@link #READY_WAIT_REFUSED}. Cleared the moment a probe succeeds.
     */
    private volatile long warmupBackoffUntil = 0L;
    private volatile CefBrowser browser;
    private volatile long lastReloadAt = 0L;

    /**
     * How long a caller may wait on a HEALTHY (first-use/reloading) session.
     * 25 s since 2026-07-14: every live host verifies within ~20 s (2 probes),
     * and the old 45 s only ever mattered for never-ready anchors
     * (news.google.com's consent shell) — where each query burned the full
     * wait until the warmup budget expired. The direct fallback catches the
     * rare host that would have made it at second 30.
     */
    private static final Duration READY_WAIT = Duration.ofSeconds(25);
    /** How long a caller may wait when the last full warmup already failed. */
    private static final Duration READY_WAIT_EXHAUSTED = Duration.ofSeconds(3);
    /**
     * How long a caller may wait while the warmup is SLEEPING OFF a restricted
     * status: not at all. The latch cannot fall during that sleep, so every
     * millisecond spent on it is provably wasted.
     *
     * <p>
     * Measured 2026-08-11 on {@code cdn.finra.org}: the anchor answered 403 on
     * probe 1, the warmup did what it should and backed off (5 s, 10 s, 20 s,
     * 40 s) — and meanwhile a research collect walked ~24 daily RegSHO files, each
     * paying the full {@link #READY_WAIT_EXHAUSTED} on a session that was being
     * actively refused. Seventy seconds of waiting for a "no" that had already
     * arrived. The host cooldown in {@code WebFetchChain} cannot catch this by
     * design: it counts only an ANSWERED 429 on every strategy, and the browser
     * strategy THREW here ("a strategy that threw is not a 429, it is silence").
     * The anchor's 403 is the answer nobody was counting.
     */
    private static final Duration READY_WAIT_REFUSED = Duration.ZERO;

    private final AtomicLong nextId = new AtomicLong();
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();

    /**
     * @param anchorUrl a URL whose origin owns the resources you'll fetch
     *                  (the browser parks here so those fetches are same-origin)
     * @param verifyUrl a cheap same-origin URL that returns HTTP 200 only once
     *                  the page session is fully established — readiness is gated
     *                  on this, NOT on the anchor's page-load event, because
     *                  Cloudflare serves a 200 interstitial page first and often
     *                  resolves it silently (no second load event)
     * @param label     short name for logs (e.g. "reddit")
     */
    public CefFetchClient(CefHost cefHost, String anchorUrl, String verifyUrl, String label) {
        this(cefHost, anchorUrl, verifyUrl, label, "include");
    }

    public CefFetchClient(CefHost cefHost, String anchorUrl, String verifyUrl, String label, String credentials) {
        this.cefHost = cefHost;
        this.anchorUrl = anchorUrl;
        this.verifyUrl = verifyUrl;
        this.label = label;
        this.credentials = credentials == null ? "include" : credentials;
    }

    /** Result of one browser-driven fetch. */
    public record HttpResult(int status, String body, Map<String, String> headers) {}

    /**
     * Fetches {@code url} (which must be same-origin with the anchor) through the
     * browser. Blocks until the response arrives or {@code timeout} elapses.
     *
     * @return status, body and headers as the browser saw them
     * @throws Exception if the browser never became ready, or the response did
     *                   not arrive within {@code timeout}
     */
    public HttpResult fetch(String url, Duration timeout) throws Exception {
        return fetch(url, "GET", null, null, timeout);
    }

    /**
     * Full request form (2026-07-16, the OpenWeb leg): arbitrary method,
     * request headers and an optional string body, run as the page's own
     * {@code fetch()} — cross-origin calls carry the page's Origin and pass
     * the target's CORS exactly like the site's own widget XHRs. The classic
     * {@link #fetch(String, Duration)} delegates here with GET/no-extras and
     * behaves byte-identically to before.
     */
    public HttpResult fetch(String url, String method, Map<String, String> headers,
            String body, Duration timeout) throws Exception {
        // Hard rule: never fetch FROM the EDT. The whole JCEF pump (page loads,
        // injected JS, router replies) rides EDT cycles — a blocked EDT can't
        // pump the work that would complete this future: deadlock-until-timeout.
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException(
                    "CefFetchClient.fetch must not be called on the EDT (JCEF is EDT-pumped)");
        }
        // Three grades of patience: a healthy anchor gets the full wait, one
        // whose last full warmup expired gets a short one, and one that is
        // being refused AS WE SPEAK gets none — waiting on a latch that cannot
        // fall is the difference between a slow run and a stalled one.
        ensureReady(readyWait());
        HttpResult result;
        try {
            result = rawFetch(url, method, headers, body, timeout);
        } catch (Exception first) {
            // No reply at all — but that does NOT mean the tab is broken.
            // Measured live on 2026-08-09 through the running app's DevTools port:
            // while the Java side was logging failed fetches for this very origin,
            // the tab itself answered the identical Yahoo request with HTTP 200 in
            // 83 ms, window.wsbgFetchQuery was a function, and the message router
            // replied. The request goes out and is served; the REPLY is what gets
            // lost, intermittently (7× in 100 minutes). So re-issue once before
            // blaming the tab — the page is usually perfectly alive.
            //
            // Cost: a lost reply doubles that one fetch's worst-case latency.
            // Cheap against the alternative, which cost the EUR/USD wire half an
            // hour on the fallback source.
            LOG.debug("[{}] no reply for {} ({}) — re-issuing once", label, url,
                    first.getClass().getSimpleName());
            try {
                result = rawFetch(url, method, headers, body, timeout);
            } catch (Exception second) {
                // Twice mute: now it IS about the tab.
                noteSilence(second);
                throw second;
            }
        }
        silentStrikes.set(0);
        lastUsedAt = System.currentTimeMillis();
        // A restricted status mid-session usually means the document's session
        // went stale; reload the anchor so the next request runs against a fresh
        // page. This request still returns that status (the scraper records it),
        // but we recover automatically instead of silently dropping to RSS.
        if (isRestricted(result.status())) reloadAnchor(false);
        return result;
    }

    /**
     * A fetch that produced no answer whatsoever — the page-side {@code fetch()}
     * never reported back. Its most common cause is a dead return channel: the
     * document navigated, became a Chromium error page, or otherwise lost
     * {@code window.wsbgFetchQuery}, and then BOTH the success and the error path
     * of the injected script are mute (they share the one channel). There is no
     * status to react to, so silence itself has to be the health signal.
     *
     * <p>First strike re-anchors (bypassing {@link #RELOAD_COOLDOWN_MS} — the
     * deckel is there to stop reload storms on a LIVE session, not to keep a mute
     * one alive). A second strike tears the tab down; {@link CefWebFetcher} then
     * finds a closed client on the next fetch and builds a fresh one through its
     * acquire-or-rebuild loop. Nothing else is touched — no other origin's tab is
     * disturbed by this.
     */
    private void noteSilence(Exception cause) {
        int strikes = silentStrikes.incrementAndGet();
        String what = cause.getMessage() != null
                ? cause.getClass().getSimpleName() + ": " + cause.getMessage()
                : cause.getClass().getSimpleName();
        if (strikes >= SILENT_STRIKES_BEFORE_REBUILD) {
            LOG.info("CEF fetch '{}' stayed mute {}× ({}) — tearing the tab down; "
                    + "the next fetch builds a fresh one.", label, strikes, what);
            dispose();
        } else {
            LOG.info("CEF fetch '{}' answered nothing ({}) — re-anchoring on {}.",
                    label, what, anchorUrl);
            reloadAnchor(true);
        }
    }

    // ---- eviction seam (CefWebFetcher) -------------------------------------

    /**
     * Marks one fetch as in flight; {@code false} when this client is already
     * disposed — the caller drops its map entry and builds a fresh client
     * instead. The double-check closes the race with a concurrent
     * {@link #dispose()}: whichever side loses backs out cleanly. The LRU stamp
     * is NOT refreshed here — only a fetch that answered counts as use.
     */
    boolean tryBeginFetch() {
        if (closed.get()) return false;
        inFlight.incrementAndGet();
        if (closed.get()) {
            inFlight.decrementAndGet();
            return false;
        }
        return true;
    }

    void endFetch() {
        inFlight.decrementAndGet();
    }

    /** No fetch in flight — only then may the eviction sweep dispose this client. */
    boolean isIdle() {
        return inFlight.get() == 0;
    }

    long lastUsedAt() {
        return lastUsedAt;
    }

    /**
     * Tears the hidden browser down and unhooks this client from the shared
     * router/load-end plumbing (a leaked handler per evicted tab would
     * accumulate forever). Idempotent. Callers evict only idle clients; the
     * one remaining race — a fetch that passed {@link #tryBeginFetch} before
     * {@code closed} flipped — at worst errors out and the caller's chain
     * falls through to its direct leg. Browser close runs on the EDT with the
     * {@code setCloseAllowed() → close(true)} handshake (the
     * BrowserWindow.gracefulShutdown pattern — without the pre-approval CEF's
     * doClose vetoes and stalls ~100 s).
     */
    void dispose() {
        if (!closed.compareAndSet(false, true)) return;
        CefMessageRouterHandlerAdapter h = routerHandler;
        if (h != null) cefHost.removeFetchQueryHandler(h);
        java.util.function.BiConsumer<CefBrowser, Integer> l = loadEndListener;
        if (l != null) cefHost.removeLoadEndListener(l);
        CefBrowser b = browser;
        browser = null;
        ready = false;
        if (b != null) {
            SwingUtilities.invokeLater(() -> {
                try { b.setCloseAllowed(); } catch (Throwable ignored) {}
                try { b.close(true); } catch (Throwable ignored) {}
            });
        }
        LOG.info("CEF fetch '{}' disposed (tab eviction).", label);
    }

    /**
     * Issues one browser fetch with no readiness gating — used both by the public
     * {@link #fetch} (after the page is confirmed through) and by the warmup
     * poller (to discover when the page IS through). Requires the browser to
     * exist and a page to be loaded; callers ensure that.
     */
    private HttpResult rawFetch(String url, Duration timeout) throws Exception {
        return rawFetch(url, "GET", null, null, timeout);
    }

    private HttpResult rawFetch(String url, String method, Map<String, String> headers,
            String body, Duration timeout) throws Exception {
        long id = nextId.incrementAndGet();
        Pending p = new Pending();
        pending.put(id, p);
        try {
            String script = headers == null && body == null && "GET".equals(method)
                    ? FetchWireProtocol.buildScript(clientTag, credentials, id, url)
                    : FetchWireProtocol.buildScript(clientTag, credentials, id, url,
                            method, headers, body);
            browser.executeJavaScript(script, anchorUrl, 0);
            return p.future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            pending.remove(id);
        }
    }

    private static boolean isRestricted(int status) {
        return status == 403 || status == 429 || status == 503;
    }

    /**
     * Reads a {@code Retry-After} header (delta-seconds form) into millis, so we
     * wait at least as long as the server asked. Returns 0 when absent or in the
     * HTTP-date form (we fall back to exponential back-off there). Header lookup
     * is case-tolerant — browser {@code fetch} lowercases header names.
     */
    private static long parseRetryAfterMs(Map<String, String> headers) {
        if (headers == null) return 0L;
        String v = headers.get("retry-after");
        if (v == null) v = headers.get("Retry-After");
        if (v == null) return 0L;
        try {
            return Math.max(0L, Long.parseLong(v.trim()) * 1000L);
        } catch (NumberFormatException e) {
            return 0L; // HTTP-date form — let the exponential back-off handle it
        }
    }

    /**
     * Reloads the anchor document to refresh cookies / re-establish the session,
     * resetting readiness so the next {@link #fetch} waits for the fresh page.
     * Rate-limited by {@link #RELOAD_COOLDOWN_MS} and run off the calling thread;
     * {@code force} skips that deckel — it exists to stop reload storms on a LIVE
     * session, and a mute tab (see {@link #noteSilence}) must not be kept alive by it.
     */
    private synchronized void reloadAnchor(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastReloadAt < RELOAD_COOLDOWN_MS) return;
        lastReloadAt = now;
        CefBrowser b = browser;
        if (b == null) return;
        ready = false;
        readyLatch = new CountDownLatch(1);
        LOG.info("CEF fetch '{}': restricted status or stale session — re-anchoring on {}", label, anchorUrl);
        SwingUtilities.invokeLater(() -> b.loadURL(anchorUrl));
    }

    // ---- lifecycle --------------------------------------------------------

    /** The caller's patience, by how dead the anchor currently looks. */
    private Duration readyWait() {
        if (System.currentTimeMillis() < warmupBackoffUntil) return READY_WAIT_REFUSED;
        return warmupExhaustedAt != 0 ? READY_WAIT_EXHAUSTED : READY_WAIT;
    }

    private void ensureReady(Duration timeout) throws Exception {
        if (ready) return;
        start();
        kickWarmup(); // re-arm if a previous warmup expired without success
        CountDownLatch latch = readyLatch;
        if (!latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            // The latch may have been swapped by a concurrent reloadAnchor while
            // we waited on the old instance — readiness itself is the truth.
            if (ready) return;
            throw new IllegalStateException(
                    "CEF fetch browser for '" + label + "' session not ready within " + timeout);
        }
    }

    /**
     * Polls {@link #verifyUrl} in the background until it returns HTTP 200, then
     * flips readiness. This is the crux of the Cloudflare interaction: the anchor
     * load fires {@code onLoadEnd} on the <em>interstitial</em> page (also a 200)
     * and that page usually resolves silently afterwards (no second load event),
     * so the only reliable "session is ready" signal is an actual data fetch
     * succeeding.
     * Idempotent and re-armable: one poller at a time, and {@link #ensureReady}
     * restarts it if a run gave up.
     */
    private void kickWarmup() {
        if (ready || browser == null) return;
        if (!warmupRunning.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            try {
                long deadline = System.currentTimeMillis() + WARMUP_BUDGET_MS;
                long delay = WARMUP_POLL_MS;
                awaitAnchorDocument();
                for (int attempt = 1;
                        !ready && !closed.get() && System.currentTimeMillis() < deadline;
                        attempt++) {
                    int status = -1;
                    long retryAfterMs = 0L;
                    try {
                        HttpResult r = rawFetch(verifyUrl, WARMUP_FETCH_TIMEOUT);
                        status = r.status();
                        if (status == 200) {
                            ready = true;
                            warmupExhaustedAt = 0L; // healthy again — full patience restored
                            warmupBackoffUntil = 0L;
                            readyLatch.countDown();
                            LOG.info("CEF fetch '{}' session ready after {} probe(s).", label, attempt);
                            return;
                        }
                        retryAfterMs = parseRetryAfterMs(r.headers());
                    } catch (Exception e) {
                        LOG.debug("[{}] warmup probe {} failed: {}", label, attempt, e.getMessage());
                    }
                    // Throttle (503/429) → back off exponentially and honour
                    // Retry-After; never poll a rate-limited endpoint fast. A
                    // non-throttle non-200 (interstitial still resolving) keeps
                    // the quick base cadence so a healthy session comes up fast.
                    if (isRestricted(status)) {
                        delay = Math.max(retryAfterMs, Math.min(WARMUP_MAX_BACKOFF_MS, delay * 2));
                        // Publish the sleep so callers can skip the doomed wait.
                        warmupBackoffUntil = System.currentTimeMillis() + delay;
                        LOG.debug("[{}] warmup probe {} → HTTP {}, backing off {} ms",
                                label, attempt, status, delay);
                    } else {
                        delay = WARMUP_POLL_MS;
                        warmupBackoffUntil = 0L;
                    }
                    Thread.sleep(delay);
                }
                if (!ready && !closed.get()) {
                    warmupExhaustedAt = System.currentTimeMillis();
                    LOG.info("CEF fetch '{}' warmup gave up after {} — callers fail fast, "
                            + "fallback handles it.", label, java.time.Duration.ofMillis(WARMUP_BUDGET_MS));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                warmupRunning.set(false);
            }
        }, "cef-fetch-warmup-" + label);
        t.setDaemon(true);
        t.start();
    }

    /**
     * Holds the warmup poller until the anchor document exists (see
     * {@link #ANCHOR_LOAD_WAIT_MS}). Bounded: on timeout we probe anyway, so a
     * host that never fires load-end still runs its full budget rather than
     * silently never verifying.
     */
    private void awaitAnchorDocument() throws InterruptedException {
        if (anchorLoadedLatch.await(ANCHOR_LOAD_WAIT_MS, TimeUnit.MILLISECONDS)) return;
        LOG.debug("[{}] anchor document still not loaded after {} ms — probing anyway",
                label, ANCHOR_LOAD_WAIT_MS);
    }

    private void start() {
        if (!started.compareAndSet(false, true)) return;
        registerHandlersOnce(); // forces CEF init — must precede the cookie seed
        // Consent pre-seed BEFORE the anchor loads: a fresh profile otherwise
        // gets the EU consent shell instead of the real page (news.google.com
        // never verified — the standing "warmup gave up" blocker, 2026-07-14).
        ConsentCookieSeeder.seedFor(anchorUrl);
        createBrowserOnEdt();
    }

    /** One-shot registration — survives a failed browser creation without duplicating (audit C5).
     *  Both hooks are kept in fields so {@link #dispose()} can unhook them again. */
    private void registerHandlersOnce() {
        if (!handlersRegistered.compareAndSet(false, true)) return;

        routerHandler = new CefMessageRouterHandlerAdapter() {
            @Override
            public boolean onQuery(CefBrowser b, CefFrame f, long queryId, String request,
                    boolean persistent, CefQueryCallback callback) {
                if (request == null || !request.startsWith(clientTag)) {
                    return false; // not ours — let another client's handler try
                }
                // Nothing may escape from here: onQuery is dispatched on the
                // native UI thread (AppKit on macOS), where an escaping throwable
                // is neither logged usefully nor recoverable — it just eats this
                // reply, and with it the whole fetch, which then times out with no
                // trace. Throwable, not Exception: an Error out of the JSON/array
                // work would slip past the old guard, and callback.success() sat
                // outside it entirely.
                try {
                    handleMessage(request);
                } catch (Throwable t) {
                    LOG.debug("[{}] malformed router message: {}", label, t.toString());
                }
                try {
                    callback.success("");
                } catch (Throwable t) {
                    LOG.debug("[{}] router callback refused: {}", label, t.toString());
                }
                return true;
            }
        };
        cefHost.addFetchQueryHandler(routerHandler);

        // A page finished loading (the anchor, or an interstitial on it).
        // Don't trust this as "ready" — start the warmup poller, which confirms the
        // session is usable by fetching real data.
        loadEndListener = (b, status) -> {
            if (b != browser) return;
            anchorLoadedLatch.countDown(); // a document exists — probes can land now
            if (!ready) {
                LOG.info("CEF fetch '{}' anchor page loaded (status {}); verifying session…", label, status);
                kickWarmup();
            }
        };
        cefHost.addLoadEndListener(loadEndListener);
    }

    /**
     * Creates the (never-displayed) browser on the EDT, matching the UI path.
     * Assigning {@code browser} before the async load fires keeps the load-end
     * listener's identity check ({@code b == browser}) valid.
     */
    private void createBrowserOnEdt() {
        try {
            Runnable create = () -> browser = cefHost.createFetchBrowser(anchorUrl);
            if (SwingUtilities.isEventDispatchThread()) {
                create.run();
            } else {
                SwingUtilities.invokeAndWait(create);
            }
            LOG.info("CEF fetch browser '{}' created, loading anchor {}", label, anchorUrl);
        } catch (Exception e) {
            // Re-arm so a later call can retry rather than dead-latching forever.
            started.set(false);
            throw new RuntimeException("Failed to create CEF fetch browser for '" + label + "'", e);
        }
    }

    // ---- return channel ---------------------------------------------------

    private void handleMessage(String request) throws Exception {
        // Layout: <tag>M<total><json>      (meta, once)
        //         <tag>C<id><seq><data>  (one per chunk)
        String[] parts = request.split(String.valueOf(FetchWireProtocol.DELIM), 5);
        String type = parts[1];
        if ("M".equals(type)) {
            int total = Integer.parseInt(parts[2]);
            JsonNode meta = JSON.readTree(parts[3]);
            long id = meta.path("id").asLong();
            Pending p = pending.get(id);
            if (p == null) return;
            Map<String, String> headers = new HashMap<>();
            JsonNode h = meta.path("headers");
            h.fieldNames().forEachRemaining(k -> headers.put(k, h.path(k).asText("")));
            p.onMeta(total, meta.path("status").asInt(0), headers);
        } else if ("C".equals(type)) {
            long id = Long.parseLong(parts[2]);
            int seq = Integer.parseInt(parts[3]);
            String data = parts.length > 4 ? parts[4] : "";
            Pending p = pending.get(id);
            if (p != null) p.onChunk(seq, data);
        }
    }

    /** Accumulates the meta + chunks of one in-flight fetch until complete. */
    private static final class Pending {
        final CompletableFuture<HttpResult> future = new CompletableFuture<>();
        private final Map<Integer, String> chunks = new HashMap<>();
        private int total = -1;
        private int status;
        private Map<String, String> headers = Map.of();

        synchronized void onMeta(int total, int status, Map<String, String> headers) {
            this.total = total;
            this.status = status;
            this.headers = headers;
            maybeComplete();
        }

        synchronized void onChunk(int seq, String data) {
            chunks.put(seq, data);
            maybeComplete();
        }

        private void maybeComplete() {
            if (future.isDone() || total < 0) return;
            if (total == 0) { // error/empty sentinel
                future.complete(new HttpResult(status, "", headers));
                return;
            }
            if (chunks.size() < total) return;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < total; i++) {
                String s = chunks.get(i);
                if (s != null) sb.append(s);
            }
            future.complete(new HttpResult(status, sb.toString(), headers));
        }
    }
}
