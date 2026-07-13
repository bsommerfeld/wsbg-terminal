package de.bsommerfeld.wsbg.terminal.ui;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;
import de.bsommerfeld.wsbg.terminal.ui.scroll.WheelScrollPolicy;
import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import me.friwi.jcefmaven.impl.progress.ConsoleProgressHandler;
import org.cef.CefApp;
import org.cef.CefApp.CefAppState;
import org.cef.CefBrowserSettings;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.handler.CefLifeSpanHandlerAdapter;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefMessageRouterHandler;
import org.cef.handler.CefRequestHandlerAdapter;
import org.cef.network.CefRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

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
    private final WheelScrollPolicy wheelScrollPolicy;
    private CefApp cefApp;
    private CefClient cefClient;

    /**
     * Page→Java return channel for browser-driven fetches (see
     * {@code ui.net.CefFetchClient}). Registered once during {@link #initialize()}
     * BEFORE any browser is created — JCEF binds message routers to a browser at
     * creation time, so adding it afterwards would silently miss every browser.
     * The {@code window.wsbgFetchQuery(...)} JS function it injects is harmless to
     * the UI page, which never calls it.
     */
    private CefMessageRouter fetchRouter;

    /**
     * Fan-out for main-frame load-end events. A single {@code CefLoadHandler} slot
     * exists per client; this multiplexer lets several consumers (the headless
     * fetch browsers) observe "their" browser finishing a navigation without
     * fighting over that slot.
     */
    private final CopyOnWriteArrayList<BiConsumer<CefBrowser, Integer>> loadEndListeners =
            new CopyOnWriteArrayList<>();

    @Inject
    public CefHost(WheelScrollPolicy wheelScrollPolicy) {
        this.wheelScrollPolicy = wheelScrollPolicy;
    }

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
            configureSettings(builder);

            cefApp = builder.build();
            cefClient = cefApp.createClient();
            installExternalLinkHandlers(cefClient);
            installFetchPlumbing(cefClient);
            LOG.info("JCEF initialized.");
        } catch (Exception e) {
            throw new RuntimeException("JCEF initialization failed", e);
        }
    }

    /** CefSettings, the app-state handler, and the Chromium command-line flags. */
    private void configureSettings(CefAppBuilder builder) {
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
                // Fallback owner of the clean-close JVM exit. In practice
                // the close gesture (BrowserWindow.gracefulShutdown) drives
                // the exit itself: cefApp.dispose() is async, so the window
                // path force-exits after reaping the Chromium helpers rather
                // than waiting for TERMINATED to arrive. This handler still
                // covers a dispose() that is NOT followed by an explicit
                // System.exit — it fires once every Chromium subprocess is
                // gone. Idempotent with the window path (whoever exits first
                // wins).
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
                // Blink animates wheel scrolling, decoupling the visual motion
                // from our discrete wheel-event cadence and the 60fps OSR cap.
                // Verified to noticeably smooth trackpad scrolling under OSR.
                "--enable-smooth-scrolling",
                "--disable-background-timer-throttling",     // keep our intervals firing
                "--disable-renderer-backgrounding",
                "--disable-features=CalculateNativeWinOcclusion",
                // Expose DevTools over HTTP on the loopback so we can
                // inspect the page from any browser at:
                //     http://localhost:9222
                // Cheaper than wiring a right-click context menu and
                // doesn't require the user to remember a shortcut.
                "--remote-debugging-port=9222",
                // Loopback-only port; allow tooling (CDP scripts) to attach.
                "--remote-allow-origins=*");
    }

    /** External-link interception: user navigations + target="_blank" popups. */
    private void installExternalLinkHandlers(CefClient client) {
        client.addRequestHandler(new ExternalLinkRouter());
        // target="_blank" anchors (every external link in the page: donate
        // heart, banner links, Reddit/FJ header glyphs) arrive as POPUPS,
        // not as onBeforeBrowse navigations — without this handler the
        // click silently dies (no popup window exists in OSR mode).
        client.addLifeSpanHandler(new CefLifeSpanHandlerAdapter() {
            @Override
            public boolean onBeforePopup(CefBrowser browser, CefFrame frame,
                                         String targetUrl, String targetFrameName) {
                if (targetUrl != null && targetUrl.startsWith("http")) {
                    openExternal(targetUrl);
                }
                return true; // never create an in-app popup window
            }
        });
    }

    /**
     * Headless-fetch plumbing (the {@code wsbgFetchQuery} router + the load-end
     * multiplexer). BOTH must be registered HERE, before any browser exists:
     * JCEF wires routers/handlers into a browser when the browser is created, so
     * a late registration would never reach it. This ordering — call it from
     * {@link #initialize()} ahead of every {@code createBrowser} — is
     * load-bearing.
     */
    private void installFetchPlumbing(CefClient client) {
        fetchRouter = CefMessageRouter.create(
                new CefMessageRouter.CefMessageRouterConfig("wsbgFetchQuery", "wsbgFetchQueryCancel"));
        client.addMessageRouter(fetchRouter);
        client.addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
                if (frame == null || !frame.isMain()) return;
                for (BiConsumer<CefBrowser, Integer> l : loadEndListeners) {
                    try {
                        l.accept(browser, httpStatusCode);
                    } catch (Exception e) {
                        LOG.debug("load-end listener threw: {}", e.getMessage());
                    }
                }
            }
        });
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
            openExternal(url);
            return true; // cancel in-browser navigation
        }
    }

    /**
     * Opens {@code url} in the OS default browser; failures are logged, never
     * thrown. Public because the page itself routes external link clicks here
     * over the socket ({@code open-external} in {@code CommandBridge}) — the
     * CEF-side interception below is only the fallback for navigations that
     * bypass the page's click handler.
     */
    public static void openExternal(String url) {
        DesktopLauncher.openExternal(url);
    }

    /**
     * Reveals {@code dir} in the OS file manager (Finder / Explorer / Nautilus);
     * the directory is created first if it's missing so the OPEN action never
     * fails on a fresh install. Failures are logged, never thrown. Used by the
     * Settings "Zu den Logs" button to open the app-data folder.
     */
    public static void openFolder(Path dir) {
        DesktopLauncher.openFolder(dir);
    }

    public CefBrowser createBrowser(String url) {
        // Our own software OSR browser (SwingCefBrowser) instead of jcef's
        // stock CefBrowserOsr: CEF's onPaint buffer is blitted into a lightweight
        // Swing component — no JOGL, no GL-clear flicker, clean shutdown, and
        // live resize tracks. A single window (no heavyweight child) is exactly
        // what WindowsCustomChrome needs to hit-test the title bar natively.
        // createImmediately() does the native create now (no paint-triggered
        // lazy init like the GLCanvas had).
        // OSR paints at windowless_frame_rate (CEF default 30, hard max 60). Set
        // it to the cap at creation via CefBrowserSettings — the dynamic
        // setWindowlessFrameRate() setter no-ops right after createImmediately()
        // because the native browser isn't created yet. 120Hz is not reachable:
        // it would need external begin-frame control, which JCEF exposes no Java
        // API to drive (so enabling it only yields black frames — see initialize()).
        return createBrowser(url, 60);
    }

    /**
     * Browser for headless same-origin fetching (CefFetchClient): never shown, so
     * it must NOT burn CPU painting. A minimal windowless frame rate means CEF
     * calls onPaint ~1×/sec instead of 60×/sec — the page still loads, runs JS
     * and serves {@code fetch()} calls (those are paint-independent), but it no
     * longer competes with the VISIBLE browser's Java2D paint pipeline. Without
     * this, the hidden reddit.com renderer blits a full invisible page 60×/sec.
     */
    public CefBrowser createFetchBrowser(String url) {
        return createBrowser(url, 1);
    }

    private CefBrowser createBrowser(String url, int frameRate) {
        CefBrowserSettings settings = new CefBrowserSettings();
        settings.windowless_frame_rate = frameRate;
        org.cef.browser.SwingCefBrowser browser = new org.cef.browser.SwingCefBrowser(
                client(), url, false, null, wheelScrollPolicy, settings);
        browser.createImmediately();
        return browser;
    }

    /**
     * Registers a handler on the shared headless-fetch message router. Triggers
     * JCEF initialization if it hasn't happened yet, so the router exists. A
     * handler may be added at any time (unlike the router itself, which must be
     * registered before browser creation — handled in {@link #initialize()}).
     */
    public void addFetchQueryHandler(CefMessageRouterHandler handler) {
        client(); // ensure initialize() ran and fetchRouter is non-null
        fetchRouter.addHandler(handler, true);
    }

    /**
     * Subscribes to main-frame load-end events. The listener receives the
     * browser that finished loading and the HTTP status of its main resource;
     * filter by browser identity to react only to your own.
     */
    public void addLoadEndListener(BiConsumer<CefBrowser, Integer> listener) {
        client(); // ensure the multiplexing load handler is wired
        loadEndListeners.add(listener);
    }

    /** Removes a load-end listener again — short-lived consumers (e.g. a one-shot
     *  PDF print browser) must not accumulate in the multiplex list. */
    public void removeLoadEndListener(BiConsumer<CefBrowser, Integer> listener) {
        loadEndListeners.remove(listener);
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

    /**
     * Force-terminates any Chromium "jcef Helper" subprocesses that
     * {@link #dispose()} hasn't finished reaping, then returns. Must be called
     * right before a {@code System.exit(0)} on every close path.
     *
     * <p>{@code cefApp.dispose()} tears the GPU/renderer/network helper
     * processes down <b>asynchronously</b>: it only signals them and reports
     * completion later via {@code stateHasChanged(TERMINATED)}. The close paths
     * force-exit without waiting for that signal. On POSIX the exiting parent's
     * orphans are reparented to {@code init} and still reaped by the OS, but
     * <b>Windows has no such reaping</b> — any helper still shutting down when
     * the JVM exits is left running in the background (the reported
     * "jcef-helper lingers after close" bug). This gives dispose() a short grace
     * period to finish on its own, then force-kills whatever jcef helpers
     * remain, guaranteeing a clean exit on every platform.
     *
     * <p>Matched narrowly by command name ("jcef") so it can never touch a
     * sibling we deliberately spawned — notably the update launcher started by
     * {@code AppMain.relaunchForUpdate()} just before it tears CEF down.
     */
    public static void reapHelperProcesses() {
        CefHelperReaper.reap();
    }

    private static File resolveInstallDir() {
        return StorageUtils.getAppDataDir().resolve("jcef-bundle").toFile();
    }

    private static Path resolveCacheDir() {
        return StorageUtils.getAppDataDir().resolve("cef");
    }
}
