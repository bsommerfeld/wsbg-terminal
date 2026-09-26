package de.bsommerfeld.tinyfetch.api;

import de.bsommerfeld.tinyfetch.browser.BrowserHeaders;
import de.bsommerfeld.tinyfetch.cache.ValidatorCache;
import de.bsommerfeld.tinyfetch.curl.CurlEasy;
import de.bsommerfeld.tinyfetch.curl.CurlException;
import de.bsommerfeld.tinyfetch.curl.CurlLibrary;
import de.bsommerfeld.tinyfetch.curl.CurlRequest;
import de.bsommerfeld.tinyfetch.curl.CurlResponse;
import de.bsommerfeld.tinyfetch.curl.CurlShare;
import de.bsommerfeld.tinyfetch.curl.NativeLibraryLocator;
import de.bsommerfeld.tinyfetch.curl.NativePlatform;
import de.bsommerfeld.tinyfetch.pacing.HostPacer;
import de.bsommerfeld.tinyfetch.pacing.WallDetector;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.random.RandomGenerator;

/**
 * HTTP client that looks and behaves like one person using a browser.
 *
 * <h2>What "like a person" means here</h2>
 * The traffic is meant to be indistinguishable from someone browsing the same
 * pages - not because of how it is disguised, but because of how it behaves.
 * Both halves are enforced by this class, not left to the caller:
 *
 * <h3>It looks like a browser</h3>
 * <ul>
 *   <li><b>Fingerprint.</b> TLS ClientHello, HTTP/2 settings and header order
 *       are Chrome's (libcurl-impersonate, see {@link Browser}); the user agent
 *       names the machine's real OS. A plain Java client is recognisable from
 *       its handshake alone, whatever user agent it claims.</li>
 *   <li><b>How the request came about.</b> {@link FetchRequest#page} and
 *       {@link FetchRequest#data} send the headers of a navigation and of a
 *       page script respectively, referer and {@code sec-fetch-*} included.</li>
 *   <li><b>One browser, one memory.</b> One cookie jar for all hosts, resumed
 *       TLS sessions, kept-alive connections, and conditional requests
 *       ({@code If-None-Match}) for anything seen before - optionally carried
 *       across restarts ({@link Builder#cookieFile}).</li>
 * </ul>
 *
 * <h3>It behaves like a person</h3>
 * <ul>
 *   <li><b>One thing at a time per host.</b> Requests to a host queue and go
 *       out one after another, never in parallel bursts.</li>
 *   <li><b>A human pace.</b> Consecutive requests to a host are
 *       {@link HostPolicy#minInterval()} apart plus random jitter; a nearly
 *       spent rate budget ({@code x-ratelimit-*}) waits for its reset.</li>
 *   <li><b>Takes no for an answer.</b> A {@link Wall} - throttle, refusal,
 *       CAPTCHA - pauses the host with a doubling back-off; during the pause
 *       every request fails at once ({@link CooldownException}) without
 *       reaching the network. Challenges are never solved or worked around.</li>
 * </ul>
 * The pace and back-off per host come from {@link HostPolicy}. Set them for
 * what a person would plausibly do on that site, never tighter than the site
 * documents or signals.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * try (TinyFetch fetch = TinyFetch.builder()
 *         .acceptLanguage("de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7")
 *         .policy("www.reddit.com", HostPolicy.defaults().withMinInterval(Duration.ofSeconds(6)))
 *         .cookieFile(dataDirectory.resolve("cookies.txt"))
 *         .build()) {
 *
 *     // The person opens the page ...
 *     FetchResponse page = fetch.fetch(FetchRequest.page("https://www.ls-tc.de/de/aktie/34313"));
 *
 *     // ... whose script then loads its data.
 *     FetchResponse chart = fetch.fetch(
 *             FetchRequest.data("https://www.ls-tc.de/_rpc/json/instrument/chart/dataForInstrument?...")
 *                     .referer("https://www.ls-tc.de/de/aktie/34313"));
 *
 *     if (chart.ok()) {
 *         parse(chart.text());
 *     } else if (chart.wall() != Wall.NONE) {
 *         // the host is paused now - take the fallback
 *     }
 * } catch (CooldownException paused) {
 *     // paused.until(): nothing was sent
 * }
 * }</pre>
 *
 * <h2>Sites that only open to a browser</h2>
 * Some sites issue their session only to a visitor that runs their scripts.
 * With a {@link SessionUnlocker} ({@link Builder#unlocker}), {@link #unlock}
 * has a real browser engine open the page once and takes the session over;
 * every request after that is TinyFetch again. The engine and this client
 * present as the same {@link Browser}. If the site wants a person, the
 * unlocker's {@link CaptchaSolver} lets one solve it - the program never does.
 *
 * <h2>Threads</h2>
 * {@link #fetch} is safe from any number of threads, virtual ones included.
 * It blocks for the host's pace, so a caller must not hold anything others
 * need while it waits. Requests to different hosts run in parallel.
 *
 * <h2>Native library</h2>
 * Needs libcurl-impersonate ({@code .script/natives.sh}, found as described
 * in {@link Builder#library}) and {@code --enable-native-access=de.bsommerfeld.tinyfetch}.
 */
