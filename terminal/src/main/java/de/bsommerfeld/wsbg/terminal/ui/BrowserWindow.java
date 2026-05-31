package de.bsommerfeld.wsbg.terminal.ui;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.cef.browser.CefBrowser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Top-level frameless Swing window. The HTML provides its own titlebar
 * (macOS-style traffic lights, app title, refresh/settings/fullscreen
 * buttons); the OS-level chrome is intentionally hidden so the page can
 * own the entire surface.
 *
 * <p>
 * Window drag and the close/minimize/maximize buttons are wired through
 * {@link WindowDragHandler}, which receives commands from the page via
 * the WebSocket push hub.
 */
@Singleton
public final class BrowserWindow {

    private static final Logger LOG = LoggerFactory.getLogger(BrowserWindow.class);

    private final CefHost cefHost;
    private final WindowDragHandler dragHandler;

    private JFrame frame;
    private CefBrowser browser;

    @Inject
    public BrowserWindow(CefHost cefHost, WindowDragHandler dragHandler) {
        this.cefHost = cefHost;
        this.dragHandler = dragHandler;
    }

    public void open(String url) {
        // Live-resize behaviour: AWT layouts during drag instead of after.
        Toolkit.getDefaultToolkit().setDynamicLayout(true);

        frame = new JFrame("WSBG Terminal");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setMinimumSize(new java.awt.Dimension(800, 600));
        loadIcon().ifPresent(frame::setIconImage);

        // On macOS, JCEF reparents its Chromium NSWindow into the JFrame's
        // NSWindow via sun.lwawt.macosx internals. That reparenting fails
        // on undecorated JFrames because they create a borderless NSWindow
        // variant the JCEF helper doesn't recognise, producing two stray
        // windows (a blank JFrame + a free-floating Chromium window).
        //
        // Workaround: keep the JFrame decorated and hide the OS title
        // bar via macOS-only root pane client properties. The HTML's
        // custom titlebar then sits flush at the top of the content.
        if (isMac()) {
            frame.getRootPane().putClientProperty("apple.awt.fullWindowContent", true);
            frame.getRootPane().putClientProperty("apple.awt.transparentTitleBar", true);
            frame.getRootPane().putClientProperty("apple.awt.windowTitleVisible", false);
        } else {
            frame.setUndecorated(true);
        }

        // Swing-side repaint suppression keeps the heavyweight Canvas
        // free of double-buffer fills during live resize. The Canvas
        // itself is intentionally NOT in this list — that flag breaks
        // JCEF's macOS reparenting (addNotify path).
        frame.setIgnoreRepaint(true);
        frame.getRootPane().setIgnoreRepaint(true);
        frame.getContentPane().setIgnoreRepaint(true);

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
        frame.getContentPane().add(browserUi, BorderLayout.CENTER);

        frame.setSize(1280, 820);
        frame.setLocationRelativeTo(null);

        dragHandler.bind(frame);

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                LOG.info("Window closing.");
                try { browser.close(true); } catch (Throwable ignored) {}
                cefHost.dispose();
            }
        });

        frame.setVisible(true);

        // First-paint kick — deferred 250 ms so the reparenting that
        // fires inside addNotify is fully done before we perturb the
        // layout. JCEF on macOS lazily initialises the Chromium NSView
        // and doesn't render until something invalidates its bounds;
        // the page stays white until the user resizes manually.
        // A Swing Timer (rather than invokeLater) lets the EDT run
        // other queued work first.
        if (isMac()) {
            javax.swing.Timer kick = new javax.swing.Timer(250, e -> {
                Dimension size = frame.getSize();
                frame.setSize(size.width + 1, size.height);
                frame.setSize(size.width, size.height);
            });
            kick.setRepeats(false);
            kick.start();
        }

        LOG.info("Browser window opened.");
    }

    private static boolean isMac() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
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

    private static java.util.Optional<Image> loadIcon() {
        try (InputStream in = BrowserWindow.class.getResourceAsStream("/images/app-icon.png")) {
            if (in == null) return java.util.Optional.empty();
            return java.util.Optional.of(ImageIO.read(in));
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
    }
}
