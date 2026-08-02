package de.bsommerfeld.updater.launcher;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The launcher's half of the quit handshake, against stand-in terminals. What
 * matters is the verdict, because the caller updates the install on the
 * strength of it: {@code true} may only mean "verifiably gone".
 */
class TerminalRaiserTest {

    private static final int PORT = 19337;

    private ServerSocket stubTerminal;

    @TempDir
    Path appDir;

    @AfterEach
    void closeStub() throws IOException {
        if (stubTerminal != null && !stubTerminal.isClosed())
            stubTerminal.close();
    }

    @Test
    @DisplayName("no terminal listening → nothing to raise, nothing to quit")
    void noTerminalRunning() {
        assumeTrue(portIsFree(), "port taken by a running terminal");
        assertFalse(TerminalRaiser.raise());
        assertFalse(TerminalRaiser.requestQuit(Duration.ofSeconds(2), log()));
    }

    @Test
    @DisplayName("a terminal that answers but stays alive is NOT reported as gone")
    void aliveTerminalIsNotGone() throws Exception {
        // The pre-quit-protocol terminal: it raises its window on our poke,
        // answers nothing, and keeps its lock. Updating over it would be the
        // one genuinely destructive outcome.
        serveOnce(null, false);
        assertFalse(TerminalRaiser.requestQuit(Duration.ofSeconds(2), log()));
    }

    @Test
    @DisplayName("a terminal that answers without a PID is awaited via its lock")
    void lockReleaseIsTheFallbackSignal() throws Exception {
        serveOnce("garbled-answer\n", true);
        assertTrue(TerminalRaiser.requestQuit(Duration.ofSeconds(20), log()));
    }

    @Test
    @DisplayName("a PID acknowledgement is awaited on the process itself")
    void pidAcknowledgementIsAwaited() throws Exception {
        // A PID that no longer exists stands in for "already exited" — the
        // handle lookup comes up empty and the wait is over immediately.
        serveOnce("Q" + unusedPid() + "\n", false);
        assertTrue(TerminalRaiser.requestQuit(Duration.ofSeconds(5), log()));
    }

    // =====================================================================
    // Stand-in terminal
    // =====================================================================

    /**
     * Binds the well-known port and handles exactly one connection: reads the
     * command byte, optionally answers, and optionally releases the lock
     * afterwards (the way a real terminal does mid-shutdown).
     */
    private void serveOnce(String answer, boolean releaseLockAfter) throws IOException {
        assumeTrue(portIsFree(), "port taken by a running terminal");
        stubTerminal = new ServerSocket();
        stubTerminal.setReuseAddress(true);
        stubTerminal.bind(new InetSocketAddress("127.0.0.1", PORT));

        Thread server = new Thread(() -> {
            try (Socket conn = stubTerminal.accept()) {
                conn.getInputStream().read();
                if (answer != null) {
                    conn.getOutputStream().write(answer.getBytes(StandardCharsets.US_ASCII));
                    conn.getOutputStream().flush();
                }
            } catch (IOException ignored) {
                // Test teardown closed the socket.
            }
            if (releaseLockAfter) {
                try { stubTerminal.close(); } catch (IOException ignored) { }
            }
        }, "stub-terminal");
        server.setDaemon(true);
        server.start();
    }

    private static boolean portIsFree() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 300);
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    /** A PID far outside the live range — {@code ProcessHandle.of} finds nothing. */
    private static long unusedPid() {
        return 4_000_000_000L;
    }

    private SessionLog log() {
        return new SessionLog(appDir);
    }
}
