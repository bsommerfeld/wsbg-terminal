package de.bsommerfeld.updater.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * The launcher's end of the terminal's single-instance channel: detect a
 * running terminal, bring it to the front, or — when an update has to be
 * applied over its install — ask it to close.
 *
 * <p>
 * {@link #raise()} is contentless: opening a TCP connection to the well-known
 * loopback port is the entire request. {@link #requestQuit} sends one command
 * byte on top and then waits for the process to actually die. If anything at
 * all goes sideways (port not in use, timeout, unexpected response, future
 * protocol change), both report failure and the caller falls back to the safe
 * behaviour — never start a second terminal, never update underneath a live
 * one. That fallback is what the non-upgradable native hull needs against
 * future client changes.
 */
final class TerminalRaiser {

    /** Must match {@code SingleInstance.PORT} in the terminal module. */
    private static final int PORT = 19337;
    /** Must match {@code SingleInstance.CMD_QUIT} in the terminal module. */
    private static final int CMD_QUIT = 'Q';

    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(800);
    private static final Duration ACK_TIMEOUT = Duration.ofMillis(1500);

    /**
     * Poll interval for the port-free fallback wait. Deliberately slow: each
     * probe is a connect, and a connect is a raise request — against a terminal
     * that is refusing to die we would otherwise keep yanking its window to the
     * front while we wait.
     */
    private static final long PORT_POLL_INTERVAL_MS = 1000;

    /**
     * Grace period after the instance lock disappeared, used only when the
     * terminal did not tell us its PID. The lock is released early in its
     * shutdown, so the JVM lives on for a moment — long enough to still hold
     * jars in {@code lib/} open on Windows.
     */
    private static final long PORT_FREE_SETTLE_MS = 3000;

    private TerminalRaiser() {}

    /** True iff a running terminal accepted the raise poke. */
    static boolean raise() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", PORT),
                    (int) CONNECT_TIMEOUT.toMillis());
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Asks a running terminal to shut down and waits until it is really gone.
     *
     * <p>
     * "Really gone" means the process, not the port: the terminal releases its
     * instance lock early in its teardown, so a free port only proves the
     * shutdown started. The terminal therefore answers with its PID and we wait
     * on that handle; without a PID (an older terminal, a truncated answer) we
     * fall back to polling the port and adding a settle window.
     *
     * <p>
     * A terminal too old to know the quit command raises its window instead and
     * stays alive — the wait then simply times out and {@code false} is the
     * honest answer: don't touch the install.
     *
     * @return {@code true} only if the terminal is verifiably gone
     */
    static boolean requestQuit(Duration timeout, SessionLog log) {
        long deadline = System.nanoTime() + timeout.toNanos();
        Optional<Long> pid;
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", PORT),
                    (int) CONNECT_TIMEOUT.toMillis());
            s.setSoTimeout((int) ACK_TIMEOUT.toMillis());

            OutputStream out = s.getOutputStream();
            out.write(CMD_QUIT);
            out.flush();

            pid = readPid(s.getInputStream());
        } catch (IOException e) {
            log.log("Quit request failed: " + e.getMessage());
            return false;
        }

        if (pid.isPresent()) {
            log.log("Terminal acknowledged quit (pid " + pid.get() + ") — waiting for it to exit.");
            return awaitProcessExit(pid.get(), deadline, log);
        }

        log.log("Terminal gave no pid on quit — falling back to waiting for the instance lock.");
        return awaitPortFree(deadline, log);
    }

    // =====================================================================
    // Waiting for death
    // =====================================================================

    /**
     * Reads the {@code Q<pid>\n} acknowledgement. Anything else — a closed
     * stream, a raise-only terminal that never answers, a garbled line —
     * yields empty, which sends the caller down the port-polling path.
     */
    private static Optional<Long> readPid(InputStream in) {
        try {
            StringBuilder ack = new StringBuilder();
            for (int b = in.read(); b >= 0 && b != '\n' && ack.length() < 32; b = in.read()) {
                ack.append((char) b);
            }
            String text = ack.toString().trim();
            if (text.isEmpty() || text.charAt(0) != CMD_QUIT)
                return Optional.empty();
            return Optional.of(Long.parseLong(text.substring(1)));
        } catch (IOException | NumberFormatException | IndexOutOfBoundsException e) {
            return Optional.empty();
        }
    }

    private static boolean awaitProcessExit(long pid, long deadline, SessionLog log) {
        Optional<ProcessHandle> handle = ProcessHandle.of(pid);
        if (handle.isEmpty() || !handle.get().isAlive())
            return true; // already gone between ack and lookup

        try {
            handle.get().onExit().get(remainingMillis(deadline), TimeUnit.MILLISECONDS);
            log.log("Terminal process " + pid + " exited.");
            return true;
        } catch (Exception e) {
            log.log("Terminal process " + pid + " did not exit in time.");
            return false;
        }
    }

    private static boolean awaitPortFree(long deadline, SessionLog log) {
        while (remainingMillis(deadline) > 0) {
            if (!raise()) {
                // The lock is gone but the JVM may still be finishing its
                // teardown — give it the settle window before we overwrite
                // the files it is running off.
                sleep(Math.min(PORT_FREE_SETTLE_MS, remainingMillis(deadline)));
                log.log("Terminal released its instance lock.");
                return true;
            }
            sleep(PORT_POLL_INTERVAL_MS);
        }
        log.log("Terminal still holding the instance lock after the quit timeout.");
        return false;
    }

    private static long remainingMillis(long deadline) {
        return Math.max(0, (deadline - System.nanoTime()) / 1_000_000);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
