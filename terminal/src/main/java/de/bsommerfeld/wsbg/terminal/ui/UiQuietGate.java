package de.bsommerfeld.wsbg.terminal.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * "Is anything moving on screen right now?" - the one signal that tells heavy,
 * browser-UI-thread-blocking work when it may run without being seen.
 *
 * <p>Under off-screen rendering every frame of the visible page is delivered
 * through CEF's browser UI thread (the AppKit main thread on macOS). Anything
 * that occupies that thread for tens of milliseconds therefore freezes the
 * picture for exactly that long - and the hidden fetch browsers do precisely
 * that: creating one costs ~80-95 ms on that thread ({@code
 * CefBrowserHost::CreateBrowser}), closing one ~60 ms (compositor teardown with
 * a synchronous GPU finish). Measured 2026-08-21 on an M4 Max: 56 creations and
 * 37 evictions in 23 minutes, each landing wherever the scrapers happened to be
 * - in the middle of the intro, of a grid morph, of a scroll. Those were the
 * "constant micro-stutters".
 *
 * <p>The work itself cannot be made cheaper from Java. It can be made
 * invisible: run it only while the visible page has not produced a frame for
 * {@link #QUIET_MS}. A static dashboard paints about once a second (the clock),
 * so quiet windows are plentiful; an animation or a scroll paints every 16 ms
 * and keeps the gate shut. A bounded wait ({@link #MAX_WAIT_MS}) guarantees a
 * permanently animating page cannot starve the fetchers.
 *
 * <p>{@link #noteFrame()} is called by the OSR panel of the SHOWING browser on
 * every delivered frame (the hidden browsers paint into panels that are never
 * showing and do not count).
 */
public final class UiQuietGate {

    private static final Logger LOG = LoggerFactory.getLogger(UiQuietGate.class);

    /** No visible frame for this long = nothing is moving. */
    public static final long QUIET_MS = 150;
    /** Upper bound on how long a caller is held back. */
    public static final long MAX_WAIT_MS = 4_000;

    private static volatile long lastFrameNanos = System.nanoTime();
    /** Before the first frame the page is still loading - that is not "quiet", it is "not yet". */
    private static volatile boolean anyFrame;

    private UiQuietGate() {}

    /** A frame of the visible page was just delivered. Called from CEF's paint thread. */
    public static void noteFrame() {
        lastFrameNanos = System.nanoTime();
        anyFrame = true;
    }

    /** True when the visible page has been still for at least {@link #QUIET_MS}. */
    public static boolean isQuiet() {
        return anyFrame && System.nanoTime() - lastFrameNanos >= QUIET_MS * 1_000_000L;
    }

    /** {@link #awaitQuiet(String, long)} with the default {@link #MAX_WAIT_MS}. */
    public static long awaitQuiet(String what) {
        return awaitQuiet(what, MAX_WAIT_MS);
    }

    /**
     * Blocks the calling thread (never the EDT) until the page is quiet or
     * {@code maxWaitMs} has passed. Returns how long it waited, for the log.
     */
    public static long awaitQuiet(String what, long maxWaitMs) {
        if (SwingUtilities.isEventDispatchThread()) return 0; // never park the EDT
        long start = System.nanoTime();
        long deadline = start + maxWaitMs * 1_000_000L;
        while (!isQuiet() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        long waitedMs = (System.nanoTime() - start) / 1_000_000L;
        if (waitedMs > 0) {
            LOG.debug("UI quiet gate held '{}' for {} ms{}", what, waitedMs,
                    isQuiet() ? "" : " (gave up waiting, page still animating)");
        }
        return waitedMs;
    }

    /**
     * Runs {@code task} on the EDT at the next quiet moment, at the latest after
     * {@link #MAX_WAIT_MS}. Polls on a Swing timer, so it never blocks anything.
     */
    public static void runOnEdtWhenQuiet(String what, Runnable task) {
        long deadline = System.nanoTime() + MAX_WAIT_MS * 1_000_000L;
        Runnable attempt = new Runnable() {
            @Override
            public void run() {
                if (isQuiet() || System.nanoTime() >= deadline) {
                    task.run();
                    return;
                }
                Timer t = new Timer(40, e -> run());
                t.setRepeats(false);
                t.start();
            }
        };
        SwingUtilities.invokeLater(attempt);
    }
}
