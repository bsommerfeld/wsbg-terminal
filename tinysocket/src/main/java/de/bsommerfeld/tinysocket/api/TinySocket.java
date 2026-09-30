package de.bsommerfeld.tinysocket.api;

import de.bsommerfeld.tinyfetch.api.BrowserEngine;
import de.bsommerfeld.tinyfetch.api.FetchException;
import de.bsommerfeld.tinyfetch.engine.EngineProcess;
import de.bsommerfeld.tinyfetch.engine.SocketEngine;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * WebSockets that are one person's browser.
 *
 * <h2>It is a browser</h2>
 * Every socket is Chromium's own {@code new WebSocket()}, run in a hidden tab
 * parked on the site it speaks for - {@code https://stream.example.com/} for
 * {@code wss://stream.example.com/live}, or the page {@link Builder#anchor}
 * names. The handshake carries that page's origin, the cookies the browser
 * holds for the socket's host and the browser's fingerprint; nothing is
 * imitated. The tabs are TinyBrowser's, the engine TinyFetch runs: built on
 * the same {@link BrowserEngine}, the two share one browser, one profile -
 * the cookies a fetch earned go along with a handshake - and one process.
 *
 * <h2>Any number of them</h2>
 * A socket costs no tab of its own. All sockets with the same anchor live in
 * one tab, opened with the first of them and closed a minute after the last.
 *
 * <h2>What a browser's WebSocket cannot do</h2>
 * <ul>
 *   <li>send headers of the caller's own - a key goes into the URL, a cookie
 *       or the first message, the way a site's own scripts send it;</li>
 *   <li>ping - the browser answers the server's pings itself;</li>
 *   <li>close with a code other than {@code 1000} or {@code 3000}-{@code 4999};</li>
 *   <li>say why a handshake failed - a refused socket closes with {@code 1006}.</li>
 * </ul>
 * An anchor page whose Content-Security-Policy restricts {@code connect-src}
 * blocks every socket it does not list; the default anchor, the socket
 * host's own root, rarely has one.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * try (TinySocket sockets = TinySocket.builder()
 *         .engine(BrowserEngine.of(engineClassPath, chromiumDirectory, profileDirectory))
 *         .anchor("push.example.com", "https://www.example.com/")
 *         .build()) {
 *
 *     WebSocket quotes = sockets.open("wss://push.example.com/quotes", new SocketListener() {
 *         public void onText(WebSocket socket, String text) {
 *             show(parse(text));
 *         }
 *
 *         public void onClose(WebSocket socket, int code, String reason) {
 *             // gone for good - open a new one, after a pause
 *         }
 *     });
 *     quotes.send("{\"subscribe\":\"DE0007164600\"}");
 * }
 * }</pre>
 *
 * <h2>Threads</h2>
 * {@link #open} blocks until the handshake is done; {@link WebSocket#send} and
 * {@link WebSocket#close} never wait for the server. A socket's listener is
 * called on a thread of the socket's own, one call after another - a slow
 * listener holds up its own socket, no other. Everything is safe from any
 * number of threads.
 */
public final class TinySocket implements AutoCloseable {

    private static final Duration DEFAULT_OPEN_TIMEOUT = Duration.ofSeconds(30);

    private final SocketEngine engine;
    private final Map<String, String> anchors;
    private final Duration openTimeout;
    private final Set<WebSocket> sockets = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean closed = new AtomicBoolean();

    private TinySocket(Builder builder, SocketEngine engine) {
        this.engine = engine;
        this.anchors = Map.copyOf(builder.anchors);
        this.openTimeout = builder.openTimeout;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** {@link #open(String, List, SocketListener)} without subprotocols. */
    public WebSocket open(String url, SocketListener listener) throws WebSocketException, InterruptedException {
        return open(url, List.of(), listener);
    }

    /**
     * Opens a socket and waits for its handshake - after a tab's page load,
     * when it is the first socket on its anchor.
     *
     * @param url       {@code ws://} or {@code wss://}
     * @param protocols the subprotocols to offer, in order of preference;
     *                  {@link WebSocket#protocol()} says which the server took
     * @throws WebSocketException   refused, no handshake in time, or the
     *                              browser engine is not there
     * @throws InterruptedException interrupted while waiting; the socket is dropped
     */
    public WebSocket open(String url, List<String> protocols, SocketListener listener)
            throws WebSocketException, InterruptedException {
        URI uri = parse(url);
        Objects.requireNonNull(protocols, "protocols");
        Objects.requireNonNull(listener, "listener");
        ensureOpen();
        WebSocket socket = new WebSocket(engine, uri, listener, sockets::remove);
        sockets.add(socket);
        try {
            socket.bind(engine.openSocket(uri.toString(), protocols, anchors.get(uri.getHost().toLowerCase(Locale.ROOT)),
                    socket::receive, openTimeout.toMillis()));
        } catch (FetchException e) {
            sockets.remove(socket);
            throw new WebSocketException(uri + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            sockets.remove(socket);
            throw e;
        }
        socket.awaitOpen(openTimeout);
        return socket;
    }

    /** Closes every open socket and lets go of the engine - stopping it, unless TinyFetch still uses it. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        sockets.forEach(WebSocket::close);
        engine.close();
    }

    private void ensureOpen() throws WebSocketException {
        if (closed.get()) {
            throw new WebSocketException("TinySocket is closed");
        }
    }

    private static URI parse(String url) {
        Objects.requireNonNull(url, "url");
        URI parsed = URI.create(url);
        String scheme = parsed.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("ws") || scheme.equalsIgnoreCase("wss"))) {
            throw new IllegalArgumentException("only ws(s) URLs: " + url);
        }
        if (parsed.getHost() == null) {
            throw new IllegalArgumentException("URL without host: " + url);
        }
        return parsed;
    }

    // ---- builder ------------------------------------------------------------

    /** Configures a {@link TinySocket}; everything but the engine has a working default. */
    public static final class Builder {

        private BrowserEngine browserEngine;
        private SocketEngine engine;
        private final Map<String, String> anchors = new HashMap<>();
        private Duration openTimeout = DEFAULT_OPEN_TIMEOUT;

        private Builder() {
        }

        /** The browser to open sockets in - the same one as TinyFetch's shares its process. Required. */
        public Builder engine(BrowserEngine engine) {
            this.browserEngine = Objects.requireNonNull(engine, "engine");
            return this;
        }

        /** Any other engine - the tests' stand-in. */
        Builder engine(SocketEngine engine) {
            this.engine = Objects.requireNonNull(engine, "engine");
            return this;
        }

        /**
         * The page whose origin the sockets to {@code host} speak for - for a
         * server that wants its own site as the origin, e.g.
         * {@code push.example.com} → {@code https://www.example.com/}. Without
         * one, a socket parks on the root of its own host. Any page of the
         * origin will do, and a cheap one is best: the tab loads it, with its
         * scripts. A {@code ws://} socket needs an {@code http://} page - a
         * secure page may not open an insecure socket.
         */
        public Builder anchor(String host, String url) {
            URI parsed = URI.create(Objects.requireNonNull(url, "url"));
            if (parsed.getScheme() == null || parsed.getHost() == null) {
                throw new IllegalArgumentException("anchor must be an absolute URL: " + url);
            }
            anchors.put(host.toLowerCase(Locale.ROOT), url);
            return this;
        }

        /** How long {@link #open} waits for a handshake; 30 s unless set. */
        public Builder openTimeout(Duration timeout) {
            if (timeout.isNegative() || timeout.isZero()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            this.openTimeout = timeout;
            return this;
        }

        /**
         * Starts the engine in the background - or joins the one already
         * running on the same {@link BrowserEngine}, TinyFetch's.
         *
         * @throws WebSocketException the engine could not be launched at all
         */
        public TinySocket build() throws WebSocketException {
            if (engine != null) {
                return new TinySocket(this, engine);
            }
            if (browserEngine == null) {
                throw new IllegalStateException("no engine - set one with engine(BrowserEngine)");
            }
            try {
                return new TinySocket(this, EngineProcess.shared(browserEngine.command()));
            } catch (FetchException e) {
                throw new WebSocketException("cannot start the browser engine: " + e.getMessage(), e);
            }
        }
    }
}
