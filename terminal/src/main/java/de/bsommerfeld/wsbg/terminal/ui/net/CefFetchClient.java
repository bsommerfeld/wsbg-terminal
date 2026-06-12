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
import java.security.SecureRandom;
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
 * client, so every request carries Chromium's real TLS/HTTP fingerprint,
 * cookies, and the ability to clear JS bot-walls — the exact reason a normal
 * browser is never blocked on hosts that 403/429 a headless Java client.
 *
 * <h3>The same-origin trick</h3>
 * A hidden, never-displayed browser is anchored at {@code anchorUrl} (e.g.
 * {@code https://www.reddit.com/}). Once that document is loaded, the browser's
 * main-frame origin <em>is</em> that host, so an in-page {@code fetch()} of any
 * URL on the same origin is a same-origin request — no CORS preflight, no
 * {@code Access-Control-Allow-Origin} requirement, and full access to every
 * response header (including {@code x-ratelimit-*}). The fetch runs on
 * Chromium's network stack with the document's cookies, which is what gets it
 * past the bot detection that blocks the JDK transport.
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

    /** Field delimiter inside a router message: a control char that can't occur in a URL or header. */
    private static final char DELIM = '\u0001';

    /** Body chunk size (UTF-16 code units) per router message. */
    private static final int CHUNK = 262144;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final CefHost cefHost;
    private final String anchorUrl;
    private final String verifyUrl;
    private final String label;
    private final String clientTag = randomTag();

    /** Re-anchor at most this often, so a run of blocked responses can't loop-reload. */
    private static final long RELOAD_COOLDOWN_MS = 60_000;
    /** Warmup poll cadence and ceiling — covers a slow Cloudflare JS challenge + reCAPTCHA. */
    private static final long WARMUP_POLL_MS = 2_500;
    private static final int WARMUP_MAX_ATTEMPTS = 40; // ~100s before giving up (re-armable)
    private static final Duration WARMUP_FETCH_TIMEOUT = Duration.ofSeconds(15);

    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean warmupRunning = new AtomicBoolean(false);
    private volatile CountDownLatch readyLatch = new CountDownLatch(1);
    private volatile boolean ready = false;
    private volatile CefBrowser browser;
    private volatile long lastReloadAt = 0L;

    private final AtomicLong nextId = new AtomicLong();
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();

    /**
     * @param anchorUrl a URL whose origin owns the resources you'll fetch
     *                  (the browser parks here so those fetches are same-origin)
     * @param verifyUrl a cheap same-origin URL that returns HTTP 200 only once
     *                  the page is genuinely through any bot-wall — readiness is
     *                  gated on this, NOT on the anchor's page-load event, because
     *                  Cloudflare serves a 200 challenge page first and often
     *                  clears it silently (no second load event)
     * @param label     short name for logs (e.g. "reddit")
     */
    public CefFetchClient(CefHost cefHost, String anchorUrl, String verifyUrl, String label) {
        this.cefHost = cefHost;
        this.anchorUrl = anchorUrl;
        this.verifyUrl = verifyUrl;
        this.label = label;
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
        ensureReady(Duration.ofSeconds(45));
        HttpResult result = rawFetch(url, timeout);
        // A bot-block status mid-session means the document's clearance went
        // stale; reload the anchor so the next request runs against a freshly
        // challenged page. This request still returns the block (the scraper
        // records it), but we self-heal instead of silently rotting to RSS.
        if (isBotBlock(result.status())) reloadAnchor();
        return result;
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
            browser.executeJavaScript(buildScript(id, url), anchorUrl, 0);
            return p.future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            pending.remove(id);
        }
    }

    private static boolean isBotBlock(int status) {
        return status == 403 || status == 429 || status == 503;
    }

    /**
     * Reloads the anchor document to refresh cookies / re-clear a JS bot-wall,
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
        LOG.info("CEF fetch '{}': bot-block or stale session — re-anchoring on {}", label, anchorUrl);
        SwingUtilities.invokeLater(() -> b.loadURL(anchorUrl));
    }

    // ---- lifecycle --------------------------------------------------------

    private void ensureReady(Duration timeout) throws Exception {
        if (ready) return;
        start();
        kickWarmup(); // re-arm if a previous warmup expired without success
        CountDownLatch latch = readyLatch;
        if (!latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException(
                    "CEF fetch browser for '" + label + "' not through bot-wall within " + timeout);
        }
    }

    /**
     * Polls {@link #verifyUrl} in the background until it returns HTTP 200, then
     * flips readiness. This is the crux of the Cloudflare interaction: the anchor
     * load fires {@code onLoadEnd} on the <em>challenge</em> page (also a 200) and
     * the challenge usually clears silently afterwards (no second load event), so
     * the only reliable "we're through" signal is an actual data fetch succeeding.
     * Idempotent and re-armable: one poller at a time, and {@link #ensureReady}
     * restarts it if a run gave up.
     */
    private void kickWarmup() {
        if (ready || browser == null) return;
        if (!warmupRunning.compareAndSet(false, true)) return;
        Thread t = new Thread(() -> {
            try {
                for (int attempt = 1; attempt <= WARMUP_MAX_ATTEMPTS && !ready; attempt++) {
                    try {
                        HttpResult r = rawFetch(verifyUrl, WARMUP_FETCH_TIMEOUT);
                        if (r.status() == 200) {
                            ready = true;
                            readyLatch.countDown();
                            LOG.info("CEF fetch '{}' through bot-wall after {} probe(s).", label, attempt);
                            return;
                        }
                        LOG.debug("[{}] warmup probe {} → HTTP {}, retrying", label, attempt, r.status());
                    } catch (Exception e) {
                        LOG.debug("[{}] warmup probe {} failed: {}", label, attempt, e.getMessage());
                    }
                    Thread.sleep(WARMUP_POLL_MS);
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

        cefHost.addFetchQueryHandler(new CefMessageRouterHandlerAdapter() {
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
        });

        // A page finished loading (the anchor, or a challenge interstitial on it).
        // Don't trust this as "ready" — start the warmup poller, which confirms we
        // are actually through by fetching real data.
        cefHost.addLoadEndListener((b, status) -> {
            if (b == browser && !ready) {
                LOG.info("CEF fetch '{}' anchor page loaded (status {}); verifying bot-wall…", label, status);
                kickWarmup();
            }
        });

        // Create the (never-displayed) browser on the EDT, matching the UI path.
        // Assigning `browser` before the async load fires keeps the load-end
        // listener's identity check (b == browser) valid.
        try {
            Runnable create = () -> browser = cefHost.createBrowser(anchorUrl);
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
        String[] parts = request.split(String.valueOf(DELIM), 5);
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

    /**
     * Builds the injected fetch script. The URL and tag are emitted as JSON
     * string literals (valid JS literals) so no value can break out of the
     * script. The browser slices the body so a multi-MB response survives the
     * router's per-message ceiling.
     */
    private String buildScript(long id, String url) throws Exception {
        String jsTag = JSON.writeValueAsString(clientTag);
        String jsUrl = JSON.writeValueAsString(url);
        return "(function(){var TAG=" + jsTag + ",ID=" + id + ",URL=" + jsUrl + ",D='\\u0001';"
                + "function q(s){window.wsbgFetchQuery({request:s,onSuccess:function(){},onFailure:function(){}});}"
                + "fetch(URL,{credentials:'include',headers:{'Accept':'application/json'}}).then(function(r){"
                + "var h={};r.headers.forEach(function(v,k){h[k]=v;});"
                + "return r.text().then(function(t){"
                + "var CH=" + CHUNK + ",total=Math.max(1,Math.ceil(t.length/CH));"
                + "q(TAG+D+'M'+D+total+D+JSON.stringify({id:ID,status:r.status,len:t.length,headers:h}));"
                + "for(var i=0;i<total;i++){q(TAG+D+'C'+D+ID+D+i+D+t.substr(i*CH,CH));}"
                + "});}).catch(function(e){"
                + "q(TAG+D+'M'+D+'0'+D+JSON.stringify({id:ID,status:0,len:0,error:String(e),headers:{}}));"
                + "});})();";
    }

    private static String randomTag() {
        SecureRandom r = new SecureRandom();
        StringBuilder sb = new StringBuilder(8);
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        for (int i = 0; i < 8; i++) sb.append(alphabet.charAt(r.nextInt(alphabet.length())));
        return "wsbg" + sb;
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
