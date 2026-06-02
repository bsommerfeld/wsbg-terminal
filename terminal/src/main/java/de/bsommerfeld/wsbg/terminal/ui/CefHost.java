package de.bsommerfeld.wsbg.terminal.ui;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;
import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import me.friwi.jcefmaven.impl.progress.ConsoleProgressHandler;
import org.cef.CefApp;
import org.cef.CefApp.CefAppState;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.network.CefRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Desktop;
import java.io.File;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the JCEF runtime — {@link CefApp} + {@link CefClient} are
 * process-wide singletons; only one instance per JVM is permitted.
 *
 * <p>
 * The native binaries live in {@code <appData>/jcef-bundle} (under the same
 * {@link StorageUtils#getAppDataDir() app-data root} as the bundled Ollama
 * runtime, models, fonts, and config — so the entire footprint sits in one
 * uninstall-clean directory). jcefmaven downloads them on first launch and
 * reuses them thereafter. The progress is logged via
 * {@link ConsoleProgressHandler}.
 */
@Singleton
public final class CefHost {

    private static final Logger LOG = LoggerFactory.getLogger(CefHost.class);

    private final AtomicBoolean started = new AtomicBoolean(false);
    private CefApp cefApp;
    private CefClient cefClient;

    @Inject
    public CefHost() {}

    /**
     * Initializes JCEF on first call. Subsequent calls are no-ops.
     * Must run on a non-EDT thread so the binary download doesn't block
     * the UI; in practice {@link AppMain} calls this from the EDT for
     * simplicity — the download only happens once.
     */
    public synchronized CefClient client() {
        if (started.compareAndSet(false, true)) {
            initialize();
        }
        return cefClient;
    }

    private void initialize() {
        try {
            CefAppBuilder builder = new CefAppBuilder();
            File installDir = resolveInstallDir();
            builder.setInstallDir(installDir);
            // The launcher's JCEF phase pre-populates this dir on first
            // run; presence of install.lock means we skip the slow
            // download path and the ConsoleProgressHandler stays quiet.
            if (new File(installDir, "install.lock").exists()) {
                LOG.info("Using pre-installed JCEF bundle at {}", installDir);
            } else {
                LOG.warn("JCEF bundle not pre-installed; jcefmaven will download "
                        + "it now (~120 MB). Run the launcher to install it ahead of time.");
            }
            builder.setProgressHandler(new ConsoleProgressHandler());

            CefSettings settings = builder.getCefSettings();
            // Software OSR: our SwingCefBrowser (created in createBrowser) paints
            // CEF's onPaint buffer into a lightweight Swing component — no JOGL,
            // no GL-clear flicker, and a single native window (no heavyweight
            // child). windowless rendering must be enabled for the browser to run
            // off-screen. The single window is what lets the native custom title
            // bar (WindowsCustomChrome) hit-test the whole frame directly.
            settings.windowless_rendering_enabled = true;
            settings.cache_path = resolveCacheDir().toAbsolutePath().toString();
            settings.persist_session_cookies = false;
            settings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_WARNING;

            builder.setAppHandler(new MavenCefAppHandlerAdapter() {
                @Override
                public void stateHasChanged(CefAppState state) {
                    // Sole owner of the clean-close JVM exit. The window's
                    // close gesture only triggers cefApp.dispose() (async);
                    // CEF reaches TERMINATED once every Chromium subprocess
                    // is gone, and only then do we exit. This System.exit(0)
                    // runs AppMain's shutdown hook, which tears down the
                    // remaining services. Nothing else calls System.exit on
                    // the close path, so there's no race against this.
                    if (state == CefAppState.TERMINATED) {
                        LOG.info("CefApp terminated; exiting JVM.");
                        System.exit(0);
                    }
                }
            });

            // Chromium command-line flags.
            //
            // NOTE: --disable-gpu-vsync and --enable-begin-frame-control are
            // deliberately NOT set. They were harmless in windowed mode, but
            // under OSR they cause heavy flicker: begin-frame-control makes
            // Chromium produce frames only on an external BeginFrame we don't
            // drive (→ black/half frames), and vsync-off tears the GLCanvas
            // present. Letting Chromium pace its own frames renders cleanly.
            builder.addJcefArgs(
                    "--enable-features=UseOzonePlatform",
                    "--disable-background-timer-throttling",     // keep our intervals firing
                    "--disable-renderer-backgrounding",
                    "--disable-features=CalculateNativeWinOcclusion",
                    // Expose DevTools over HTTP on the loopback so we can
                    // inspect the page from any browser at:
                    //     http://localhost:9222
                    // Cheaper than wiring a right-click context menu and
                    // doesn't require the user to remember a shortcut.
                    "--remote-debugging-port=9222");

            cefApp = builder.build();
            cefClient = cefApp.createClient();
            cefClient.addRequestHandler(new ExternalLinkRouter());
            LOG.info("JCEF initialized.");
        } catch (Exception e) {
            throw new RuntimeException("JCEF initialization failed", e);
        }
    }

    /**
     * Intercepts user-initiated navigation to non-local URLs and opens
     * them in the OS default browser instead of inside the embedded
     * Chromium. The terminal page lives at 127.0.0.1 and never needs
     * to navigate away; anything else is a third-party link (e.g. the
     * donate link in the footer) that belongs in the user's regular
     * browser.
     */
    private static final class ExternalLinkRouter extends CefRequestHandlerAdapter {
        @Override
        public boolean onBeforeBrowse(CefBrowser browser, CefFrame frame, CefRequest request,
                                      boolean userGesture, boolean isRedirect) {
            // Only intercept gestures — page-loads, XHR, WebSocket, and
            // internal scheme-handler hits all leave userGesture=false
            // and must be allowed through unchanged.
            if (!userGesture) return false;
            String url = request.getURL();
            if (url == null) return false;
            if (url.startsWith("http://127.0.0.1") || url.startsWith("ws://127.0.0.1")) {
                return false;
            }
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    Desktop.getDesktop().browse(URI.create(url));
                }
            } catch (Exception e) {
                LOG.warn("Failed to open external URL {}: {}", url, e.getMessage());
            }
            return true; // cancel in-browser navigation
        }
    }

    public CefBrowser createBrowser(String url) {
        // Our own software OSR browser (SwingCefBrowser) instead of jcef's
        // stock CefBrowserOsr: CEF's onPaint buffer is blitted into a lightweight
        // Swing component — no JOGL, no GL-clear flicker, clean shutdown, and
        // live resize tracks. A single window (no heavyweight child) is exactly
        // what WindowsCustomChrome needs to hit-test the title bar natively.
        // createImmediately() does the native create now (no paint-triggered
        // lazy init like the GLCanvas had).
        org.cef.browser.SwingCefBrowser browser =
                new org.cef.browser.SwingCefBrowser(client(), url, false, null);
        browser.createImmediately();
        return browser;
    }

    public synchronized void dispose() {
        if (cefApp != null) {
            try {
                cefApp.dispose();
            } catch (Throwable ignored) {}
            cefApp = null;
            cefClient = null;
        }
    }

    private static File resolveInstallDir() {
        return StorageUtils.getAppDataDir().resolve("jcef-bundle").toFile();
    }

    private static Path resolveCacheDir() {
        return StorageUtils.getAppDataDir().resolve("cef");
    }
}
