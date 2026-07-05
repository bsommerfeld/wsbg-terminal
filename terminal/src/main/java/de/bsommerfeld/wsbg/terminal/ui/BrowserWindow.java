package de.bsommerfeld.wsbg.terminal.ui;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.cef.browser.CefBrowser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.Objects;

/**
 * Top-level Swing window hosting the JCEF browser.
 *
 * <p>
 * Window chrome is platform-split behind {@link WindowChrome}:
 * <ul>
 *   <li><b>macOS</b> ({@link MacWindowChrome}) — the {@code JFrame} stays
 *   decorated (JCEF reparenting requires a standard NSWindow) but the OS title
 *   bar is made transparent via {@code apple.awt.*} root-pane properties, so the
 *   HTML titlebar (app title + theme toggle) sits flush over the native traffic
 *   lights.</li>
 *   <li><b>Windows</b> ({@link WinLinuxWindowChrome}) — the frame keeps the
 *   native resize border, Aero Snap and drop shadow, but {@link
 *   WindowsCustomChrome} strips the OS caption so the HTML titlebar draws flush
 *   at the top (same look as macOS, window controls on the right). The remaining
 *   frame is themed dark via {@link WindowsChrome}.</li>
 *   <li><b>Linux</b> — keeps the full native OS title bar; the page hides its
 *   HTML titlebar there to avoid duplicate chrome.</li>
 * </ul>
 *
 * <p>
 * {@link WindowDragHandler} remains wired to the {@code window} command
 * channel but is dormant now that all platforms use native chrome.
 */
@Singleton
public final class BrowserWindow {

    private static final Logger LOG = LoggerFactory.getLogger(BrowserWindow.class);

    private final CefHost cefHost;
    private final WindowDragHandler dragHandler;
    private final WindowChrome chrome = WindowChrome.forCurrentOs();

    private JFrame frame;
    private CefBrowser browser;
    private Runnable onClose = () -> {};
    private final java.util.concurrent.atomic.AtomicBoolean shuttingDown =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    @Inject
    public BrowserWindow(CefHost cefHost, WindowDragHandler dragHandler) {
        this.cefHost = cefHost;
        this.dragHandler = dragHandler;
    }

    /**
     * Graceful service shutdown to run on the close gesture, BEFORE the CEF
     * teardown. Set by {@code AppMain}. CEF's native shutdown hard-exits the
     * JVM, so anything that must stop cleanly (notably the spawned Ollama)
     * has to run here, not in a JVM shutdown hook that never fires.
     */
    public void setOnClose(Runnable onClose) {
        this.onClose = onClose;
    }

