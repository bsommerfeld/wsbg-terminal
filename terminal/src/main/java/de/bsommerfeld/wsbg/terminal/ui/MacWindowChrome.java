package de.bsommerfeld.wsbg.terminal.ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JFrame;
import java.awt.Dimension;

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
        // First-paint kick — deferred 250 ms so the reparenting that
        // fires inside addNotify is fully done before we perturb the
        // layout. JCEF on macOS lazily initialises the Chromium NSView
        // and doesn't render until something invalidates its bounds;
        // the page stays white until the user resizes manually.
        // A Swing Timer (rather than invokeLater) lets the EDT run
        // other queued work first.
        javax.swing.Timer kick = new javax.swing.Timer(250, e -> {
            Dimension size = frame.getSize();
            frame.setSize(size.width + 1, size.height);
            frame.setSize(size.width, size.height);
        });
        kick.setRepeats(false);
        kick.start();

        // Carve the native title-bar interception out of the right-hand action
        // buttons (donate / gear / update) so their clicks land instantly instead
        // of waiting on AppKit's drag / double-click-zoom disambiguation. Deferred
        // past the kick so the window is fully on screen (and in [NSApp windows])
        // before we look it up. Best-effort; a failure just keeps the old lag.
        javax.swing.Timer carve = new javax.swing.Timer(600, e -> MacTitlebarCarveout.install(frame));
        carve.setRepeats(false);
        carve.start();
    }
}
