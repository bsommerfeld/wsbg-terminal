package de.bsommerfeld.tinyfetch.api;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * A {@link SessionUnlocker} that runs the engine as a child process - in
 * practice TinyUnlock, embedded Chromium in its own short-lived JVM, so no
 * browser engine ever lives in the application's process.
 *
 * <h3>The protocol</h3>
 * The engine command is called with {@code --url <page>} and, when cookies
 * are awaited, {@code --await <a,b>}; for a person additionally
 * {@code --visible true --window-title <title>}. It prints the site's cookies
 * as Netscape cookie-file lines on stdout and exits with {@code 0} (done),
 * {@code 3} (the page is a CAPTCHA), {@code 4} (the person closed the window),
 * anything else on failure - its stderr then says why.
 *
 * <h3>First use</h3>
 * The engine installs its Chromium build on first use - a download of about
 * 100 MB that would eat into an unlock's time limit. {@link #prepare} does it
 * ahead of time; an application calls it once in the background at start.
 *
 * <h3>CAPTCHA</h3>
 * Exit {@code 3} goes to the {@link CaptchaSolver}; without one, or when it
 * returns nothing, the unlock fails with {@link CaptchaRequiredException}.
 *
 * <pre>{@code
 * ProcessUnlocker unlocker = ProcessUnlocker.builder(ProcessUnlocker.tinyUnlockCommand(
 *                 javaExecutable, engineClassPath, bundleDirectory, profileDirectory, oldTerminalProfile))
 *         .captchaSolver(challenge -> askAndOpenWindow(challenge))
 *         .build();
 *
 * TinyFetch fetch = TinyFetch.builder()
 *         .browser(unlocker.browser())
 *         .unlocker(unlocker)
 *         .build();
 * }</pre>
 */
public final class ProcessUnlocker implements SessionUnlocker {

    static final int EXIT_OK = 0;
    static final int EXIT_CAPTCHA = 3;
    static final int EXIT_CLOSED = 4;

    /** stderr lines kept for the failure message. */
    private static final int STDERR_TAIL = 6;

    private final List<String> command;
    private final Browser browser;
    private final CaptchaSolver captchaSolver;
    private final Duration timeout;
    private final Duration windowTimeout;

    private ProcessUnlocker(Builder builder) {
        this.command = List.copyOf(builder.command);
        this.browser = builder.browser;
        this.captchaSolver = builder.captchaSolver;
        this.timeout = builder.timeout;
        this.windowTimeout = builder.windowTimeout;
    }

    /**
     * @param engineCommand the engine's command line without {@code --url}
     *                      and {@code --await} - see {@link #tinyUnlockCommand}
     */
    public static Builder builder(List<String> engineCommand) {
        return new Builder(engineCommand);
    }

    /**
     * The {@code java} executable of the runtime this JVM runs on - in an
     * installed application the bundled runtime, which must keep its
     * {@code bin/} (no jlink {@code --strip-native-commands}).
     */
    public static Path currentJava() {
        String executable = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }

    /**
     * The command line that starts TinyUnlock in its own JVM.
     *
     * @param java        the {@code java} executable (JDK 25 or newer)
     * @param classPath   TinyUnlock's classes and every jar it needs
     * @param bundle      where the Chromium build lives - installed there on first use if missing
     * @param profile     the engine's own browser profile, kept across runs
     * @param seedProfile a profile to start {@code profile} from while it is empty
     *                    (the terminal's old embedded browser); {@code null} for none
     */
    public static List<String> tinyUnlockCommand(Path java, List<Path> classPath, Path bundle, Path profile,
            Path seedProfile) {
        List<String> command = new ArrayList<>(List.of(
                java.toString(),
                "--enable-native-access=ALL-UNNAMED",
                // JCEF reaches into the AWT internals of macOS.
                "--add-opens=java.desktop/sun.awt=ALL-UNNAMED",
                "--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED",
                "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.peer=ALL-UNNAMED",
                "-cp", classPath.stream().map(Path::toString)
                        .collect(Collectors.joining(System.getProperty("path.separator"))),
                "de.bsommerfeld.tinyunlock.UnlockMain",
                "--bundle", bundle.toString(),
                "--profile", profile.toString()));
        if (seedProfile != null) {
            command.addAll(List.of("--seed-profile", seedProfile.toString()));
        }
        return command;
    }

    @Override
    public Browser browser() {
        return browser;
    }

    /**
     * Makes sure the engine is ready to unlock without delay - for TinyUnlock,
     * that its Chromium build is installed. Quick when it is.
     *
     * @throws FetchException the engine could not get ready within {@code limit}
     */
    public void prepare(Duration limit) throws FetchException, InterruptedException {
        Result result = run(List.of("--prepare", "true"), limit, "preparing the engine");
        if (result.exitCode() != EXIT_OK) {
            throw new FetchException("unlock engine could not get ready (exit " + result.exitCode() + "): "
                    + result.stderr());
        }
    }

