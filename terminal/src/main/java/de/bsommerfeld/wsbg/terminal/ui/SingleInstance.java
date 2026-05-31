package de.bsommerfeld.wsbg.terminal.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;

/**
 * Process-wide singleton lock. The first terminal to start binds
 * {@link #PORT} on the loopback interface and listens for raise
 * requests; every later invocation discovers the existing instance
 * by attempting to connect to that port, sends a one-byte poke, and
 * exits.
 *
 * <p>
 * Any successful connection counts as a raise request — no payload
 * is exchanged. Keeping the protocol contentless means future
 * versions stay compatible: an old terminal sees a new launcher's
 * connect and still raises its window, and vice versa.
 *
 * <p>
 * The port must stay stable across releases — change it only with a
 * matching launcher update, otherwise launchers will fail to detect
 * a running terminal and start a duplicate.
 */
final class SingleInstance {

    private static final Logger LOG = LoggerFactory.getLogger(SingleInstance.class);

    /** Fixed loopback port; high enough to avoid colliding with anything common. */
    static final int PORT = 19337;

    private static ServerSocket lock;

    private SingleInstance() {}

    /**
     * Attempts to become the singleton holder. On success, spawns a
     * listener that invokes {@code onRaiseRequested} (on the calling
     * thread, so callers must hop to the EDT themselves if they touch
     * Swing) for every inbound connection.
     */
    static boolean claim(Runnable onRaiseRequested) {
        try {
            ServerSocket server = new ServerSocket();
            // Without reuseAddress, a fresh restart after a crash hits
            // a TIME_WAIT lockout for ~30 s.
            server.setReuseAddress(true);
            server.bind(new InetSocketAddress("127.0.0.1", PORT));
            lock = server;

            Thread listener = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket conn = server.accept()) {
                        LOG.info("Raise request from {}", conn.getRemoteSocketAddress());
                        onRaiseRequested.run();
                    } catch (IOException ignored) {
                        // Socket closed during shutdown or accept failed mid-loop.
                    }
                }
            }, "single-instance-listener");
            listener.setDaemon(true);
            listener.start();
            return true;
        } catch (IOException e) {
            LOG.info("Could not claim instance lock on port {}: {}", PORT, e.getMessage());
            return false;
        }
    }

    /**
     * Pokes a presumed-running instance into raising its window. Best
     * effort: any failure (port unreachable, timeout) returns false.
     */
    static boolean pingExisting() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", PORT),
                    (int) Duration.ofMillis(500).toMillis());
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static void release() {
        if (lock != null) {
            try { lock.close(); } catch (IOException ignored) {}
            lock = null;
        }
    }
}
