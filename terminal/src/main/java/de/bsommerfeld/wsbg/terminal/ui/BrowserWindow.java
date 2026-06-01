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
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Top-level Swing window hosting the JCEF browser.
 *
 * <p>
 * Window chrome is platform-split:
 * <ul>
 *   <li><b>macOS</b> — the {@code JFrame} stays decorated (JCEF
 *   reparenting requires a standard NSWindow) but the OS title bar is
 *   made transparent via {@code apple.awt.*} root-pane properties, so the
 *   HTML titlebar (app title + theme toggle) sits flush over the native
 *   traffic lights.</li>
 *   <li><b>Windows/Linux</b> — the frame keeps its full native OS title
 *   bar (real min/max/close, native drag/resize/snap). On Windows it is
 *   themed dark via {@link WindowsChrome}. The page hides its HTML
 *   titlebar there to avoid duplicate chrome.</li>
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
        loadIcon().ifPresent(icon -> frame.setIconImages(scaledIconSet(icon)));

        // On macOS, JCEF reparents its Chromium NSWindow into the JFrame's
        // NSWindow via sun.lwawt.macosx internals. That reparenting fails
        // on undecorated JFrames because they create a borderless NSWindow
        // variant the JCEF helper doesn't recognise, producing two stray
        // windows (a blank JFrame + a free-floating Chromium window).
        //
        // Workaround: keep the JFrame decorated and hide the OS title
        // bar via macOS-only root pane client properties. The HTML's
        // custom titlebar then sits flush at the top of the content.
        //
        // Windows/Linux keep the *native* OS decoration — real min/max/
        // close buttons, native drag, native edge-resize and Aero snap,
        // which is what users expect there (and what IntelliJ-style apps
        // provide). The page hides its own HTML titlebar on those
        // platforms (data-platform="other"), so there's no duplicate
        // chrome. The only thing the OS won't do by itself is theme the
        // title bar dark; that is applied post-show via WindowsChrome.
        if (isMac()) {
            frame.getRootPane().putClientProperty("apple.awt.fullWindowContent", true);
            frame.getRootPane().putClientProperty("apple.awt.transparentTitleBar", true);
            frame.getRootPane().putClientProperty("apple.awt.windowTitleVisible", false);
        }

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
        frame.getContentPane().add(browserUi, BorderLayout.CENTER);

        frame.setSize(1280, 820);
        frame.setLocationRelativeTo(null);

        dragHandler.bind(frame);

        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                LOG.info("Window closing — disposing JCEF (async); JVM exits on TERMINATED.");
                // Force the browser shut, then dispose the CefApp. This is
                // fire-and-forget on purpose: cefApp.dispose() returns
                // immediately and the native shutdown finishes later, at
                // which point CefHost's TERMINATED handler calls
                // System.exit(0) — the *only* place the JVM is torn down on
                // a clean close. We deliberately do NOT call System.exit
                // here (that would re-introduce the race this pattern
                // exists to avoid). frame.dispose() releases the native
                // window so it visually closes right away.
                try { browser.close(true); } catch (Throwable ignored) {}
                cefHost.dispose();
                frame.dispose();
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
        } else {
            // Windows: theme the native title bar dark to match the UI.
            // Deferred a tick so the native peer is fully realised, then a
            // 1px resize nudge forces the non-client area to repaint with
            // the new theme (and kicks JCEF's first paint, like the macOS
            // branch). No-op on Linux — WindowsChrome guards on the OS.
            javax.swing.Timer kick = new javax.swing.Timer(100, e -> {
                WindowsChrome.applyDarkTitleBar(frame);
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
            BufferedImage raw = ImageIO.read(in);
            return java.util.Optional.ofNullable(raw == null ? null : trimToContent(raw));
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
    }

    /**
     * Crops the source icon's transparent border and re-centres it on a square
     * canvas with a small (~6%) margin. The shared art carries ~20% macOS-style
     * padding, which renders the Windows/Linux taskbar icon visibly smaller than
     * its neighbours (those fill the square). Trimming makes our icon fill its
     * slot the same way. macOS is unaffected — its dock icon comes from the
     * {@code -Xdock:icon} flag, not this {@code setIconImages} call.
     */
    private static BufferedImage trimToContent(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int minX = w, minY = h, maxX = -1, maxY = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((img.getRGB(x, y) >>> 24) > 8) { // alpha above a tiny threshold
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < minX) return img; // fully transparent — nothing to trim

        int cw = maxX - minX + 1, ch = maxY - minY + 1;
        BufferedImage content = img.getSubimage(minX, minY, cw, ch);

        int side = (int) (Math.max(cw, ch) / 0.88); // ~6% margin per side
        BufferedImage square = new BufferedImage(side, side, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = square.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(content, (side - cw) / 2, (side - ch) / 2, null);
        g.dispose();
        return square;
    }

    /**
     * Builds a multi-resolution icon set from the 1024px source. Handing AWT a
     * single oversized image makes Windows pick a poor downscale for the small
     * taskbar/title-bar slots; supplying explicit small sizes lets it choose a
     * crisp match per slot. The full-size source stays in the list for the
     * large Alt+Tab / window-switcher rendering.
     */
    private static java.util.List<Image> scaledIconSet(Image source) {
        return java.util.List.of(
                source.getScaledInstance(16, 16, Image.SCALE_SMOOTH),
                source.getScaledInstance(24, 24, Image.SCALE_SMOOTH),
                source.getScaledInstance(32, 32, Image.SCALE_SMOOTH),
                source.getScaledInstance(48, 48, Image.SCALE_SMOOTH),
                source.getScaledInstance(64, 64, Image.SCALE_SMOOTH),
                source.getScaledInstance(128, 128, Image.SCALE_SMOOTH),
                source.getScaledInstance(256, 256, Image.SCALE_SMOOTH),
                source);
    }
}