    @Override
    public List<String> unlock(URI page, Set<String> awaitCookies) throws FetchException, InterruptedException {
        Result result = run(page, awaitCookies, List.of(), timeout);
        return switch (result.exitCode()) {
            case EXIT_OK -> result.cookies();
            case EXIT_CAPTCHA -> {
                Optional<List<String>> solved = captchaSolver.solveCaptcha(
                        new CaptchaChallenge(page, awaitCookies, this::openWindow));
                yield solved.orElseThrow(() -> new CaptchaRequiredException(page.getHost(),
                        "the page is a CAPTCHA and no one solved it"));
            }
            default -> throw failure(page, result);
        };
    }

    /** The person's side: the same engine and profile, in a window. */
    private List<String> openWindow(URI page, Set<String> awaitCookies, String title)
            throws FetchException, InterruptedException {
        Result result = run(page, awaitCookies, List.of("--visible", "true", "--window-title", title), windowTimeout);
        return switch (result.exitCode()) {
            case EXIT_OK -> result.cookies();
            case EXIT_CLOSED -> throw new CaptchaRequiredException(page.getHost(), "the window was closed unsolved");
            default -> throw failure(page, result);
        };
    }

    private record Result(int exitCode, List<String> cookies, String stderr) {
    }

    private Result run(URI page, Set<String> awaitCookies, List<String> extra, Duration limit)
            throws FetchException, InterruptedException {
        List<String> arguments = new ArrayList<>(List.of("--url", page.toString()));
        if (!awaitCookies.isEmpty()) {
            arguments.addAll(List.of("--await", String.join(",", awaitCookies)));
        }
        arguments.addAll(extra);
        return run(arguments, limit, page.toString());
    }

    private Result run(List<String> arguments, Duration limit, String what)
            throws FetchException, InterruptedException {
        List<String> line = new ArrayList<>(command);
        line.addAll(arguments);

        Process process;
        try {
            process = new ProcessBuilder(line).start();
        } catch (IOException e) {
            throw new FetchException("cannot start the unlock engine: " + e.getMessage(), e);
        }
        try {
            // Both streams are drained concurrently so neither pipe can fill up and stall the engine.
            Deque<String> stderr = new ArrayDeque<>();
            Thread stderrReader = Thread.ofVirtual().start(() -> tail(process.getErrorStream(), stderr));
            List<String> cookies = new ArrayList<>();
            Thread stdoutReader = Thread.ofVirtual().start(() -> collect(process.getInputStream(), cookies));

            if (!process.waitFor(limit.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new FetchException("unlock engine gave no answer within " + limit + " for " + what);
            }
            stdoutReader.join();
            stderrReader.join();
            synchronized (stderr) {
                return new Result(process.exitValue(), List.copyOf(cookies), String.join(" | ", stderr));
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static void collect(InputStream stream, List<String> lines) {
        try {
            new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(entry -> !entry.isBlank())
                    .forEach(lines::add);
        } catch (IOException ignored) {
            // the exit code tells what happened
        }
    }

    private static void tail(InputStream stream, Deque<String> tail) {
        try {
            for (String entry : new String(stream.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
                synchronized (tail) {
                    tail.addLast(entry);
                    if (tail.size() > STDERR_TAIL) {
                        tail.removeFirst();
                    }
                }
            }
        } catch (IOException ignored) {
            // nothing more to say
        }
    }

    private static FetchException failure(URI page, Result result) {
        return new FetchException("unlock engine failed for " + page + " (exit " + result.exitCode() + "): "
                + result.stderr());
    }

    /** Configures a {@link ProcessUnlocker}. */
    public static final class Builder {

        private final List<String> command;
        private Browser browser = Browser.CHROMIUM_EMBEDDED;
        private CaptchaSolver captchaSolver = CaptchaSolver.NOBODY;
        private Duration timeout = Duration.ofSeconds(90);
        private Duration windowTimeout = Duration.ofMinutes(15);

        private Builder(List<String> command) {
            if (command.isEmpty()) {
                throw new IllegalArgumentException("empty engine command");
            }
            this.command = command;
        }

        /** The browser the engine is; {@link Browser#CHROMIUM_EMBEDDED} (TinyUnlock) unless set. */
        public Builder browser(Browser browser) {
            this.browser = Objects.requireNonNull(browser, "browser");
            return this;
        }

        /** Who solves a CAPTCHA; {@link CaptchaSolver#NOBODY} unless set. */
        public Builder captchaSolver(CaptchaSolver captchaSolver) {
            this.captchaSolver = Objects.requireNonNull(captchaSolver, "captchaSolver");
            return this;
        }

        /** Longest a headless unlock may take, engine start included; 90 s unless set. */
        public Builder timeout(Duration timeout) {
            this.timeout = Objects.requireNonNull(timeout, "timeout");
            return this;
        }

        /** Longest a person's window may stay open; 15 minutes unless set. */
        public Builder windowTimeout(Duration windowTimeout) {
            this.windowTimeout = Objects.requireNonNull(windowTimeout, "windowTimeout");
            return this;
        }

        public ProcessUnlocker build() {
            return new ProcessUnlocker(this);
        }
    }
}
