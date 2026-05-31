package de.bsommerfeld.updater.launcher;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

/**
 * Detects a running terminal instance and asks it to come to the
 * front, so a second launcher click doesn't start a parallel app.
 *
 * <p>
 * The protocol is intentionally contentless — opening a TCP
 * connection to the well-known loopback port is the entire raise
 * request. If the connect attempt fails for any reason (port not in
 * use, timeout, unexpected response, future protocol change), the
 * launcher treats it as "no instance running" and proceeds with its
 * normal install/update/launch flow. This is the fallback the
 * non-upgradable launcher needs against future client changes.
 */
final class TerminalRaiser {

    /** Must match {@code SingleInstance.PORT} in the terminal module. */
    private static final int PORT = 19337;
    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(800);

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
}
