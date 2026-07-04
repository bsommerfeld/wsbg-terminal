package de.bsommerfeld.wsbg.terminal.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Process-tree utility that force-terminates lingering Chromium "jcef Helper"
 * subprocesses. Extracted from {@link CefHost}: pure process bookkeeping with
 * zero dependency on the JCEF runtime state.
 */
final class CefHelperReaper {

    private static final Logger LOG = LoggerFactory.getLogger(CefHelperReaper.class);

    private CefHelperReaper() {}

    /**
     * Force-terminates any Chromium "jcef Helper" subprocesses that
     * {@code CefApp.dispose()} hasn't finished reaping, then returns. Must be
     * called right before a {@code System.exit(0)} on every close path.
     *
     * <p>{@code cefApp.dispose()} tears the GPU/renderer/network helper
     * processes down <b>asynchronously</b>: it only signals them and reports
     * completion later via {@code stateHasChanged(TERMINATED)}. The close paths
     * force-exit without waiting for that signal. On POSIX the exiting parent's
     * orphans are reparented to {@code init} and still reaped by the OS, but
     * <b>Windows has no such reaping</b> — any helper still shutting down when
     * the JVM exits is left running in the background (the reported
     * "jcef-helper lingers after close" bug). This gives dispose() a short grace
     * period to finish on its own, then force-kills whatever jcef helpers
     * remain, guaranteeing a clean exit on every platform.
     *
     * <p>Matched narrowly by command name ("jcef") so it can never touch a
     * sibling we deliberately spawned — notably the update launcher started by
     * {@code AppMain.relaunchForUpdate()} just before it tears CEF down.
     */
    static void reap() {
        try {
            // Prefer CEF's own async teardown: wait briefly for the helpers to
            // exit on their own (the loop breaks the instant none remain), then
            // force-kill any stragglers. The visible window is already gone by
            // the time this runs, so the short wait is invisible.
            long deadline = System.nanoTime() + 1_500_000_000L; // 1.5s
            while (System.nanoTime() < deadline && anyJcefHelper()) {
                Thread.sleep(50);
            }
            ProcessHandle.current().descendants()
                    .filter(CefHelperReaper::isJcefHelper)
                    .forEach(ph -> {
                        LOG.info("Reaping lingering CEF helper (pid {}).", ph.pid());
                        try {
                            ph.destroyForcibly();
                        } catch (Throwable ignored) {}
                    });
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            LOG.debug("Helper reap skipped: {}", t.toString());
        }
    }

    private static boolean anyJcefHelper() {
        return ProcessHandle.current().descendants().anyMatch(CefHelperReaper::isJcefHelper);
    }

    /**
     * A descendant is a Chromium helper if its executable name carries the
     * "jcef" token — {@code jcef_helper(.exe)} on Windows/Linux, {@code jcef
     * Helper (GPU/Renderer/…)} on macOS. Processes whose command can't be read
     * are treated as non-helpers (left alone) rather than risk killing a
     * sibling.
     */
    private static boolean isJcefHelper(ProcessHandle ph) {
        return ph.info().command()
                .map(cmd -> cmd.toLowerCase().contains("jcef"))
                .orElse(false);
    }
}
