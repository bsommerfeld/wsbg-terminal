package de.bsommerfeld.wsbg.terminal.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JFrame;
import java.awt.Component;

/**
 * macOS window chrome: the {@code JFrame} stays decorated (JCEF reparenting
 * requires a standard NSWindow) but the OS title bar is made transparent via
 * {@code apple.awt.*} root-pane properties, so the HTML titlebar sits flush over
 * the native traffic lights. Extracted from {@link BrowserWindow}.
 */
final class MacWindowChrome implements WindowChrome {

    private static final Logger LOG = LoggerFactory.getLogger(MacWindowChrome.class);

    @Override
    public void applyBeforeShow(JFrame frame, Runnable onQuit) {
        // On macOS, JCEF reparents its Chromium NSWindow into the JFrame's
        // NSWindow via sun.lwawt.macosx internals. That reparenting fails
        // on undecorated JFrames because they create a borderless NSWindow
        // variant the JCEF helper doesn't recognise, producing two stray
        // windows (a blank JFrame + a free-floating Chromium window).
        //
        // Workaround: keep the JFrame decorated and hide the OS title
        // bar via macOS-only root pane client properties. The HTML's
        // custom titlebar then sits flush at the top of the content.
        frame.getRootPane().putClientProperty("apple.awt.fullWindowContent", true);
        frame.getRootPane().putClientProperty("apple.awt.transparentTitleBar", true);
        frame.getRootPane().putClientProperty("apple.awt.windowTitleVisible", false);

        // macOS: Cmd+Q (and the app-menu Quit) goes through the application
        // quit path, NOT windowClosing — so route it to the same teardown, else
        // it would skip the CEF dispose + Ollama kill and leak processes. Best-
        // effort; the OS / Java version may not support a quit handler.
        try {
            java.awt.Desktop.getDesktop().setQuitHandler((evt, response) -> onQuit.run());
        } catch (Throwable t) {
            LOG.debug("Could not install macOS quit handler: {}", t.toString());
        }
    }

    @Override
    public void applyAfterShow(JFrame frame) {
        // (The former "first-paint kick" - setSize(w+1) then setSize(w) 250 ms
        // after show - is gone. It dated from the windowed Chromium NSView, which
        // would not render until its bounds were invalidated. The OSR browser
        // sizes itself from the panel's componentResized; the kick only made
        // Chromium lay the page out twice more right as the intro began.)

        // Carve the native title-bar interception out of the right-hand action
        // buttons (grid / gear / update) so their clicks land instantly instead
        // of waiting on AppKit's drag / double-click-zoom disambiguation.
        // Best-effort; a failure just keeps the old lag. The panel is captured
        // here on the EDT; the JNA bootstrap (~87 ms) runs on its own thread and
        // waits for a moment in which the page is still - under OSR an EDT stall
        // is a frame stall, and this used to land in the middle of the intro.
        java.awt.Container cp = frame.getContentPane();
        Component osrPanel = cp.getComponentCount() > 0 ? cp.getComponent(0) : null;
        Thread carve = new Thread(() -> {
            UiQuietGate.awaitQuiet("mac title-bar carve-out", 10_000);
            MacTitlebarCarveout.install(osrPanel);
        }, "mac-titlebar-carveout");
        carve.setDaemon(true);
        carve.start();
    }
}
