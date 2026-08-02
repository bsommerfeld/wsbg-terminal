package de.bsommerfeld.wsbg.terminal.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Process-wide singleton lock. The first terminal to start binds
 * {@link #PORT} on the loopback interface and listens for raise
 * requests; every later invocation discovers the existing instance
 * by attempting to connect to that port, sends a one-byte poke, and
 * exits.
 *
 * <p>
 * A bare connection counts as a raise request — no payload needed.
 * A connection that sends {@link #CMD_QUIT} asks the terminal to shut
 * itself down instead; that is the launcher's path when it found an
 * update and must replace the files this process is running off.
 * Everything else — no payload, an unknown byte, a peer that hangs up
 * — falls back to "raise", so an old launcher's contentless poke keeps
 * working unchanged and no future dialect can accidentally kill the app.
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

    /** Must match {@code TerminalRaiser.CMD_QUIT} in the launcher module. */
    static final int CMD_QUIT = 'Q';

    /**
     * How long a connection may take to reveal its intent. A launcher sends
     * its command byte immediately; anything slower is treated as the
     * contentless raise poke rather than blocking the listener.
     */
    private static final int COMMAND_READ_TIMEOUT_MS = 500;

    private static ServerSocket lock;

    private SingleInstance() {}

    /**
     * Attempts to become the singleton holder. On success, spawns a
     * listener that invokes {@code onRaiseRequested} / {@code onQuitRequested}
     * (on the calling thread, so callers must hop to the EDT themselves if
     * they touch Swing) for every inbound connection.
     */
    static boolean claim(Runnable onRaiseRequested, Runnable onQuitRequested) {
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
                        if (readCommand(conn) == CMD_QUIT) {
                            LOG.info("Quit request from {} — shutting down",
                                    conn.getRemoteSocketAddress());
                            // Acknowledge with our PID before tearing down. The
                            // launcher must not merely know the command was
                            // understood — it has to wait for this JVM to be
                            // really gone before it overwrites lib/, or the
                            // extraction hits jars Windows still has locked.
                            // The instance lock is released early in the
                            // shutdown, so it is not a usable death signal.
                            conn.getOutputStream().write(
                                    (((char) CMD_QUIT) + Long.toString(ProcessHandle.current().pid()) + "\n")
                                            .getBytes(StandardCharsets.US_ASCII));
                            conn.getOutputStream().flush();
                            onQuitRequested.run();
                        } else {
                            LOG.info("Raise request from {}", conn.getRemoteSocketAddress());
                            onRaiseRequested.run();
                        }
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
     * Reads the caller's intent byte, or {@code -1} when none arrives in
     * time. A missing/short payload is not an error here — it is the
     * contentless raise poke every launcher before the quit protocol sends.
     */
    private static int readCommand(Socket conn) throws IOException {
        try {
            conn.setSoTimeout(COMMAND_READ_TIMEOUT_MS);
            return conn.getInputStream().read();
        } catch (SocketTimeoutException e) {
            return -1;
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
