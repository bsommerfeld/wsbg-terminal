package de.bsommerfeld.tinybrowser;

import de.bsommerfeld.tinyfetch.engine.SocketFrame;

import java.net.URI;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The WebSockets, in hidden tabs of their own: one per anchor origin, however
 * many sockets it holds. A socket is the document's own {@code new
 * WebSocket()}, so its handshake carries the anchor's origin, the cookies the
 * browser holds for the socket's host and the browser's own fingerprint.
 *
 * <p>Not the fetch tabs ({@link Tabs}): those re-anchor when a site refuses
 * and close when idle, and either would cut every socket in the document. A
 * socket tab never navigates by itself, and closes {@link #LINGER_MS} after
 * its last socket did - a reconnect should not pay for a page load.
 *
 * <h3>Chromium's ceilings</h3>
 * At most {@link #MAX_PER_DESTINATION} sockets to one host and port, and
 * {@link #MAX_SOCKETS} in all - the sizes of Chromium's WebSocket pool, per
 * profile, not per tab; every Chrome has them. Measured on 146 (2026-09-30):
 * past the first, Chromium refuses at once; past the second, a socket waits
 * for a free place without end. So both are refused here, at once, and say why.
 */
final class Sockets {

    private static final long LINGER_MS = 60_000;
    private static final int MAX_PER_DESTINATION = 255;
    private static final int MAX_SOCKETS = 256;

    private final Browser browser;
    /** Guarded by {@code this}. */
    private final Map<String, SocketTab> byAnchorOrigin = new HashMap<>();
    /** Sockets per host and port. Guarded by {@code this}. */
    private final Map<String, Integer> byDestination = new HashMap<>();
    private final Map<Long, SocketTab> bySocket = new ConcurrentHashMap<>();

    Sockets(Browser browser) {
        this.browser = browser;
    }

    /**
     * Opens a socket in the tab for its anchor, opening that tab first if
     * need be. Returns once the tab has it; {@code events} gets the rest, and
     * a {@link SocketFrame.Close} in any case.
     */
    void open(SocketFrame.Open open, Consumer<SocketFrame> events) {
        String anchorUrl = open.anchor() != null ? open.anchor() : anchorOf(open.url());
        String anchorOrigin = anchorUrl == null ? null : Tabs.originOf(anchorUrl);
        String destination = destinationOf(open.url());
        if (anchorOrigin == null || destination == null) {
            events.accept(new SocketFrame.Close(open.id(), 1006, "no page to open " + open.url() + " in"));
            return;
        }
        SocketTab tab;
        synchronized (this) {
            String full = bySocket.size() >= MAX_SOCKETS
                    ? "Chromium holds " + MAX_SOCKETS + " WebSockets at most, all open"
                    : byDestination.getOrDefault(destination, 0) >= MAX_PER_DESTINATION
                    ? "Chromium holds " + MAX_PER_DESTINATION + " WebSockets to " + destination + " at most, all open"
                    : null;
            if (full != null) {
                events.accept(new SocketFrame.Close(open.id(), 1006, full));
                return;
            }
            tab = byAnchorOrigin.get(anchorOrigin);
            if (tab == null) {
                try {
                    tab = SocketTab.open(browser, anchorUrl, anchorOrigin);
                } catch (Exception e) {
                    events.accept(new SocketFrame.Close(open.id(), 1006, "cannot open a tab on " + anchorUrl + ": " + e));
                    return;
                }
                byAnchorOrigin.put(anchorOrigin, tab);
            }
            tab.sockets++;
            byDestination.merge(destination, 1, Integer::sum);
            bySocket.put(open.id(), tab);
        }
        SocketTab home = tab;
        tab.page.openSocket(open, frame -> {
            if (frame instanceof SocketFrame.Close) {
                released(open.id(), destination, home);
            }
            events.accept(frame);
        });
    }

    /** Sends a Message on, or Closes, an open socket; one that is gone drops it. */
    void send(SocketFrame frame) {
        SocketTab tab = bySocket.get(frame.id());
        if (tab != null) {
            tab.page.sendSocket(frame);
        }
    }

    /** A socket closed; its tab goes {@link #LINGER_MS} after its last one, unless a new one came meanwhile. */
    private void released(long id, String destination, SocketTab tab) {
        synchronized (this) {
            bySocket.remove(id);
            byDestination.computeIfPresent(destination, (key, count) -> count > 1 ? count - 1 : null);
            if (--tab.sockets > 0) {
                return;
            }
            tab.idleSince = System.currentTimeMillis();
        }
        Thread.ofVirtual().name("tinybrowser-socket-linger").start(() -> {
            try {
                Thread.sleep(LINGER_MS);
            } catch (InterruptedException e) {
                return;
            }
            synchronized (this) {
                if (tab.sockets > 0 || System.currentTimeMillis() - tab.idleSince < LINGER_MS
                        || !byAnchorOrigin.remove(tab.origin, tab)) {
                    return;
                }
            }
            tab.page.close();
            Log.info(tab.origin + ": socket tab closed");
        });
    }

    /**
     * Where a socket parks unless told otherwise: the root of its own host,
     * on {@code http} for {@code ws} and {@code https} for {@code wss} - a
     * secure page may not open an insecure socket. {@code null} for an
     * address that is no socket's.
     */
    static String anchorOf(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            String pageScheme = switch (scheme) {
                case "ws", "http" -> "http";
                case "wss", "https" -> "https";
                default -> null;
            };
            if (pageScheme == null || uri.getHost() == null) {
                return null;
            }
            return pageScheme + "://" + uri.getHost() + (uri.getPort() == -1 ? "" : ":" + uri.getPort()) + "/";
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * {@code host:port} of a socket's address, the port spelled out - what
     * Chromium counts its sockets by; {@code null} for an address without a host.
     */
    static String destinationOf(String url) {
        try {
            URI uri = URI.create(url);
            if (uri.getHost() == null) {
                return null;
            }
            int port = uri.getPort() != -1 ? uri.getPort()
                    : "wss".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
            return uri.getHost().toLowerCase(Locale.ROOT) + ":" + port;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** One socket tab: its page and how many sockets live in it. */
    private static final class SocketTab {
        final String origin;
        volatile Page page;
        /** Guarded by the {@link Sockets}. */
        int sockets;
        /** Guarded by the {@link Sockets}. */
        long idleSince;

        private SocketTab(String origin) {
            this.origin = origin;
        }

        static SocketTab open(Browser browser, String anchorUrl, String origin) throws Exception {
            SocketTab tab = new SocketTab(origin);
            // Before the anchor loads, as for a fetch tab: a consent wall may send the document elsewhere.
            ConsentCookies.seedFor(browser, anchorUrl);
            browser.open(anchorUrl, created -> tab.page = created, status -> { });
            Log.info(origin + ": socket tab opened on " + anchorUrl);
            return tab;
        }
    }
}
