package de.bsommerfeld.wsbg.terminal.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The launcher↔terminal contract: a bare connection raises the window, the
 * quit byte shuts the app down, and the quit is acknowledged with a PID the
 * launcher can wait on. Everything else must land on "raise" — that fallback is
 * what keeps old launchers (which know nothing but the contentless poke) from
 * ever killing a running terminal.
 */
class SingleInstanceTest {

    private CountDownLatch raised;
    private CountDownLatch quit;

    @BeforeEach
    void claimLock() {
        raised = new CountDownLatch(1);
        quit = new CountDownLatch(1);
        // A terminal running on this machine owns the fixed port — then there
        // is nothing to test here, only a false failure to report.
        assumeTrue(SingleInstance.claim(raised::countDown, quit::countDown),
                "port already taken by a running terminal");
    }

    @AfterEach
    void releaseLock() {
        SingleInstance.release();
    }

    @Test
    @DisplayName("a bare connection is a raise request")
    void bareConnectionRaises() throws Exception {
        try (Socket s = connect()) {
            // no payload at all — the pre-quit-protocol launcher's poke
        }
        assertTrue(raised.await(3, TimeUnit.SECONDS), "window should have been raised");
        assertEquals(1, quit.getCount(), "must not be read as a quit");
    }

    @Test
    @DisplayName("an unknown command byte falls back to raise, never to quit")
    void unknownCommandRaises() throws Exception {
        try (Socket s = connect()) {
            s.getOutputStream().write('X');
            s.getOutputStream().flush();
        }
        assertTrue(raised.await(3, TimeUnit.SECONDS), "unknown byte should raise");
        assertEquals(1, quit.getCount(), "must not be read as a quit");
    }

    @Test
    @DisplayName("the quit byte shuts down and acknowledges with our PID")
    void quitCommandShutsDown() throws Exception {
        String ack;
        try (Socket s = connect()) {
            s.getOutputStream().write(SingleInstance.CMD_QUIT);
            s.getOutputStream().flush();
            ack = readLine(s.getInputStream());
        }
        assertTrue(quit.await(3, TimeUnit.SECONDS), "quit callback should have run");
        assertEquals(1, raised.getCount(), "must not also raise");
        assertEquals(((char) SingleInstance.CMD_QUIT) + Long.toString(ProcessHandle.current().pid()),
                ack, "launcher waits on this PID");
    }

    private static Socket connect() throws IOException {
        Socket s = new Socket();
        s.connect(new InetSocketAddress("127.0.0.1", SingleInstance.PORT), 2000);
        s.setSoTimeout(3000);
        return s;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int b = in.read(); b >= 0 && b != '\n'; b = in.read()) {
            line.append((char) b);
        }
        return line.toString();
    }
}
