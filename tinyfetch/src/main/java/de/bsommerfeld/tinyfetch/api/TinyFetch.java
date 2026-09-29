package de.bsommerfeld.tinyfetch.api;

import de.bsommerfeld.tinyfetch.engine.Engine;
import de.bsommerfeld.tinyfetch.engine.EngineAnswer;
import de.bsommerfeld.tinyfetch.engine.EngineProcess;
import de.bsommerfeld.tinyfetch.engine.EngineRequest;
import de.bsommerfeld.tinyfetch.pacing.HostPacer;
import de.bsommerfeld.tinyfetch.pacing.WallDetector;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.random.RandomGenerator;

/**
 * HTTP client that is one person using a browser.
 *
 * <h2>It is a browser</h2>
 * Every request is the {@code fetch()} of a real Chromium page: a hidden tab
 * parked on the target's own site - {@code https://www.reddit.com/} for
 * anything on {@code www.reddit.com} - asks for the address the way that
 * site's own scripts would. Fingerprint, cookies, TLS session, HTTP cache and
 * whatever the site's scripts set up for their visitor are simply the
 * browser's; nothing is imitated. A site that first checks its visitor runs a
 * browser - a script, a redirect, a consent cookie - gets one. The tabs, and
 * how they are kept healthy, are the engine's business (TinyBrowser, a
 * {@link BrowserEngine}); a host whose root cannot hold a tab gets its
 * {@link Builder#anchor}.
 *
 * <h2>It behaves like a person</h2>
 * <ul>
 *   <li><b>One thing at a time per host.</b> Requests to a host queue and go
 *       out one after another, never in parallel bursts.</li>
 *   <li><b>A human pace.</b> Consecutive requests to a host are
 *       {@link HostPolicy#minInterval()} apart plus random jitter; a nearly
 *       spent rate budget ({@code x-ratelimit-*}) waits for its reset.</li>
 *   <li><b>Takes no for an answer.</b> A {@link Wall} - throttle, refusal,
 *       CAPTCHA - pauses the host with a doubling back-off; during the pause
 *       every request fails at once ({@link CooldownException}) without
 *       reaching the browser. Challenges are never solved or worked around
 *       by the program; a CAPTCHA goes to the {@link CaptchaSolver}
 *       ({@link Builder#captchaSolver}), which may let a person solve it.</li>
 * </ul>
 * The pace and back-off per host come from {@link HostPolicy}. Set them for
 * what a person would plausibly do on that site, never tighter than the site
 * documents or signals.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * try (TinyFetch fetch = TinyFetch.builder()
 *         .engine(BrowserEngine.of(engineClassPath, chromiumDirectory, profileDirectory))
 *         .policy("www.reddit.com", HostPolicy.defaults().withMinInterval(Duration.ofSeconds(6)))
 *         .build()) {
 *
 *     FetchResponse listing = fetch.fetch(FetchRequest.of("https://www.reddit.com/r/wallstreetbetsGER/new.json"));
 *     if (listing.ok()) {
 *         parse(listing.text());
 *     } else if (listing.wall() != Wall.NONE) {
 *         // the host is paused now - take the fallback
 *     }
 * } catch (CooldownException paused) {
 *     // paused.until(): nothing was sent
 * }
 * }</pre>
 *
 * <h2>Threads</h2>
 * {@link #fetch} is safe from any number of threads, virtual ones included.
 * It blocks for the host's pace, so a caller must not hold anything others
 * need while it waits. Requests to different hosts run in parallel.
 */
public final class TinyFetch implements Fetcher, AutoCloseable {

    private static final System.Logger LOG = System.getLogger("de.bsommerfeld.tinyfetch");

    private final Engine engine;
    private final HostPolicy defaultPolicy;
    private final Map<String, HostPolicy> policies;
    private final Map<String, String> anchors;
    private final CaptchaSolver captchaSolver;
    /** Hosts whose CAPTCHA is with the solver right now - one call per host at a time. */
    private final Set<String> solving = ConcurrentHashMap.newKeySet();
    private final RandomGenerator random = RandomGenerator.getDefault();
    private final AtomicLong requestIds = new AtomicLong();

    private final Map<String, HostSession> sessions = new ConcurrentHashMap<>();
    private volatile boolean closed;

