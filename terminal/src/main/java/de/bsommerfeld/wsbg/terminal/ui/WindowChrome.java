package de.bsommerfeld.wsbg.terminal.ui;

import javax.swing.JFrame;

/**
 * Platform-split window-chrome strategy, extracted from {@link BrowserWindow}'s
 * inline {@code if (isMac()) … else …}. Two hooks bracket {@code setVisible}:
 * {@link #applyBeforeShow} sets up the frame's decoration model before the
 * native peer is realised; {@link #applyAfterShow} runs the deferred
 * post-realise kicks (title-bar theming, first-paint nudge).
 *
 * @see MacWindowChrome
 * @see WinLinuxWindowChrome
 */
interface WindowChrome {

    /**
     * Pre-show setup. {@code onQuit} is the graceful-shutdown routine to run
     * when the OS fires an application-quit (macOS Cmd+Q) rather than a window
     * close; platforms without a distinct quit gesture ignore it.
     */
    void applyBeforeShow(JFrame frame, Runnable onQuit);

    /** Post-show, deferred kicks (title-bar theming + first-paint nudge). */
    void applyAfterShow(JFrame frame);

    /** Picks the strategy for the current OS. */
    static WindowChrome forCurrentOs() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("mac") ? new MacWindowChrome() : new WinLinuxWindowChrome();
    }
}