    public void open(String url) {
        // Live-resize behaviour: AWT layouts during drag instead of after.
        Toolkit.getDefaultToolkit().setDynamicLayout(true);

        frame = new JFrame("WSBG Terminal");
        // DO_NOTHING — not EXIT_ON_CLOSE. JCEF's native teardown is
        // asynchronous: CefApp.dispose() only *starts* the multi-stage
        // shutdown of the Chromium subprocesses (GPU/renderer/network
        // "jcef Helper") and signals completion later via
        // stateHasChanged(TERMINATED). If EXIT_ON_CLOSE fired its own
        // System.exit(0) here, the JVM would race that teardown and could
        // exit first — orphaning the helper processes and leaving the
        // remote-debugging port (localhost:9222) momentarily bound. So the
        // close gesture only kicks off the dispose; the TERMINATED handler
        // in CefHost owns the single System.exit(0) once CEF is truly gone.
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        frame.setMinimumSize(new java.awt.Dimension(800, 600));
        AppIconLoader.load().ifPresent(icon -> frame.setIconImages(AppIconLoader.scaledSet(icon)));

        // Windows/Linux keep the *native* OS decoration — real min/max/
        // close buttons, native drag, native edge-resize and Aero snap,
        // which is what users expect there (and what IntelliJ-style apps
        // provide). The page hides its own HTML titlebar on those
        // platforms (data-platform="other"), so there's no duplicate
        // chrome. macOS hides the OS title bar via root-pane properties.
        // The chrome strategy applies the platform-specific pre-show setup
        // (and, on macOS, the Cmd+Q → graceful-shutdown quit handler).
        chrome.applyBeforeShow(frame, this::gracefulShutdown);

        // NOTE: do NOT call setIgnoreRepaint(true) on the frame / rootPane /
        // contentPane here. JCEF's windowed browser (CefBrowserWr) only creates
        // its native Chromium window the first time its UI JPanel receives a
        // paint(Graphics) — that paint is what starts the delayedUpdate timer
        // which calls createBrowserIfRequired(). Suppressing repaints on the
        // ancestors starves that first paint, so the browser (and its renderer
        // process) is never created and the canvas stays permanently white.
        // Smoother live-resize is not worth a window that never renders.
        frame.getContentPane().setLayout(new BorderLayout());

        // Order matters for JCEF on macOS:
        //   1. create the browser
        //   2. add its UIComponent to the realised content pane
        //   3. size + position the JFrame
        //   4. setVisible — addNotify cascades and the Chromium
        //      NSWindow reparents into the JFrame NSWindow
        // Calling setVisible before the browser is added produces the
        // two-window split (blank JFrame + free Chromium window).
        // Sizing AFTER the browser is in place avoids a stale size
        // confusing the reparenting code.
        browser = cefHost.createBrowser(url);
        Component browserUi = browser.getUIComponent();
        // NOTE: do NOT setIgnoreRepaint(true) on the OSR GLCanvas — like the
        // windowed CefBrowserWr, the OSR browser only finishes initialising
        // after its component takes its first paint; suppressing it starves
        // the browser so the page never loads (no devtools target, no socket).
        // Flicker is handled via sun.awt.noerasebackground (AppMain) instead.
        frame.getContentPane().add(browserUi, BorderLayout.CENTER);

        frame.setSize(1280, 820);
        frame.setLocationRelativeTo(null);

        dragHandler.bind(frame);

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                gracefulShutdown();
            }
        });

        frame.setVisible(true);

        // Deferred, post-realise platform kicks (title-bar theming + first-paint
        // nudge). macOS lazily initialises the Chromium NSView and doesn't render
        // until something invalidates its bounds; Windows strips the caption and
        // themes the frame dark. See the WindowChrome impls.
        chrome.applyAfterShow(frame);

        LOG.info("Browser window opened.");
    }

    /**
     * The single graceful-teardown path, shared by the window-close gesture
     * (HTML close button / native title-bar X → windowClosing) and the macOS
     * app-quit (Cmd+Q → quit handler). Idempotent: only the first caller runs it.
     *
     * <p>
     * ORDER IS LOAD-BEARING. CEF's native shutdown hard-exits the process before
     * {@code setState(TERMINATED)}, so a JVM shutdown hook never fires — the
     * services that must die with us (above all the spawned Ollama) are stopped
     * synchronously HERE first, then CEF is torn down, then we force the exit.
     */
    private void gracefulShutdown() {
        if (!shuttingDown.compareAndSet(false, true)) return;
        LOG.info("Graceful service shutdown, then CEF teardown.");
        try { onClose.run(); } catch (Throwable ignored) {}
        // setCloseAllowed() before close(true): CEF's close handshake calls
        // doClose(), which otherwise vetoes the close and re-posts WINDOW_CLOSING,
        // stalling CefApp for a ~100s internal timeout. Pre-approving makes
        // onBeforeClose fire immediately.
        try { browser.setCloseAllowed(); } catch (Throwable ignored) {}
        try { browser.close(true); } catch (Throwable ignored) {}
        try { cefHost.dispose(); } catch (Throwable ignored) {}
        try { if (frame != null) frame.dispose(); } catch (Throwable ignored) {}
        // Window is gone; cefApp.dispose() only *started* the async helper
        // teardown. Reap any Chromium "jcef Helper" still shutting down before
        // the exit, else on Windows (no POSIX orphan reaping) they linger.
        CefHost.reapHelperProcesses();
        System.exit(0);
    }

    public JFrame frame() {
        return Objects.requireNonNull(frame, "open() not called yet");
    }

    /**
     * Brings the window to the front. Invoked when a second-instance
     * launch is rejected — the user expects the existing window to pop
     * up instead of nothing happening.
     */
    public void raise() {
        if (frame == null) return;
        SwingUtilities.invokeLater(() -> {
            // De-iconify if minimised.
            int state = frame.getExtendedState();
            if ((state & Frame.ICONIFIED) != 0) {
                frame.setExtendedState(state & ~Frame.ICONIFIED);
            }
            if (!frame.isVisible()) frame.setVisible(true);
            // toFront alone doesn't reliably steal focus on macOS;
            // flipping alwaysOnTop momentarily forces the window
            // server to bring the window forward, then we drop the
            // flag so it doesn't sit on top of everything else.
            frame.setAlwaysOnTop(true);
            frame.toFront();
            frame.requestFocus();
            frame.setAlwaysOnTop(false);
        });
    }

    public CefBrowser browser() {
        return Objects.requireNonNull(browser, "open() not called yet");
    }
}
