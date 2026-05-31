package de.bsommerfeld.wsbg.terminal.ui;

import com.google.inject.Inject;
import com.google.inject.Singleton;
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
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns the JCEF runtime — {@link CefApp} + {@link CefClient} are
 * process-wide singletons; only one instance per JVM is permitted.
 *
 * <p>
 * The native binaries live in {@code ~/jcef-bundle}; jcefmaven downloads
 * them on first launch and reuses them thereafter. The progress is logged
 * via {@link ConsoleProgressHandler}.
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
            // OSR mode (windowless_rendering_enabled=true) would give us
            // perfect live resize but it routes painting through JOGL,
            // and the JOGL natives jcefmaven ships do not include an
            // arm64 build — the renderer crashes on Apple Silicon with
            // an UnsatisfiedLinkError. Until JCEF/jcefmaven update their
            // JOGL bundle we stay in windowed (heavy-weight) mode and
            // accept that the page snaps to the new size after the
            // resize gesture ends instead of tracking live.
            settings.windowless_rendering_enabled = false;
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
        return client().createBrowser(url, false, false);
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
        String home = System.getProperty("user.home", ".");
        return Paths.get(home, "jcef-bundle").toFile();
    }

    private static Path resolveCacheDir() {
        String home = System.getProperty("user.home", ".");
        return Paths.get(home, ".cache", "wsbg-terminal", "cef");
    }
}
