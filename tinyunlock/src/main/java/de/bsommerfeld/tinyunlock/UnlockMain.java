package de.bsommerfeld.tinyunlock;

import me.friwi.jcefmaven.CefAppBuilder;
import me.friwi.jcefmaven.MavenCefAppHandlerAdapter;
import org.cef.CefApp;
import org.cef.CefBrowserSettings;
import org.cef.CefClient;
import org.cef.CefSettings;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.HeadlessCefBrowser;
import org.cef.handler.CefLoadHandlerAdapter;
import org.cef.network.CefCookie;
import org.cef.network.CefCookieManager;
import org.cef.network.CefRequest;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The child process: opens one page in embedded Chromium the way a visitor
 * would, lets the page's own scripts run, and prints the cookies the site set
 * for itself on stdout - one Netscape cookie-file line each. Everything else
 * goes to stderr.
 *
 * <h3>Two modes</h3>
 * <ul>
 *   <li><b>Headless</b> (default): no window, no dock icon. Passes whatever a
 *       browser passes by itself - a script that checks the visitor, a
 *       redirect, a consent cookie. Stops at a CAPTCHA with exit code 3.</li>
 *   <li><b>Visible</b> ({@code --visible true}): a normal window showing the
 *       page, for a person to solve the CAPTCHA themselves. Nothing in it is
 *       clicked or filled in by the program. Closing the window is exit code 4.</li>
 * </ul>
 * Both use the same profile, so what the person solved stays solved for the
 * headless runs that follow.
 *
 * <h3>Arguments (the protocol TinyFetch's {@code ProcessUnlocker} speaks)</h3>
 * <pre>
 * --url &lt;page&gt;             the page to open
 * --bundle &lt;dir&gt;           the Chromium build (jcefmaven installs it there if missing)
 * --profile &lt;dir&gt;          the browser profile, kept across runs as a returning visitor's is
 * --seed-profile &lt;dir&gt;     an existing profile to start from while --profile is still empty
 * --await &lt;a,b&gt;            cookie names that mean "through" (optional)
 * --visible true           open a window for a person
 * --window-title &lt;text&gt;    the window's title, already in the user's language
 * --timeout-seconds &lt;n&gt;    give up after this long (default 45 headless, 600 visible)
 * --print-text true        diagnostics: the page's text to stderr
 * --prepare true           only make sure the Chromium build is installed, then exit
 * </pre>
 *
 * <h3>Exit codes</h3>
 * {@code 0} cookies on stdout are complete, {@code 1} failure, {@code 3} the
 * page is a CAPTCHA (headless), {@code 4} the person closed the window.
 */
public final class UnlockMain {

    static final int EXIT_OK = 0;
    static final int EXIT_FAILED = 1;
    static final int EXIT_CAPTCHA = 3;
    static final int EXIT_CLOSED = 4;

    private static final long QUIET_MILLIS = 1_500;
    private static final long POLL_MILLIS = 250;

    private UnlockMain() {
    }

    public static void main(String[] args) {
        /*
         * stdout is the answer channel. JCEF prints its own lines to
         * System.out, so everything but the cookie lines goes to stderr.
        */
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8);
        System.setOut(System.err);
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            System.err.println("tinyunlock: uncaught in " + thread.getName());
            failure.printStackTrace(System.err);
        });

        int exit;
        try {
            Arguments arguments = Arguments.parse(args);
            // Must precede anything that starts AWT: no dock icon unless a window is wanted.
            System.setProperty("apple.awt.UIElement", Boolean.toString(!arguments.visible()));
            Outcome outcome = arguments.prepare() ? prepare(arguments) : run(arguments);
            outcome.cookies().forEach(out::println);
            out.flush();
            exit = outcome.exitCode();
        } catch (Throwable t) {
            System.err.println("tinyunlock: " + t);
            exit = EXIT_FAILED;
        }
        /*
         * CEF's shutdown is asynchronous and has been known to hang on the
         * way out; the answer is already on stdout, so leave now.
        */
        Runtime.getRuntime().halt(exit);
    }

    private record Outcome(int exitCode, List<String> cookies) {
    }

    /**
     * Installs the Chromium build if it is missing - the one slow step of a
     * first unlock (a download of about 100 MB), done ahead of time so no
     * unlock has to wait for it.
     */
    private static Outcome prepare(Arguments arguments) throws Exception {
        CefAppBuilder builder = new CefAppBuilder();
        builder.setInstallDir(new File(arguments.bundle()));
        builder.setProgressHandler((state, percent) -> System.err.println("tinyunlock: " + state
                + (percent >= 0 ? " " + Math.round(percent) + " %" : "")));
        builder.install();
        return new Outcome(EXIT_OK, List.of());
    }

    private static Outcome run(Arguments arguments) throws Exception {
        Path profile = Path.of(arguments.profile());
        if (arguments.seedProfile() != null && ProfileSeed.seed(Path.of(arguments.seedProfile()), profile)) {
            System.err.println("tinyunlock: profile started from " + arguments.seedProfile());
        }

        URI page = URI.create(arguments.url());
        CefApp app = startCef(arguments);
        CefClient client = app.createClient();

        AtomicLong lastActivity = new AtomicLong(System.currentTimeMillis());
        AtomicReference<String> lastPage = new AtomicReference<>("");
        CountDownLatch firstLoad = new CountDownLatch(1);
        client.addLoadHandler(new CefLoadHandlerAdapter() {
            @Override
            public void onLoadStart(CefBrowser browser, CefFrame frame, CefRequest.TransitionType transitionType) {
                if (frame.isMain()) {
                    lastActivity.set(System.currentTimeMillis());
                }
            }

            @Override
            public void onLoadEnd(CefBrowser browser, CefFrame frame, int httpStatusCode) {
                if (!frame.isMain()) {
                    return;
                }
                lastActivity.set(System.currentTimeMillis());
                browser.getSource(source -> {
                    lastPage.set(source);
                    System.err.println("tinyunlock: loaded " + browser.getURL() + " (" + httpStatusCode + "): "
                            + PageCheck.describe(source));
                    firstLoad.countDown();
                });
                if (arguments.printText()) {
                    browser.getText(text -> System.err.println("tinyunlock: text " + text));
                }
            }
        });
        if (!arguments.visible()) {
            // A person looking at the page should see it whole.
            client.addRequestHandler(new ResourcePolicy());
        }

        AtomicBoolean windowClosed = new AtomicBoolean();
        CefBrowser[] browser = new CefBrowser[1];
        SwingUtilities.invokeAndWait(() -> {
            if (arguments.visible()) {
                browser[0] = openWindow(client, page, arguments.windowTitle(), windowClosed);
            } else {
                CefBrowserSettings settings = new CefBrowserSettings();
                settings.windowless_frame_rate = 1;
                HeadlessCefBrowser headless = new HeadlessCefBrowser(client, page.toString(), settings);
                headless.createImmediately();
                browser[0] = headless;
            }
        });

        long deadline = System.currentTimeMillis() + arguments.timeoutSeconds() * 1000L;
        if (!firstLoad.await(arguments.timeoutSeconds(), TimeUnit.SECONDS)) {
            throw new IllegalStateException("page never finished loading");
        }

        String site = site(page.getHost());
        while (System.currentTimeMillis() < deadline) {
            if (windowClosed.get()) {
                return new Outcome(EXIT_CLOSED, List.of());
            }
            boolean settled = !browser[0].isLoading()
                    && System.currentTimeMillis() - lastActivity.get() > QUIET_MILLIS;
            boolean captcha = PageCheck.isCaptcha(lastPage.get());
            if (settled && captcha && !arguments.visible()) {
                return new Outcome(EXIT_CAPTCHA, List.of());
            }
            if (settled && !captcha) {
                List<CefCookie> cookies = cookiesOf(site);
                if (hasAll(cookies, arguments.await())) {
                    return new Outcome(EXIT_OK, toLines(cookies));
                }
            }
            Thread.sleep(POLL_MILLIS);
        }
        throw new IllegalStateException("timed out; still missing " + missing(arguments.await(), cookiesOf(site))
                + (PageCheck.isCaptcha(lastPage.get()) ? " behind a CAPTCHA" : ""));
    }

    /** A plain window around a windowed browser - the person's side of a CAPTCHA. */
    private static CefBrowser openWindow(CefClient client, URI page, String title, AtomicBoolean closed) {
        CefBrowser browser = client.createBrowser(page.toString(), false, false);
        JFrame frame = new JFrame(title);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.getContentPane().add(browser.getUIComponent(), BorderLayout.CENTER);
        frame.setSize(1100, 820);
        frame.setLocationRelativeTo(null);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                closed.set(true);
            }
        });
        frame.setVisible(true);
        frame.toFront();
        return browser;
    }

    private static CefApp startCef(Arguments arguments) throws Exception {
        CefAppBuilder builder = new CefAppBuilder();
        builder.setInstallDir(new File(arguments.bundle()));
        builder.setProgressHandler((state, percent) -> { });
        builder.setAppHandler(new MavenCefAppHandlerAdapter() { });

        CefSettings settings = builder.getCefSettings();
        settings.windowless_rendering_enabled = !arguments.visible();
        settings.root_cache_path = arguments.profile();
        settings.cache_path = arguments.profile();
        settings.persist_session_cookies = true;
        settings.log_severity = CefSettings.LogSeverity.LOGSEVERITY_DISABLE;
        builder.addJcefArgs(
                "--mute-audio",
                "--disable-background-timer-throttling",
                "--disable-renderer-backgrounding");
        if (!arguments.visible()) {
            builder.addJcefArgs("--disable-gpu", "--disable-gpu-compositing");
        }

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
        return app[0];
    }

    /** Every cookie whose domain belongs to {@code site}. */
    private static List<CefCookie> cookiesOf(String site) throws InterruptedException {
        List<CefCookie> cookies = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        boolean started = CefCookieManager.getGlobalManager().visitAllCookies((cookie, count, total, delete) -> {
            String domain = cookie.domain.startsWith(".") ? cookie.domain.substring(1) : cookie.domain;
            if (domain.equals(site) || domain.endsWith("." + site)) {
                cookies.add(cookie);
            }
            if (count == total - 1) {
                done.countDown();
            }
            return true;
        });
        // Without any cookie the visitor is never called; waiting a poll is enough.
        if (started) {
            done.await(POLL_MILLIS, TimeUnit.MILLISECONDS);
        }
        return cookies;
    }

    private static boolean hasAll(List<CefCookie> cookies, Set<String> names) {
        Set<String> present = new HashSet<>();
        long now = System.currentTimeMillis();
        for (CefCookie cookie : cookies) {
            if (!cookie.hasExpires || cookie.expires == null || cookie.expires.getTime() > now) {
                present.add(cookie.name);
            }
        }
        return present.containsAll(names);
    }

    /**
     * Netscape cookie-file lines, which libcurl's {@code CURLOPT_COOKIELIST}
     * reads: domain, subdomains flag, path, secure, expiry, name, value;
     * HttpOnly cookies carry the {@code #HttpOnly_} prefix.
     */
    static List<String> toLines(List<CefCookie> cookies) {
        List<String> lines = new ArrayList<>();
        for (CefCookie cookie : cookies) {
            boolean subdomains = cookie.domain.startsWith(".");
            long expires = cookie.hasExpires && cookie.expires != null ? cookie.expires.getTime() / 1000 : 0;
            lines.add((cookie.httponly ? "#HttpOnly_" : "") + cookie.domain
                    + "\t" + (subdomains ? "TRUE" : "FALSE")
                    + "\t" + (cookie.path == null || cookie.path.isEmpty() ? "/" : cookie.path)
                    + "\t" + (cookie.secure ? "TRUE" : "FALSE")
                    + "\t" + expires
                    + "\t" + cookie.name
                    + "\t" + cookie.value);
        }
        return lines;
    }

    /** The registrable domain, as TinyFetch's headers compute it. */
    static String site(String host) {
        String[] labels = host.toLowerCase(Locale.ROOT).split("\\.");
        if (labels.length <= 2) {
            return String.join(".", labels);
        }
        boolean countrySecondLevel = labels[labels.length - 1].length() == 2
                && List.of("co", "com", "net", "org", "gov", "ac", "edu").contains(labels[labels.length - 2]);
        int keep = countrySecondLevel ? 3 : 2;
        return String.join(".", List.of(labels).subList(labels.length - keep, labels.length));
    }

    private static Set<String> missing(Set<String> awaited, List<CefCookie> cookies) {
        Set<String> missing = new LinkedHashSet<>(awaited);
        cookies.forEach(cookie -> missing.remove(cookie.name));
        return missing;
    }

    /** Parsed command line. */
    record Arguments(String url, String bundle, String profile, String seedProfile, Set<String> await,
            boolean visible, String windowTitle, int timeoutSeconds, boolean printText, boolean prepare) {

        static Arguments parse(String[] args) {
            String url = null;
            String bundle = null;
            String profile = null;
            String seedProfile = null;
            Set<String> await = new LinkedHashSet<>();
            boolean visible = false;
            String windowTitle = "";
            Integer timeout = null;
            boolean printText = false;
            boolean prepare = false;
            for (int i = 0; i + 1 < args.length; i += 2) {
                String value = args[i + 1];
                switch (args[i]) {
                    case "--url" -> url = value;
                    case "--bundle" -> bundle = value;
                    case "--profile" -> profile = value;
                    case "--seed-profile" -> seedProfile = value;
                    case "--await" -> {
                        for (String name : value.split(",")) {
                            if (!name.isBlank()) {
                                await.add(name.trim());
                            }
                        }
                    }
                    case "--visible" -> visible = Boolean.parseBoolean(value);
                    case "--window-title" -> windowTitle = value;
                    case "--timeout-seconds" -> timeout = Integer.parseInt(value);
                    case "--print-text" -> printText = Boolean.parseBoolean(value);
                    case "--prepare" -> prepare = Boolean.parseBoolean(value);
                    default -> throw new IllegalArgumentException("unknown argument " + args[i]);
                }
            }
            if (bundle == null || profile == null || (url == null && !prepare)) {
                throw new IllegalArgumentException("--url, --bundle and --profile are required");
            }
            int seconds = timeout != null ? timeout : visible ? 600 : 45;
            return new Arguments(url, bundle, profile, seedProfile, await, visible, windowTitle, seconds, printText,
                    prepare);
        }
    }
}