    private TinyFetch(Builder builder, Engine engine) {
        this.engine = engine;
        this.defaultPolicy = builder.defaultPolicy;
        this.policies = Map.copyOf(builder.policies);
        this.anchors = Map.copyOf(builder.anchors);
        this.captchaSolver = builder.captchaSolver;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public FetchResponse fetch(FetchRequest request) throws FetchException, InterruptedException {
        Objects.requireNonNull(request, "request");
        ensureOpen();
        HostSession session = sessions.computeIfAbsent(request.host(), this::openSession);

        /*
         * The host lock is held across the wait and the exchange: that is
         * what queues a host's requests one behind the other, and fair
         * ordering keeps them first come, first served.
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

    /**
     * When {@code host} may be asked again, if it is paused after a wall.
     */
    public Optional<Instant> pausedUntil(String host) {
        HostSession session = sessions.get(host.toLowerCase(Locale.ROOT));
        return session == null ? Optional.empty() : session.pacer.pausedUntil().map(Instant::ofEpochMilli);
    }

    /** Stops the engine; requests afterwards fail. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        engine.close();
    }

    // ---- one exchange -----------------------------------------------------

    private FetchResponse exchange(HostSession session, FetchRequest request)
            throws FetchException, InterruptedException {
        List<Map.Entry<String, String>> headers = new ArrayList<>(request.headers());
        request.contentType().ifPresent(type -> headers.add(Map.entry("content-type", type)));
        EngineRequest engineRequest = new EngineRequest(requestIds.incrementAndGet(), request.uri().toString(),
                request.method(), headers, request.body(), anchors.get(request.host()),
                request.timeout().toMillis());

        EngineAnswer answer;
        try {
            answer = engine.exchange(engineRequest);
        } catch (FetchException e) {
            session.pacer.recordNoAnswer();
            throw e;
        }
        if (answer.failure() != null) {
            session.pacer.recordNoAnswer();
            throw new FetchException(request + ": " + answer.failure());
        }

        Map<String, List<String>> responseHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        answer.headers().forEach(header ->
                responseHeaders.computeIfAbsent(header.getKey(), name -> new ArrayList<>()).add(header.getValue()));
        URI url = answer.url() == null || answer.url().isEmpty() ? request.uri() : URI.create(answer.url());
        FetchResponse plain = new FetchResponse(answer.status(), url, responseHeaders, answer.body(), Wall.NONE);
        Wall wall = WallDetector.classify(answer.status(), plain::header, answer.body());
        session.pacer.recordAnswer(wall, plain::header);
        if (wall == Wall.NONE) {
            return plain;
        }
        LOG.log(System.Logger.Level.WARNING, "{0} answered {1} ({2}) - paused until {3}",
                request.host(), answer.status(), wall,
                session.pacer.pausedUntil().map(Instant::ofEpochMilli).orElse(Instant.now()));
        if (wall == Wall.CHALLENGE) {
            askSolver(session, new CaptchaChallenge(request.host(), url, answer.status()));
        }
        return new FetchResponse(answer.status(), url, responseHeaders, answer.body(), wall);
    }

    /**
     * Hands a CAPTCHA to the {@link CaptchaSolver} on a thread of its own:
     * the request that met it returns now, the host stays paused meanwhile,
     * and a solved one ends the pause.
     */
    private void askSolver(HostSession session, CaptchaChallenge challenge) {
        if (!solving.add(challenge.host())) {
            return;
        }
        Thread.ofVirtual().name("tinyfetch-captcha-" + challenge.host()).start(() -> {
            try {
                if (captchaSolver.solve(challenge)) {
                    session.pacer.clearPause();
                    LOG.log(System.Logger.Level.INFO, "{0}: CAPTCHA solved, pause lifted", challenge.host());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "CAPTCHA solver failed for " + challenge.host(), e);
            } finally {
                solving.remove(challenge.host());
            }
        });
    }

    private HostSession openSession(String host) {
        HostPolicy policy = policies.getOrDefault(host, defaultPolicy);
        return new HostSession(new HostPacer(policy, System::currentTimeMillis, random));
    }

    private void ensureOpen() throws FetchException {
        if (closed) {
            throw new FetchException("TinyFetch is closed");
        }
    }

    /** One host: its queue and its pace. */
    private static final class HostSession {
        final ReentrantLock lock = new ReentrantLock(true);
        final HostPacer pacer;

        HostSession(HostPacer pacer) {
            this.pacer = pacer;
        }
    }

    // ---- builder ------------------------------------------------------------

    /** Configures a {@link TinyFetch}; everything but the engine has a working default. */
    public static final class Builder {

        private BrowserEngine browserEngine;
        private Engine engine;
        private HostPolicy defaultPolicy = HostPolicy.defaults();
        private final Map<String, HostPolicy> policies = new HashMap<>();
        private final Map<String, String> anchors = new HashMap<>();
        private CaptchaSolver captchaSolver = CaptchaSolver.NOBODY;

        private Builder() {
        }

        /** The browser to fetch with. Required. */
        public Builder engine(BrowserEngine engine) {
            this.browserEngine = Objects.requireNonNull(engine, "engine");
            return this;
        }

        /** Any other engine - the tests' stand-in. */
        Builder engine(Engine engine) {
            this.engine = Objects.requireNonNull(engine, "engine");
            return this;
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
         * The page the tab for {@code host} parks on, where the host's own
         * root cannot hold it:
         * <ul>
         *   <li>an API host whose root never answers - park on the brand's
         *       real site ({@code api.nasdaq.com} → {@code https://www.nasdaq.com/});
         *       the requests are then cross-origin, so the API must answer
         *       CORS with credentials, and only CORS-safelisted headers go
         *       along;</li>
         *   <li>a root that redirects to another host - park on a cheap
         *       address on the host itself, so the requests stay same-origin.</li>
         * </ul>
         * Without one the tab parks on {@code https://<host>/}; a root that
         * answers at all, even with a 403 or 404, does.
         */
        public Builder anchor(String host, String url) {
            URI parsed = URI.create(Objects.requireNonNull(url, "url"));
            if (parsed.getScheme() == null || parsed.getHost() == null) {
                throw new IllegalArgumentException("anchor must be an absolute URL: " + url);
            }
            anchors.put(host.toLowerCase(Locale.ROOT), url);
            return this;
        }

        /** Who is asked when a host wants a person; {@link CaptchaSolver#NOBODY} unless set. */
        public Builder captchaSolver(CaptchaSolver captchaSolver) {
            this.captchaSolver = Objects.requireNonNull(captchaSolver, "captchaSolver");
            return this;
        }

        /**
         * Starts the engine in the background - requests made before it is
         * up wait for it, within their patience.
         *
         * @throws FetchException the engine could not be launched at all
         */
        public TinyFetch build() throws FetchException {
            if (engine != null) {
                return new TinyFetch(this, engine);
            }
            if (browserEngine == null) {
                throw new IllegalStateException("no engine - set one with engine(BrowserEngine)");
            }
            EngineProcess process = new EngineProcess(browserEngine.command());
            process.start();
            return new TinyFetch(this, process);
        }
    }
}
