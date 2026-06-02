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

    // Off-screen (windowless) rendering — Windows only. Renders into a single,
    // same-thread JOGL GLCanvas instead of a nested Chromium child, which is the
    // foundation the native custom title bar needs (its WM_NCHITTEST forwards
    // HTTRANSPARENT to the frame → native drag/snap/maximize). Requires a
    // **JDK ≤ 25**: JOGL's GLCanvas references java.applet.Applet, removed in
    // JDK 26 (NoClassDefFoundError on addNotify). macOS/Linux stay windowed.
    private static final boolean OSR =
            System.getProperty("os.name", "").toLowerCase().contains("win");

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
            // OSR (windowless) on Windows: the browser renders into a single
            // JOGL GLCanvas instead of a nested Chromium child window. That
            // single, same-thread (AWT) child is what makes the native custom
            // title bar work — its WM_NCHITTEST can forward HTTRANSPARENT to the
            // frame, which then drives native drag/snap/maximize (see
            // WindowsCustomChrome). macOS/Linux stay windowed: OSR routes through
            // JOGL whose jcefmaven natives have no arm64 build (Apple Silicon
            // crashes with UnsatisfiedLinkError), and the native chrome is
            // Windows-only anyway.
            settings.windowless_rendering_enabled = OSR;
            settings.cache_path = resolveCacheDir().toAbsolutePath().toString();
            settings.persist_session_cookies = false;
            settings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_WARNING;

            builder.setAppHandler(new MavenCefAppHandlerAdapter() {
                @Override
                public void stateHasChanged(CefAppState state) {
                    if (state == CefAppState.TERMINATED) {
                        LOG.info("CefApp terminated; exiting JVM.");
                        System.exit(0);
                    }
                }
            });

            // Chromium command-line flags. Tuned for low-latency desktop
            // rendering and smooth live resize on macOS.
            builder.addJcefArgs(
                    "--disable-gpu-vsync",                       // lower input latency
                    "--enable-features=UseOzonePlatform",
                    "--enable-begin-frame-control",              // smoother frame pacing
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
        // 2nd arg = off-screen rendered (OSR). On Windows this yields a single
        // GLCanvas child the native title-bar chrome can work with.
        return client().createBrowser(url, OSR, false);
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
