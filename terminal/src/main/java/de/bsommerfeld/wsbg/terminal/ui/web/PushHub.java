package de.bsommerfeld.wsbg.terminal.ui.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * WebSocket push channel for live updates from the Java backend to the
 * JCEF frontend. One process-wide instance; clients connect from the
 * page on load.
 *
 * <p>
 * Messages are JSON envelopes with a {@code type} discriminator
 * (e.g. {@code headlines}, {@code fj-news}, {@code market-status}).
 * Inbound messages from the page (window controls, settings changes)
 * are dispatched to type-keyed handlers registered via {@link #on}.
 */
@Singleton
public final class PushHub {

    private static final Logger LOG = LoggerFactory.getLogger(PushHub.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final CopyOnWriteArrayList<WebSocket> clients = new CopyOnWriteArrayList<>();
    private final Map<String, Consumer<Map<String, Object>>> handlers = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Runnable> openListeners = new CopyOnWriteArrayList<>();

    private InternalServer server;
    private int port = -1;

    @Inject
    public PushHub() {}

    public void start() {
        server = new InternalServer(new InetSocketAddress("127.0.0.1", 0));
        server.setReuseAddr(true);
        server.start();
        try {
            // start() is async; the port is unknown until the bind completes.
            for (int i = 0; i < 100 && server.getPort() <= 0; i++) Thread.sleep(10);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        port = server.getPort();
        LOG.info("PushHub listening on ws://127.0.0.1:{}", port);
    }

    public void stop() {
        if (server != null) {
            try { server.stop(500); } catch (Exception ignored) {}
        }
    }

    public int port() {
        return port;
    }

    /** Registers a handler for an inbound message type. */
    public void on(String type, Consumer<Map<String, Object>> handler) {
        handlers.put(type, handler);
    }

    /** Called every time a client connects (e.g. for sending snapshot state). */
    public void onClientOpen(Runnable listener) {
        openListeners.add(listener);
    }

    public boolean hasClients() {
        return !clients.isEmpty();
    }

    /** Broadcasts a typed JSON message to every connected client. */
    public void broadcast(String type, Object payload) {
        if (clients.isEmpty()) {
            LOG.debug("broadcast({}) skipped — no clients", type);
            return;
        }
        try {
            String json = mapper.writeValueAsString(Map.of("type", type, "payload", payload));
            logBroadcast(type, json, payload);
            for (WebSocket ws : clients) {
                if (ws.isOpen()) ws.send(json);
            }
        } catch (Exception e) {
            LOG.warn("Failed to broadcast {}: {}", type, e.getMessage());
        }
    }

    /**
     * Builds the {@code payload} via {@code supplier} and broadcasts it, swallowing
     * and logging any failure. Publishers that only need "send this, don't let a
     * serialisation/build error escape the poll loop" use this instead of hand-rolling
     * their own try/catch. The supplier is evaluated inside the guard, so a throwing
     * payload builder is caught too.
     */
    public void broadcastSafe(String type, java.util.function.Supplier<Object> supplier) {
        try {
            broadcast(type, supplier.get());
        } catch (Exception e) {
            LOG.warn("{} broadcast failed: {}", type, e.getMessage());
        }
    }

    private void logBroadcast(String type, String json, Object payload) {
        int items = (payload instanceof java.util.Collection<?> c) ? c.size() : -1;
        LOG.info("broadcast {} → {} client(s){}",
                type, clients.size(),
                items >= 0 ? " (" + items + " items, " + json.length() + " B)" : "");
    }

    private final class InternalServer extends WebSocketServer {
        InternalServer(InetSocketAddress address) { super(address); }

        @Override public void onOpen(WebSocket conn, ClientHandshake handshake) {
            clients.add(conn);
            LOG.debug("Client connected: {} ({} total)", conn.getRemoteSocketAddress(), clients.size());
            openListeners.forEach(r -> {
                try { r.run(); } catch (Throwable t) { LOG.warn("open listener failed", t); }
            });
        }

        @Override public void onClose(WebSocket conn, int code, String reason, boolean remote) {
            clients.remove(conn);
        }

        @Override @SuppressWarnings("unchecked")
        public void onMessage(WebSocket conn, String message) {
            try {
                Map<String, Object> env = mapper.readValue(message, Map.class);
                String type = String.valueOf(env.get("type"));
                Consumer<Map<String, Object>> h = handlers.get(type);
                if (h != null) {
                    Object payload = env.get("payload");
                    h.accept(payload instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of());
                }
            } catch (Exception e) {
                LOG.warn("Bad inbound message: {}", e.getMessage());
            }
        }

        @Override public void onError(WebSocket conn, Exception ex) {
            LOG.warn("WebSocket error: {}", ex.getMessage());
        }

        @Override public void onStart() {
            setConnectionLostTimeout(0);
        }
    }
}
