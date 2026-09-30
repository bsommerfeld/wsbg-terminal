package de.bsommerfeld.tinybrowser;

import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.EnumProgress;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import org.cef.CefApp;
import org.cef.CefBrowserSettings;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.browser.HeadlessCefBrowser;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.cef.network.CefCookie;
import org.cef.network.CefCookieManager;

import javax.swing.SwingUtilities;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The embedded Chromium: one CEF app, one client, one message router that
 * carries every tab's answers home, and the hidden tabs themselves - windowless
 * browsers that render into nothing ({@link HeadlessCefBrowser}).
 *
 * <p>The router's page-side function gets a random name per run, so no site
 * can look for a fixed one. Its messages start with the tag of the tab they
 * belong to ({@link PageFetch}); {@link #onMessages} routes them there.
 */
final class Chromium implements Browser {

    /**
     * Chromium's switches for pages nobody looks at, as the terminal's
     * embedded browser on master ran its hidden tabs.
     */
    private static final List<String> SWITCHES = List.of(
            "--mute-audio",
            "--disable-gpu",
            "--disable-gpu-compositing",
            // Timers of a page that is never shown keep firing - a site's
            // visitor check must not stall on a throttled timer.
            "--disable-background-timer-throttling",
            "--disable-renderer-backgrounding",
            // No out-of-process iframes: every one would be a renderer process.
            "--disable-site-isolation-trials",
            // Scripts probe the camera and microphone; on macOS every probe is
            // a synchronous round trip to the privacy daemon. Fake devices
            // answer without it.
            "--use-fake-device-for-media-stream",
            "--use-fake-ui-for-media-stream",
            "--deny-permission-prompts");

    private final CefApp app;
    private final CefClient client;
    private final String queryFunction;
    private final Map<String, Consumer<String>> messageHandlers = new ConcurrentHashMap<>();
    private final List<BiConsumer<CefBrowser, Integer>> loadEndListeners = new CopyOnWriteArrayList<>();

    private Chromium(CefApp app, String queryFunction) {
        this.app = app;
        this.queryFunction = queryFunction;
        this.client = app.createClient();

        CefMessageRouter router = CefMessageRouter.create(
                new CefMessageRouter.CefMessageRouterConfig(queryFunction, queryFunction + "Cancel"));
        router.addHandler(new CefMessageRouterHandlerAdapter() {
            @Override
            public boolean onQuery(CefBrowser browser, CefFrame frame, long queryId, String request,
                    boolean persistent, CefQueryCallback callback) {
                /*
                 * Runs on the browser UI thread, where an escaping throwable
                 * is neither logged usefully nor recoverable - it just eats
                 * this message, and with it the whole fetch.
                */
                try {
                    int end = request == null ? -1 : request.indexOf(PageFetch.DELIMITER);
                    Consumer<String> handler = end < 0 ? null : messageHandlers.get(request.substring(0, end));
                    if (handler != null) {
                        handler.accept(request);
                    }
                } catch (Throwable failure) {
                    Log.debug("malformed page message: " + failure);
                }
                try {
                    callback.success("");
                } catch (Throwable failure) {
                    Log.debug("page message not acknowledged: " + failure);
                }
                return true;
            }
        }, true);
        client.addMessageRouter(router);

        client.addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
                if (frame.isMain()) {
                    loadEndListeners.forEach(listener -> listener.accept(browser, httpStatusCode));
                }
            }
        });
        client.addRequestHandler(new ResourcePolicy());
    }

    /**
     * Starts Chromium - installing it into {@code bundle} first if it is not
     * there, the one slow step (a download of about 100 MB).
     */
    static Chromium start(Path bundle, Path profile) throws Exception {
        CefAppBuilder builder = new CefAppBuilder();
        builder.setInstallDir(bundle.toFile());
        builder.setProgressHandler(new InstallProgress());
        builder.setAppHandler(new MavenCefAppHandlerAdapter() { });

        CefSettings settings = builder.getCefSettings();
        settings.windowless_rendering_enabled = true;
        settings.root_cache_path = profile.toString();
        settings.cache_path = profile.toString();
        settings.persist_session_cookies = true;
        settings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_DISABLE;
        builder.addJcefArgs(SWITCHES.toArray(String[]::new));

        CefApp[] app = new CefApp[1];
        Exception[] failure = new Exception[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                app[0] = builder.build();
            } catch (Exception e) {
                failure[0] = e;
            }
        });
        if (failure[0] != null) {
            throw failure[0];
        }
        return new Chromium(app[0], randomName());
    }

    /** {@code Chromium 146.0.7680.179}. */
    @Override
    public String version() {
        var version = app.getVersion();
        return version == null ? "Chromium" : "Chromium " + version.getChromeVersion();
    }

    /** The name of the page-side function that carries messages home. */
    String queryFunction() {
        return queryFunction;
    }

    /** Messages starting with {@code tag} go to {@code handler}. */
    void onMessages(String tag, Consumer<String> handler) {
        messageHandlers.put(tag, handler);
    }

    void removeMessages(String tag) {
        messageHandlers.remove(tag);
    }

    /** Every main-frame load end, with the browser and its HTTP status. */
    void addLoadEndListener(BiConsumer<CefBrowser, Integer> listener) {
        loadEndListeners.add(listener);
    }

    void removeLoadEndListener(BiConsumer<CefBrowser, Integer> listener) {
        loadEndListeners.remove(listener);
    }

    /**
     * Opens a hidden tab on {@code url}. Its frame rate is 1: a page nobody
     * sees must not paint - it loads, runs its scripts and answers
     * {@code fetch()} all the same. Created on the EDT, as JCEF wants it.
     *
     * @param created receives the tab before it starts loading, so a load-end
     *                listener comparing browsers already knows it
     */
    @Override
    public void open(String url, Consumer<Page> created, IntConsumer loaded) throws Exception {
        ChromiumPage page = new ChromiumPage(this, url, loaded);
        Runnable create = () -> {
            CefBrowserSettings settings = new CefBrowserSettings();
            settings.windowless_frame_rate = 1;
            HeadlessCefBrowser browser = new HeadlessCefBrowser(client, url, settings);
            page.attach(browser);
            created.accept(page);
            browser.createImmediately();
        };
        if (SwingUtilities.isEventDispatchThread()) {
            create.run();
        } else {
            SwingUtilities.invokeAndWait(create);
        }
    }

    /**
     * Closes a tab - with the {@code setCloseAllowed() → close(true)}
     * handshake; without the approval CEF vetoes the close and stalls.
     */
    void closeTab(CefBrowser tab) {
        SwingUtilities.invokeLater(() -> {
            try {
                tab.setCloseAllowed();
            } catch (Throwable ignored) {
                // closing anyway
            }
            try {
                tab.close(true);
            } catch (Throwable ignored) {
                // already gone
            }
        });
    }

    @Override
    public boolean plantCookie(String site, String name, String value, Instant expires) {
        Date now = new Date();
        return CefCookieManager.getGlobalManager().setCookie("https://www." + site + "/",
                new CefCookie(name, value, "." + site, "/", true, false, now, now, true, Date.from(expires)));
    }

    /**
     * Writes the cookies to the profile now, and that is all: the engine
     * leaves without CEF's shutdown, which has been known to hang.
     */
    @Override
    public void leave(long timeoutMillis) {
        CountDownLatch flushed = new CountDownLatch(1);
        try {
            CefCookieManager manager = CefCookieManager.getGlobalManager();
            if (manager == null || !manager.flushStore(flushed::countDown)) {
                return;
            }
            flushed.await(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Throwable failure) {
            Log.warn("cookies not flushed: " + failure);
        }
    }

    private static String randomName() {
        SecureRandom random = new SecureRandom();
        String alphabet = "abcdefghijklmnopqrstuvwxyz";
        StringBuilder name = new StringBuilder("_");
        for (int i = 0; i < 12; i++) {
            name.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return name.toString();
    }

    /** Logs the install's steps and every tenth percent - not every tick of the download. */
    private static final class InstallProgress implements me.friwi.jcefmaven.IProgressHandler {
        private EnumProgress lastState;
        private int lastTenth = -1;

        @Override
        public void handleProgress(EnumProgress state, float percent) {
            int tenth = percent < 0 ? -1 : (int) (percent / 10);
            if (state == lastState && tenth == lastTenth) {
                return;
            }
            lastState = state;
            lastTenth = tenth;
            Log.info("chromium " + state.name().toLowerCase() + (percent >= 0 ? " " + tenth * 10 + " %" : ""));
        }
    }
}
