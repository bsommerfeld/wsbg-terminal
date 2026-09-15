package de.bsommerfeld.wsbg.terminal.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;

/**
 * {@code WSBG_SHELL=external}: the backend runs without a window of its own. The
 * page is shown by a separate shell process (the Tauri shell in {@code shell/})
 * that loads the entry URL announced on stdout as {@code WSBG_ENTRY_URL=...}.
 *
 * <p>What changes in this mode:
 * <ul>
 *   <li>No Swing window, no JCEF browser for the page, no title-bar chrome. The
 *       embedded Chromium is still initialised (unless {@link OfflineMode} is on)
 *       because the hidden fetch browsers need it - they keep working exactly as
 *       before, only without a visible page to stall.</li>
 *   <li>{@link UiQuietGate} is always open: there is no visible frame to protect.</li>
 *   <li>The JVM is a background process on macOS (no Dock icon); the shell owns
 *       the Dock presence.</li>
 *   <li>With {@code WSBG_SHELL_PARENT_WATCH=true} the backend quits cleanly when
 *       its stdin reaches end-of-file - the shell holds the other end of that pipe,
 *       so closing the shell (or the shell dying) takes the backend down with it.</li>
 * </ul>
 */
public final class ExternalShell {

    private static final Logger LOG = LoggerFactory.getLogger(ExternalShell.class);

    public static final boolean ACTIVE = "external".equalsIgnoreCase(System.getenv("WSBG_SHELL"));

    /** Whether stdin end-of-file means "the shell is gone, quit". */
    static final boolean PARENT_WATCH = ACTIVE
            && "true".equalsIgnoreCase(System.getenv("WSBG_SHELL_PARENT_WATCH"));

    private ExternalShell() {}

    /** Prints the machine-readable entry URL line the shell waits for. */
    static void announceEntryUrl(String url) {
        System.out.println("WSBG_ENTRY_URL=" + url);
        System.out.flush();
    }

    /**
     * Hands a window command to the shell: {@code close}, {@code minimize},
     * {@code maximize-toggle} (the page's title-bar buttons, arriving over the
     * push hub) and {@code raise} (a second launch or the launcher asking for the
     * running window). The shell reads its sidecar's stdout line by line and acts
     * on {@code WSBG_SHELL_CMD=...}; without a shell the line is just a log line.
     */
    public static void command(String command) {
        if (!ACTIVE) return;
        System.out.println("WSBG_SHELL_CMD=" + command);
        System.out.flush();
    }

    /**
     * Starts the stdin watchdog: a daemon thread that blocks on stdin and runs
     * {@code onEof} once the shell has closed the pipe. Inert unless
     * {@link #PARENT_WATCH} is set, so a backend started from a terminal by hand
     * never quits on its own.
     */
    static void watchParent(Runnable onEof) {
        if (!PARENT_WATCH) return;
        Thread t = new Thread(() -> {
            InputStream in = System.in;
            byte[] buf = new byte[256];
            try {
                while (in.read(buf) >= 0) {
                    // Nothing is expected on stdin; only its end matters.
                }
            } catch (IOException e) {
                LOG.debug("stdin watch ended with {}", e.toString());
            }
            LOG.info("Shell closed the control pipe - shutting down.");
            onEof.run();
        }, "shell-parent-watch");
        t.setDaemon(true);
        t.start();
    }
}
