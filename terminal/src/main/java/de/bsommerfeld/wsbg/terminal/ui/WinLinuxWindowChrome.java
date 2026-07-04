package de.bsommerfeld.wsbg.terminal.ui;

import javax.swing.JFrame;
import java.awt.Dimension;

/**
 * Windows/Linux window chrome. The frame keeps its native OS decoration (real
 * min/max/close buttons, native drag, native edge-resize and Aero snap); on
 * Windows {@link WindowsCustomChrome} then strips the caption so the HTML
 * titlebar draws flush and {@link WindowsChrome} themes the frame dark. Both
 * native calls are no-ops on Linux (they guard on the OS), which keeps its full
 * native title bar. Extracted from {@link BrowserWindow}.
 */
final class WinLinuxWindowChrome implements WindowChrome {

    @Override
    public void applyBeforeShow(JFrame frame, Runnable onQuit) {
        // Nothing to do before show — Windows/Linux keep the native decoration;
        // the caption strip + dark theme are applied post-realise below. There
        // is no distinct application-quit gesture here, so onQuit is unused (the
        // window-close gesture already drives the graceful shutdown).
    }

    @Override
    public void applyAfterShow(JFrame frame) {
        // Windows: remove the native caption (keeping native resize/snap)
        // so the HTML title bar draws flush like macOS, then theme the
        // remaining frame dark. Deferred a tick so the native peer is fully
        // realised; the 1px resize nudge forces the non-client area to
        // recompute/repaint (and kicks JCEF's first paint, like the macOS
        // branch). Both calls are no-ops on Linux — they guard on the OS,
        // and the page keeps its native title bar there (data-platform).
        javax.swing.Timer kick = new javax.swing.Timer(100, e -> {
            WindowsCustomChrome.install(frame);
            WindowsChrome.applyDarkTitleBar(frame);
            Dimension size = frame.getSize();
            frame.setSize(size.width + 1, size.height);
            frame.setSize(size.width, size.height);
        });
        kick.setRepeats(false);
        kick.start();

        // The OSR GLCanvas child is realised lazily; re-run install once it
        // exists so its window proc gets bridged (idempotent for the frame).
        javax.swing.Timer bridge = new javax.swing.Timer(1500, e -> WindowsCustomChrome.install(frame));
        bridge.setRepeats(false);
        bridge.start();
    }
}