public final class TinyFetch implements Fetcher, BrowserSession, AutoCloseable {

    private static final System.Logger LOG = System.getLogger("de.bsommerfeld.tinyfetch");

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);

    private final CurlLibrary library;
    private final CurlShare share;
    private final Browser browser;
    private final BrowserHeaders headers;
    private final HostPolicy defaultPolicy;
    private final Map<String, HostPolicy> policies;
    private final Path cookieFile;
    private final Path caBundle;
    private final long maxBodyBytes;
    private final ValidatorCache cache;
    private final SessionUnlocker unlocker;
    private final RandomGenerator random = RandomGenerator.getDefault();

    private final Map<String, HostSession> sessions = new ConcurrentHashMap<>();
    private volatile boolean closed;

    private TinyFetch(Builder builder, CurlLibrary library) throws FetchException {
        this.library = library;
        this.browser = builder.browser;
        this.headers = new BrowserHeaders(builder.browser, NativePlatform.current(), builder.acceptLanguage());
        this.defaultPolicy = builder.defaultPolicy;
        this.policies = Map.copyOf(builder.policies);
        this.cookieFile = builder.cookieFile;
        this.caBundle = builder.caBundle;
        this.maxBodyBytes = builder.maxBodyBytes;
        this.cache = new ValidatorCache(builder.cacheEntries);
        this.unlocker = builder.unlocker;
        try {
            this.share = new CurlShare(library);
        } catch (CurlException e) {
            throw new FetchException("cannot set up the cookie jar: " + e.getMessage(), e);
        }
        loadCookies();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Whether libcurl-impersonate can be found where {@link Builder#library}
     * looks without an explicit path - for a caller that would rather run
     * without TinyFetch than fail.
     */
    public static boolean libraryAvailable() {
        try {
            NativeLibraryLocator.locate();
            return true;
        } catch (CurlException e) {
            return false;
        }
    }

    @Override
    public FetchResponse fetch(FetchRequest request) throws FetchException, InterruptedException {
        Objects.requireNonNull(request, "request");
        ensureOpen();
        HostSession session = sessions.computeIfAbsent(request.host(), this::openSession);

        /*
         * The host lock is held across the wait and the transfer: that is what
         * queues a host's requests one behind the other, and fair ordering
         * keeps them first come, first served.
        */
        session.lock.lockInterruptibly();
        try {
            ensureOpen();
            Optional<Long> pausedUntil = session.pacer.pausedUntil();
            if (pausedUntil.isPresent()) {
                throw new CooldownException(request.host(), Instant.ofEpochMilli(pausedUntil.get()),
                        session.pacer.pauseReason());
            }
            long wait = session.pacer.waitMillis();
            if (wait > 0) {
                Thread.sleep(wait);
            }
            return exchange(session, request);
        } finally {
            session.lock.unlock();
        }
    }

    @Override
    public boolean hasCookie(String host, String name) {
        String target = host.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis() / 1000;
        try {
            for (String line : share.exportCookies()) {
                String[] fields = line.split("\t");
                if (fields.length < 7 || !fields[5].equals(name)) {
                    continue;
                }
                String domain = fields[0].replaceFirst("^#HttpOnly_", "").replaceFirst("^\\.", "")
                        .toLowerCase(Locale.ROOT);
                boolean matches = target.equals(domain) || target.endsWith("." + domain);
                long expires = parseLong(fields[4]);
                if (matches && (expires == 0 || expires > now)) {
                    return true;
                }
            }
        } catch (CurlException e) {
            LOG.log(System.Logger.Level.WARNING, "cookie jar unreadable: " + e.getMessage());
        }
        return false;
    }

    @Override
    public boolean canUnlock() {
        return unlocker != null;
    }

    /**
     * Has the {@link SessionUnlocker}'s engine open {@code url} and takes the
     * site's cookies over - once per host at a time; a second caller waits and
     * finds the cookies there. A host paused after a wall may be unlocked: the
     * unlock is what ends the pause. A CAPTCHA nobody solved pauses it like
     * {@link Wall#CHALLENGE}.
     */
    @Override
    public void unlock(String url, Set<String> awaitCookies) throws FetchException, InterruptedException {
        if (unlocker == null) {
            throw new IllegalStateException("no SessionUnlocker configured");
        }
        ensureOpen();
        URI page = URI.create(url);
        String host = page.getHost().toLowerCase(Locale.ROOT);
        HostSession session = sessions.computeIfAbsent(host, this::openSession);
        session.unlockLock.lockInterruptibly();
        try {
            if (!awaitCookies.isEmpty() && awaitCookies.stream().allMatch(name -> hasCookie(host, name))) {
                return;
            }
            List<String> cookies;
            try {
                cookies = unlocker.unlock(page, awaitCookies);
            } catch (CaptchaRequiredException e) {
                session.pacer.recordAnswer(Wall.CHALLENGE, name -> Optional.empty());
                throw e;
            }
            try {
                share.importCookies(cookies);
            } catch (CurlException e) {
                throw new FetchException("cannot take the session over: " + e.getMessage(), e);
            }
            session.pacer.clearPause();
            LOG.log(System.Logger.Level.INFO, "{0} unlocked, {1} cookie(s) taken over", host, cookies.size());
        } finally {
            session.unlockLock.unlock();
        }
    }

    /**
     * When {@code host} may be asked again, if it is paused after a wall.
     */
    public Optional<Instant> pausedUntil(String host) {
        HostSession session = sessions.get(host.toLowerCase(Locale.ROOT));
        return session == null ? Optional.empty() : session.pacer.pausedUntil().map(Instant::ofEpochMilli);
    }

    /** The user agent every request carries. */
    public String userAgent() {
        return headers.userAgent();
    }

    /** The loaded native library, e.g. {@code libcurl/8.x BoringSSL ...}. */
    public String libraryVersion() {
        return library.version();
    }

    /**
     * Cancels transfers in flight, saves the cookie jar if a file is set and
     * releases every connection. Requests afterwards fail.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        saveCookies();
        for (HostSession session : sessions.values()) {
            session.lock.lock();
            try {
                session.close();
            } finally {
                session.lock.unlock();
            }
        }
        sessions.clear();
        share.close();
    }

    // ---- one exchange -----------------------------------------------------

    private FetchResponse exchange(HostSession session, FetchRequest request)
            throws FetchException, InterruptedException {
        String url = request.uri().toString();

        /*
         * The cache only takes part when the request is a plain GET and the
         * caller has not asked a conditional question of their own - then a
         * 304 is theirs to read.
        */
        boolean cacheable = request.method().equals("GET")
                && !request.hasHeader("if-none-match") && !request.hasHeader("if-modified-since");
        Optional<ValidatorCache.Entry> cached = cacheable ? cache.get(url) : Optional.empty();
        Map<String, String> conditional = cached.map(ValidatorCache.Entry::conditionalHeaders).orElse(Map.of());

        CurlRequest curlRequest = new CurlRequest(url, request.method(), headers.build(request, conditional),
                request.body(), browser.impersonateTarget(), BrowserHeaders.ACCEPT_ENCODING,
                request.timeout(), CONNECT_TIMEOUT, maxBodyBytes, caBundle,
                () -> closed || Thread.currentThread().isInterrupted());

        CurlResponse raw;
        try {
            raw = session.easy().perform(curlRequest);
        } catch (CurlException e) {
            session.pacer.recordNoAnswer();
            if (e.code() == CurlException.ABORTED && Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("interrupted during " + request);
            }
            if (e.code() == CurlException.ABORTED && closed) {
                throw new FetchException("closed during " + request);
            }
            throw new FetchException(request + ": " + e.getMessage(), e);
        }

        Map<String, List<String>> responseHeaders = FetchResponse.parseHeaderLines(raw.headerLines());
        FetchResponse response = new FetchResponse(raw.status(), URI.create(raw.effectiveUrl()), raw.httpVersion(),
                responseHeaders, raw.body(), Wall.NONE, false);
        Wall wall = WallDetector.classify(raw.status(), response::header, raw.body());
        if (wall != Wall.NONE) {
            response = new FetchResponse(raw.status(), response.url(), raw.httpVersion(), responseHeaders,
                    raw.body(), wall, false);
        }
        session.pacer.recordAnswer(wall, response::header);

        if (wall != Wall.NONE) {
            LOG.log(System.Logger.Level.WARNING, "{0} answered {1} ({2}) - paused until {3}",
                    request.host(), raw.status(), wall,
                    session.pacer.pausedUntil().map(Instant::ofEpochMilli).orElse(Instant.now()));
        }

        if (raw.status() == 304 && cached.isPresent()) {
            return response.asRevalidated(cached.get().body(), cached.get().headers());
        }
        if (cacheable) {
            cache.store(url, raw.status(), response.header("etag"), response.header("last-modified"),
                    raw.body(), responseHeaders);
        }
        return response;
    }

    private HostSession openSession(String host) {
        HostPolicy policy = policies.getOrDefault(host, defaultPolicy);
        return new HostSession(new HostPacer(policy, System::currentTimeMillis, random));
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void ensureOpen() throws FetchException {
        if (closed) {
            throw new FetchException("TinyFetch is closed");
        }
    }

    // ---- cookie persistence -----------------------------------------------

    private void loadCookies() {
        if (cookieFile == null || !Files.isRegularFile(cookieFile)) {
            return;
        }
        try {
            share.importCookies(Files.readAllLines(cookieFile));
        } catch (IOException | CurlException e) {
            LOG.log(System.Logger.Level.WARNING, "cookie jar " + cookieFile + " not loaded: " + e.getMessage());
        }
    }

    /**
     * Written through a temporary file and moved into place, owner-readable
     * only where the file system knows permissions: the jar holds sessions.
     */
    private void saveCookies() {
        if (cookieFile == null) {
            return;
        }
        try {
            List<String> lines = share.exportCookies();
            Path directory = cookieFile.toAbsolutePath().getParent();
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, "cookies", ".tmp");
            try {
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException notPosix) {
                // Windows: the user profile's ACLs already keep it private.
            }
            Files.write(temporary, lines);
            Files.move(temporary, cookieFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | CurlException e) {
            LOG.log(System.Logger.Level.WARNING, "cookie jar " + cookieFile + " not saved: " + e.getMessage());
        }
    }

    // ---- per-host state ---------------------------------------------------

    /** One host: its queue, its pace, its connection. */
    private final class HostSession {
        final ReentrantLock lock = new ReentrantLock(true);
        /** Separate from {@link #lock}: an unlock may wait minutes for a person, requests must not. */
        final ReentrantLock unlockLock = new ReentrantLock();
        final HostPacer pacer;
        private CurlEasy easy;

        HostSession(HostPacer pacer) {
            this.pacer = pacer;
        }

        /** Opened on first use; the caller holds {@link #lock}. */
        CurlEasy easy() throws FetchException {
            if (easy == null) {
                try {
                    easy = new CurlEasy(library, share);
                } catch (CurlException e) {
                    throw new FetchException("cannot open a connection handle: " + e.getMessage(), e);
                }
            }
            return easy;
        }

        void close() {
            if (easy != null) {
                easy.close();
                easy = null;
            }
        }
    }

    // ---- builder ------------------------------------------------------------

    /** Configures a {@link TinyFetch}; every setting has a working default. */
    public static final class Builder {

        private Browser browser = Browser.CHROME;
        private String acceptLanguage;
        private SessionUnlocker unlocker;
        private HostPolicy defaultPolicy = HostPolicy.defaults();
        private final Map<String, HostPolicy> policies = new HashMap<>();
        private Path cookieFile;
        private Path library;
        private Path caBundle;
        private long maxBodyBytes = 32L * 1024 * 1024;
        private int cacheEntries = 256;

        private Builder() {
        }

        /** The browser to present as; {@link Browser#CHROME} unless set. */
        public Builder browser(Browser browser) {
            this.browser = Objects.requireNonNull(browser, "browser");
            return this;
        }

        /**
         * The {@code accept-language} header - what the person's browser is
         * set to. Unless set: the browser's own default where it has one
         * ({@link Browser#defaultAcceptLanguage()}), German first otherwise,
         * as the terminal's users are.
         */
        public Builder acceptLanguage(String acceptLanguage) {
            this.acceptLanguage = Objects.requireNonNull(acceptLanguage, "acceptLanguage");
            return this;
        }

        /**
         * The engine for {@link TinyFetch#unlock}. It must be the same
         * browser as this client ({@link #browser}), or {@link #build} refuses.
         */
        public Builder unlocker(SessionUnlocker unlocker) {
            this.unlocker = unlocker;
            return this;
        }

        String acceptLanguage() {
            if (acceptLanguage != null) {
                return acceptLanguage;
            }
            return browser.defaultAcceptLanguage() != null
                    ? browser.defaultAcceptLanguage() : "de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7";
        }

        /** The policy of every host without its own; {@link HostPolicy#defaults()} unless set. */
        public Builder defaultPolicy(HostPolicy policy) {
            this.defaultPolicy = Objects.requireNonNull(policy, "policy");
            return this;
        }

        /** The policy of one host, matched exactly (case-insensitive). */
        public Builder policy(String host, HostPolicy policy) {
            policies.put(host.toLowerCase(Locale.ROOT), Objects.requireNonNull(policy, "policy"));
            return this;
        }

        /**
         * Keeps the cookie jar in this file across runs (Netscape format),
         * loaded at build, saved at {@link TinyFetch#close()} - a returning
         * visitor instead of a stranger on every start.
         */
        public Builder cookieFile(Path cookieFile) {
            this.cookieFile = cookieFile;
            return this;
        }

        /**
         * The libcurl-impersonate file. Unless set: system property
         * {@code tinyfetch.library}, then {@code tinyfetch.library.dir}
         * (directly or under {@code <platform>/}), then the directory of the
         * TinyFetch jar.
         */
        public Builder library(Path library) {
            this.library = library;
            return this;
        }

        /** A PEM bundle of trusted roots, where the library's default store is not the right one. */
        public Builder caBundle(Path caBundle) {
            this.caBundle = caBundle;
            return this;
        }

        /** Largest body accepted; bigger ones fail the request. 32 MB unless set. */
        public Builder maxBodyBytes(long maxBodyBytes) {
            if (maxBodyBytes <= 0 || maxBodyBytes > Integer.MAX_VALUE - 8) {
                throw new IllegalArgumentException("maxBodyBytes out of range: " + maxBodyBytes);
            }
            this.maxBodyBytes = maxBodyBytes;
            return this;
        }

        /** How many URLs the revalidation cache remembers; 256 unless set. */
        public Builder cacheEntries(int cacheEntries) {
            if (cacheEntries < 0) {
                throw new IllegalArgumentException("cacheEntries must not be negative");
            }
            this.cacheEntries = cacheEntries;
            return this;
        }

        /**
         * @throws FetchException if the native library cannot be found or loaded
         */
        public TinyFetch build() throws FetchException {
            if (unlocker != null && unlocker.browser() != browser) {
                throw new IllegalStateException("the unlocker is " + unlocker.browser() + " but this client "
                        + browser + " - a session issued to one would come back from the other");
            }
            try {
                Path path = library != null ? library : NativeLibraryLocator.locate();
                return new TinyFetch(this, CurlLibrary.load(path));
            } catch (CurlException e) {
                throw new FetchException(e.getMessage(), e);
            }
        }
    }
}
