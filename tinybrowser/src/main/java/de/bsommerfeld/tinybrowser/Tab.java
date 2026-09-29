package de.bsommerfeld.tinybrowser;

import org.cef.browser.CefBrowser;

import javax.swing.SwingUtilities;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * One hidden tab, parked on a site, that fetches for it - the terminal's
 * {@code CefFetchClient} from master, carried over with what it learned.
 *
 * <h3>The same-origin trick</h3>
 * The tab loads {@code anchorUrl} (e.g. {@code https://www.reddit.com/}). From
 * then on its document's origin is that site, so a {@code fetch()} run in it
 * of any address on the same origin is a same-origin request - no CORS, every
 * response header readable - on Chromium's network stack with the site's
 * cookies. The site sees its own page asking, because it is.
 *
 * <h3>Readiness</h3>
 * The anchor's load event is not trusted: Cloudflare's interstitial is a page
 * too, and it often resolves without a second load. So a warmup poller probes
 * with a real request until the site answers without refusing. A refusal first
 * loads the anchor once more - the second visit, see {@link #revisit()} - and
 * then backs off exponentially, honouring {@code Retry-After}. Callers wait
 * on a healthy tab, fail fast on one whose warmup ran out, and get the refusal
 * itself, at once, while the site is refusing.
 *
 * <h3>Health</h3>
 * A refused request mid-session re-anchors the tab (at most once a minute). A
 * fetch without any reply is re-issued once - the reply is what gets lost,
 * not the request; twice mute re-anchors, and once more tears the tab down,
 * which {@link Tabs} replaces on the next request.
 *
 * <h3>Threading</h3>
 * {@link #fetch} is safe from any number of threads except the EDT, which
 * JCEF needs to pump the very work a fetch waits for.
 */
final class Tab {

    /** One fetch's outcome: an HTTP answer, or the reason for none ({@code failure}). */
    record Result(int status, String url, List<Map.Entry<String, String>> headers, byte[] body, String failure) {

        static Result failed(String failure) {
            return new Result(0, "", List.of(), new byte[0], failure);
        }
    }

    /** Re-anchor at most this often, so a run of refusals cannot loop-reload. */
    private static final long RELOAD_COOLDOWN_MS = 60_000;
    /**
     * How many fetches in a row may come back without any answer before the
     * tab is torn down instead of merely re-anchored: the first silence gets
     * a fresh document, and if that changes nothing the tab is the problem.
     */
    private static final int SILENT_STRIKES_BEFORE_REBUILD = 2;
    /** Warmup cadence while the site is not refusing - an interstitial resolving. */
    private static final long WARMUP_POLL_MS = 2_500;
    /** Ceiling of the warmup's back-off while the site refuses. */
    private static final long WARMUP_MAX_BACKOFF_MS = 60_000;
    /**
     * How long one warmup run probes before giving up (re-armed by the next
     * request). A healthy tab verifies in seconds; the budget only runs out
     * on pages that never let a visitor through.
     */
    private static final long WARMUP_BUDGET_MS = 2 * 60_000;
    private static final Duration WARMUP_FETCH_TIMEOUT = Duration.ofSeconds(15);
    /**
     * How long the warmup waits for the anchor's first load before probing
     * anyway: a probe into a tab without a document lands nowhere and burns
     * its whole timeout.
     */
    private static final long ANCHOR_LOAD_WAIT_MS = 20_000;
    /** How long a caller waits on a healthy tab that is still coming up. */
    private static final Duration READY_WAIT = Duration.ofSeconds(25);
    /** How long a caller waits when the last full warmup already failed. */
    private static final Duration READY_WAIT_EXHAUSTED = Duration.ofSeconds(3);
    /**
     * How long a caller waits while the warmup sleeps off a refusal: not at
     * all - the tab cannot become ready during that sleep, and the refusal is
     * the answer.
     */
    private static final Duration READY_WAIT_REFUSED = Duration.ZERO;
    /** The page-side abort, beyond the caller's own timeout. */
    private static final long PAGE_ABORT_MARGIN_MS = 30_000;

    private final Chromium chromium;
    private final String anchorUrl;
    private final String probeUrl;
    private final Map<String, String> probeHeaders;
    private final String label;
    private final String credentials;
    private final String tag = PageFetch.randomTag();

    private final AtomicBoolean started = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicInteger inFlight = new AtomicInteger();
    /**
     * When a fetch last got an answer - the eviction signal. Stamped on the
     * answer, not the attempt: a mute tab must not keep itself alive.
     */
    private volatile long lastUsedAt = System.currentTimeMillis();
    private final AtomicInteger silentStrikes = new AtomicInteger();
    private final AtomicBoolean warmupRunning = new AtomicBoolean();
    /** Falls with the anchor's first load; a re-anchor reloads, it does not take the document away. */
    private final CountDownLatch anchorLoaded = new CountDownLatch(1);
    /** Falls with the next load of the tab, whichever it is - what a {@link #revisit()} waits for. */
    private volatile CountDownLatch nextLoad = new CountDownLatch(1);
    private volatile CountDownLatch readyLatch = new CountDownLatch(1);
    private volatile boolean ready;
    /** When the last full warmup ran out without success, {@code 0} for never. */
    private volatile long warmupExhaustedAt;
    /** Until when the warmup sleeps off a refusal, {@code 0} for not. */
    private volatile long warmupBackoffUntil;
    /** The warmup's last refusing answer - what callers get while the site refuses. */
    private volatile Result lastRefusal;
    private volatile CefBrowser browser;
    private volatile long lastReloadAt;
    private volatile BiConsumer<CefBrowser, Integer> loadEndListener;

    private final AtomicLong nextId = new AtomicLong();
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();

    /**
     * @param anchorUrl    the page the tab parks on
     * @param probeUrl     what the warmup asks for to learn the site lets it through
     * @param probeHeaders the headers the probe goes out with
     * @param credentials  {@code include}: the site's cookies go along
     */
    Tab(Chromium chromium, String anchorUrl, String probeUrl, Map<String, String> probeHeaders, String label,
            String credentials) {
        this.chromium = chromium;
        this.anchorUrl = anchorUrl;
        this.probeUrl = probeUrl;
        this.probeHeaders = Map.copyOf(probeHeaders);
        this.label = label;
        this.credentials = credentials;
    }

    /**
     * Fetches through the tab. Blocks until the answer arrives or
     * {@code timeout} elapses - after the tab is ready, which a new tab takes
     * a moment for.
     *
     * @throws Exception the tab never got ready and the site did not refuse
     *                   either, or no reply came - twice
     */
    Result fetch(String url, String method, Map<String, String> headers, byte[] body, Duration timeout)
            throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Tab.fetch must not run on the EDT - JCEF is pumped there");
        }
        if (!ensureReady(readyWait())) {
            Result refusal = lastRefusal;
            if (refusal != null) {
                return refusal;
            }
            throw new IllegalStateException("tab for " + label + " not ready");
        }
        Result result;
        try {
            result = rawFetch(url, method, headers, body, timeout);
        } catch (Exception first) {
            /*
             * No reply - which does not mean the tab is broken. Measured on
             * master (2026-08-09): while fetches for an origin failed, its tab
             * answered the same request with 200 in 83 ms; the request goes
             * out and is served, the reply gets lost now and then. So once
             * more before blaming the tab.
            */
            Log.debug(label + ": no reply for " + url + ", asking once more");
            try {
                result = rawFetch(url, method, headers, body, timeout);
            } catch (Exception second) {
                noteSilence(second);
                throw second;
            }
        }
        silentStrikes.set(0);
        lastUsedAt = System.currentTimeMillis();
        // A refusal mid-session usually means the document's session went
        // stale: the next request runs against a fresh page.
        if (isRestricted(result.status())) {
            reloadAnchor(false);
        }
        return result;
    }

    /**
     * A fetch that got no reply at all. Its usual cause is a dead return
     * channel - the document navigated or became an error page - and then
     * success and failure are equally mute. First strike re-anchors, past
     * the cooldown (that is there against reload storms on a live tab, not to
     * keep a mute one alive); the second tears the tab down.
     */
    private void noteSilence(Exception cause) {
        int strikes = silentStrikes.incrementAndGet();
        String what = cause.getMessage() != null
                ? cause.getClass().getSimpleName() + ": " + cause.getMessage()
                : cause.getClass().getSimpleName();
        if (strikes >= SILENT_STRIKES_BEFORE_REBUILD) {
            Log.info(label + " stayed mute " + strikes + "x (" + what + ") - closing the tab, the next request opens a new one");
            dispose();
        } else {
            Log.info(label + " answered nothing (" + what + ") - re-anchoring on " + anchorUrl);
            reloadAnchor(true);
        }
    }

    // ---- eviction seam (Tabs) ------------------------------------------------

    /**
     * Marks one fetch as in flight; {@code false} when the tab is closed - the
     * caller replaces it. The double check closes the race with a concurrent
     * {@link #dispose()}.
     */
    boolean tryBeginFetch() {
        if (closed.get()) {
            return false;
        }
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

    /** No fetch in flight - only then may the tab be evicted. */
    boolean isIdle() {
        return inFlight.get() == 0;
    }

    long lastUsedAt() {
        return lastUsedAt;
    }

    /** Closes the tab and unhooks it from the router and the load events. Idempotent. */
    void dispose() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        chromium.removeMessages(tag);
        BiConsumer<CefBrowser, Integer> listener = loadEndListener;
        if (listener != null) {
            chromium.removeLoadEndListener(listener);
        }
        CefBrowser closing = browser;
        browser = null;
        ready = false;
        if (closing != null) {
            chromium.closeTab(closing);
        }
        pending.values().forEach(waiting -> waiting.future.complete(Result.failed("tab closed")));
        Log.info(label + ": tab closed");
    }

    // ---- one fetch -----------------------------------------------------------

    /** One page-side fetch, without waiting for readiness - for callers once ready, and for the warmup. */
    private Result rawFetch(String url, String method, Map<String, String> headers, byte[] body, Duration timeout)
            throws Exception {
        CefBrowser tab = browser;
        if (tab == null) {
            throw new IllegalStateException("tab for " + label + " is closed");
        }
        long id = nextId.incrementAndGet();
        Pending waiting = new Pending();
        pending.put(id, waiting);
        try {
            tab.executeJavaScript(PageFetch.script(chromium.queryFunction(), tag, credentials, id, url, method,
                    headers, body, timeout.toMillis() + PAGE_ABORT_MARGIN_MS), anchorUrl, 0);
            return waiting.future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            pending.remove(id);
        }
    }

    static boolean isRestricted(int status) {
        return status == 403 || status == 429 || status == 503;
    }

    /** {@code Retry-After} in delta seconds, as milliseconds; {@code 0} when absent or a date. */
    private static long retryAfterMillis(List<Map.Entry<String, String>> headers) {
        for (Map.Entry<String, String> header : headers) {
            if (header.getKey().equalsIgnoreCase("retry-after")) {
                try {
                    return Math.max(0L, Long.parseLong(header.getValue().trim()) * 1000L);
                } catch (NumberFormatException date) {
                    return 0L;
                }
            }
        }
        return 0L;
    }

    /**
     * Reloads the anchor to renew the session, and makes the next fetch wait
     * for the fresh page. {@code force} skips {@link #RELOAD_COOLDOWN_MS}.
     */
    private synchronized void reloadAnchor(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && now - lastReloadAt < RELOAD_COOLDOWN_MS) {
            return;
        }
        lastReloadAt = now;
        CefBrowser tab = browser;
        if (tab == null) {
            return;
        }
        ready = false;
        readyLatch = new CountDownLatch(1);
        Log.info(label + ": refused or stale - re-anchoring on " + anchorUrl);
        SwingUtilities.invokeLater(() -> tab.loadURL(anchorUrl));
    }

    // ---- lifecycle -----------------------------------------------------------

    /** The caller's patience, by how dead the tab currently looks. */
    private Duration readyWait() {
        if (System.currentTimeMillis() < warmupBackoffUntil) {
            return READY_WAIT_REFUSED;
        }
        return warmupExhaustedAt != 0 ? READY_WAIT_EXHAUSTED : READY_WAIT;
    }

    private boolean ensureReady(Duration timeout) throws Exception {
        if (ready) {
            return true;
        }
        start();
        kickWarmup();
        CountDownLatch latch = readyLatch;
        // The latch may be swapped by a re-anchor meanwhile - readiness itself is the truth.
        return latch.await(timeout.toMillis(), TimeUnit.MILLISECONDS) || ready;
    }

    /**
     * Probes in the background until the site answers without refusing, then
     * flips readiness. One poller at a time; re-armed by the next caller if a
     * run gave up.
     */
    private void kickWarmup() {
        if (ready || browser == null || !warmupRunning.compareAndSet(false, true)) {
            return;
        }
        Thread.ofVirtual().name("tinybrowser-warmup-" + label).start(() -> {
            try {
                long deadline = System.currentTimeMillis() + WARMUP_BUDGET_MS;
                long delay = WARMUP_POLL_MS;
                awaitAnchorDocument();
                for (int attempt = 1; !ready && !closed.get() && System.currentTimeMillis() < deadline; attempt++) {
                    Result probe = null;
                    try {
                        probe = rawFetch(probeUrl, "GET", probeHeaders, null, WARMUP_FETCH_TIMEOUT);
                    } catch (Exception e) {
                        Log.debug(label + ": warmup probe " + attempt + " failed: " + e.getMessage());
                    }
                    if (probe != null && probe.status() > 0 && !isRestricted(probe.status())) {
                        lastRefusal = null;
                        warmupExhaustedAt = 0L;
                        warmupBackoffUntil = 0L;
                        ready = true;
                        readyLatch.countDown();
                        Log.info(label + ": ready after " + attempt + " probe(s)");
                        return;
                    }
                    // A refusal backs off and honours Retry-After - a site
                    // saying no is never polled fast. Anything else is an
                    // interstitial still resolving: the quick cadence.
                    if (probe != null && isRestricted(probe.status())) {
                        lastRefusal = probe;
                        if (revisit()) {
                            continue;
                        }
                        delay = Math.max(retryAfterMillis(probe.headers()),
                                Math.min(WARMUP_MAX_BACKOFF_MS, delay * 2));
                        warmupBackoffUntil = System.currentTimeMillis() + delay;
                        Log.debug(label + ": warmup probe " + attempt + " refused with " + probe.status()
                                + ", backing off " + delay + " ms");
                    } else {
                        delay = WARMUP_POLL_MS;
                        warmupBackoffUntil = 0L;
                    }
                    Thread.sleep(delay);
                }
                if (!ready && !closed.get()) {
                    warmupExhaustedAt = System.currentTimeMillis();
                    Log.info(label + ": warmup gave up after " + Duration.ofMillis(WARMUP_BUDGET_MS)
                            + " - requests fail fast until the next one");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                warmupRunning.set(false);
            }
        });
    }

    /**
     * Loads the anchor once more after a refusal, as a person presses reload,
     * and waits for the page - at most once per {@link #RELOAD_COOLDOWN_MS}.
     *
     * <p>A site's first-visit check often wants exactly that. Measured on
     * Reddit (2026-09-29) with a fresh profile: the first visit gets "Prove
     * your humanity" and every request 403, but sets {@code csv} and
     * {@code edgebucket}; with those, the second visit gets a script check
     * that submits itself, then the real page with the visitor session
     * ({@code loid}, {@code token_v2}), and the JSON answers 200. Nothing is
     * solved here: a CAPTCHA on the second visit stays one.
     *
     * @return whether the anchor was loaded again
     */
    private boolean revisit() throws InterruptedException {
        CefBrowser tab = browser;
        synchronized (this) {
            long now = System.currentTimeMillis();
            if (tab == null || now - lastReloadAt < RELOAD_COOLDOWN_MS) {
                return false;
            }
            lastReloadAt = now;
        }
        CountDownLatch loaded = new CountDownLatch(1);
        nextLoad = loaded;
        Log.info(label + ": refused on arrival - visiting " + anchorUrl + " again");
        SwingUtilities.invokeLater(() -> tab.loadURL(anchorUrl));
        if (!loaded.await(ANCHOR_LOAD_WAIT_MS, TimeUnit.MILLISECONDS)) {
            Log.debug(label + ": second visit still not loaded after " + ANCHOR_LOAD_WAIT_MS + " ms - probing anyway");
        }
        return true;
    }

    private void awaitAnchorDocument() throws InterruptedException {
        if (!anchorLoaded.await(ANCHOR_LOAD_WAIT_MS, TimeUnit.MILLISECONDS)) {
            Log.debug(label + ": anchor still not loaded after " + ANCHOR_LOAD_WAIT_MS + " ms - probing anyway");
        }
    }

    private void start() throws Exception {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        chromium.onMessages(tag, this::handleMessage);
        loadEndListener = (loaded, status) -> {
            if (loaded != browser) {
                return;
            }
            anchorLoaded.countDown();
            nextLoad.countDown();
            loaded.getSource(source -> Log.debug(label + ": anchor document " + describe(source)));
            if (!ready) {
                Log.info(label + ": anchor loaded (" + status + "), verifying the session");
                kickWarmup();
            }
        };
        chromium.addLoadEndListener(loadEndListener);
        // Before the anchor loads: a fresh profile gets a consent wall otherwise.
        ConsentCookies.seedFor(anchorUrl);
        try {
            chromium.openTab(anchorUrl, created -> browser = created);
            Log.info(label + ": tab opened on " + anchorUrl);
        } catch (Exception e) {
            dispose();
            throw new IllegalStateException("cannot open a tab for " + label, e);
        }
    }

    // ---- the way home ----------------------------------------------------------

    /** One router message of this tab, in {@link PageFetch}'s layout. Runs on the browser UI thread. */
    private void handleMessage(String message) {
        String[] parts = message.split(String.valueOf(PageFetch.DELIMITER), 3);
        char type = parts[1].charAt(0);
        String rest = parts[2];
        switch (type) {
            case 'M' -> {
                String[] fields = rest.split(String.valueOf(PageFetch.DELIMITER), 5);
                Pending waiting = pending.get(Long.parseLong(fields[0]));
                if (waiting != null) {
                    waiting.onMeta(Integer.parseInt(fields[1]), Integer.parseInt(fields[2]), fields[3],
                            headers(fields.length > 4 ? fields[4] : ""));
                }
            }
            case 'C' -> {
                String[] fields = rest.split(String.valueOf(PageFetch.DELIMITER), 3);
                Pending waiting = pending.get(Long.parseLong(fields[0]));
                if (waiting != null) {
                    waiting.onChunk(Integer.parseInt(fields[1]), fields.length > 2 ? fields[2] : "");
                }
            }
            case 'E' -> {
                String[] fields = rest.split(String.valueOf(PageFetch.DELIMITER), 2);
                Pending waiting = pending.get(Long.parseLong(fields[0]));
                if (waiting != null) {
                    waiting.future.complete(Result.failed("page fetch failed: " + (fields.length > 1 ? fields[1] : "")));
                }
            }
            default -> Log.debug(label + ": unknown message type " + type);
        }
    }

    /** One line for the log: the document's title and size. */
    static String describe(String html) {
        String lower = html.toLowerCase(java.util.Locale.ROOT);
        int start = lower.indexOf("<title>");
        int end = lower.indexOf("</title>");
        String title = start >= 0 && end > start ? html.substring(start + 7, end).trim() : "?";
        return "\"" + title + "\", " + html.length() + " chars";
    }

    static List<Map.Entry<String, String>> headers(String joined) {
        List<Map.Entry<String, String>> headers = new ArrayList<>();
        if (joined.isEmpty()) {
            return headers;
        }
        String[] parts = joined.split(String.valueOf(PageFetch.HEADER_DELIMITER), -1);
        for (int i = 0; i + 1 < parts.length; i += 2) {
            headers.add(Map.entry(parts[i], parts[i + 1]));
        }
        return headers;
    }

    /** Collects the meta and the chunks of one fetch until it is whole. */
    private static final class Pending {
        final CompletableFuture<Result> future = new CompletableFuture<>();
        private final Map<Integer, String> chunks = new HashMap<>();
        private int total = -1;
        private int status;
        private String url = "";
        private List<Map.Entry<String, String>> headers = List.of();

        synchronized void onMeta(int total, int status, String url, List<Map.Entry<String, String>> headers) {
            this.total = total;
            this.status = status;
            this.url = url;
            this.headers = headers;
            maybeComplete();
        }

        synchronized void onChunk(int sequence, String data) {
            chunks.put(sequence, data);
            maybeComplete();
        }

        private void maybeComplete() {
            if (future.isDone() || total < 0 || chunks.size() < total) {
                return;
            }
            /*
             * This runs on the browser UI thread. Joining and decoding a body
             * of several MB there would hold up every tab; the thread waiting
             * for it does it instead.
            */
            int count = total;
            Map<Integer, String> parts = new HashMap<>(chunks);
            Result meta = new Result(status, url, headers, null, null);
            future.completeAsync(() -> {
                StringBuilder encoded = new StringBuilder();
                for (int i = 0; i < count; i++) {
                    String part = parts.get(i);
                    if (part != null) {
                        encoded.append(part);
                    }
                }
                byte[] body = Base64.getDecoder().decode(encoded.toString());
                return new Result(meta.status(), meta.url(), meta.headers(), body, null);
            });
        }
    }
}
