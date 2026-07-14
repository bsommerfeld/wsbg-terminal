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
    /** Wall-clock of the last fetch begin — the LRU/idle-TTL eviction signal. */
    private volatile long lastUsedAt = System.currentTimeMillis();
    private final AtomicBoolean warmupRunning = new AtomicBoolean(false);
    private volatile CountDownLatch readyLatch = new CountDownLatch(1);
    private volatile boolean ready = false;
    /**
     * Wall-clock ms the last FULL warmup budget expired without success (0 =
     * never). While set, {@link #ensureReady} fails FAST instead of stalling
     * every caller 45 s — a known-dead anchor must not train up scheduler
     * threads (audit C2). Cleared the moment a warmup probe succeeds.
     */
    private volatile long warmupExhaustedAt = 0L;
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
        // Hard rule: never fetch FROM the EDT. The whole JCEF pump (page loads,
        // injected JS, router replies) rides EDT cycles — a blocked EDT can't
        // pump the work that would complete this future: deadlock-until-timeout.
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException(
                    "CefFetchClient.fetch must not be called on the EDT (JCEF is EDT-pumped)");
        }
        // Fail fast on a known-dead anchor instead of stalling every caller 45 s.
        ensureReady(warmupExhaustedAt != 0 ? READY_WAIT_EXHAUSTED : READY_WAIT);
        HttpResult result = rawFetch(url, timeout);
        // A restricted status mid-session usually means the document's session
        // went stale; reload the anchor so the next request runs against a fresh
        // page. This request still returns that status (the scraper records it),
        // but we recover automatically instead of silently dropping to RSS.
        if (isRestricted(result.status())) reloadAnchor();
        return result;
    }

    // ---- eviction seam (CefWebFetcher) -------------------------------------

    /**
     * Marks one fetch as in flight and refreshes the LRU stamp; {@code false}
     * when this client is already disposed — the caller drops its map entry and
     * builds a fresh client instead. The double-check closes the race with a
     * concurrent {@link #dispose()}: whichever side loses backs out cleanly.
     */
    boolean tryBeginFetch() {
        if (closed.get()) return false;
        inFlight.incrementAndGet();
        if (closed.get()) {
            inFlight.decrementAndGet();
            return false;
        }
        lastUsedAt = System.currentTimeMillis();
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
        long id = nextId.incrementAndGet();
        Pending p = new Pending();
        pending.put(id, p);
        try {
            browser.executeJavaScript(
                    FetchWireProtocol.buildScript(clientTag, credentials, id, url), anchorUrl, 0);
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
     * Rate-limited by {@link #RELOAD_COOLDOWN_MS} and run off the calling thread.
     */
    private synchronized void reloadAnchor() {
        long now = System.currentTimeMillis();
        if (now - lastReloadAt < RELOAD_COOLDOWN_MS) return;
        lastReloadAt = now;
        CefBrowser b = browser;
        if (b == null) return;
        ready = false;
        readyLatch = new CountDownLatch(1);
        LOG.info("CEF fetch '{}': restricted status or stale session — re-anchoring on {}", label, anchorUrl);
        SwingUtilities.invokeLater(() -> b.loadURL(anchorUrl));
    }

    // ---- lifecycle --------------------------------------------------------

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
                        LOG.debug("[{}] warmup probe {} → HTTP {}, backing off {} ms",
                                label, attempt, status, delay);
                    } else {
                        delay = WARMUP_POLL_MS;
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
                try {
                    handleMessage(request);
                } catch (Exception e) {
                    LOG.debug("[{}] malformed router message: {}", label, e.getMessage());
                }
                callback.success("");
                return true;
            }
        };
        cefHost.addFetchQueryHandler(routerHandler);

        // A page finished loading (the anchor, or an interstitial on it).
        // Don't trust this as "ready" — start the warmup poller, which confirms the
        // session is usable by fetching real data.
        loadEndListener = (b, status) -> {
            if (b == browser && !ready) {
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
