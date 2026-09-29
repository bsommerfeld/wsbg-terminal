package de.bsommerfeld.tinyfetch.api;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Where TinyFetch's browser comes from: TinyBrowser, embedded Chromium in its
 * own JVM, started by TinyFetch and stopped with it. Immutable.
 *
 * <pre>{@code
 * BrowserEngine engine = BrowserEngine.of(
 *         List.of(installation.resolve("engine").resolve("*")),   // TinyBrowser and its jars
 *         dataDirectory.resolve("chromium"),                       // installed there on first use
 *         dataDirectory.resolve("browser-profile"));               // cookies, cache, sessions
 * }</pre>
 *
 * <h3>The profile</h3>
 * The browser's memory - cookies, the HTTP cache, what sites stored - lives
 * in the profile directory and carries over from run to run, as a returning
 * visitor's does. One engine at a time per profile: Chromium locks it.
 */
public final class BrowserEngine {

    private final List<Path> classPath;
    private final Path chromium;
    private final Path profile;
    private final Path seedProfile;
    private final Path java;

    private BrowserEngine(List<Path> classPath, Path chromium, Path profile, Path seedProfile, Path java) {
        this.classPath = classPath;
        this.chromium = chromium;
        this.profile = profile;
        this.seedProfile = seedProfile;
        this.java = java;
    }

    /**
     * @param classPath TinyBrowser's classes and every jar it needs; a
     *                  {@code dir/*} entry stands for every jar in {@code dir}
     * @param chromium  where the Chromium build lives - installed there on
     *                  first use if missing (a download of about 100 MB)
     * @param profile   the browser profile, kept across runs
     */
    public static BrowserEngine of(List<Path> classPath, Path chromium, Path profile) {
        if (classPath.isEmpty()) {
            throw new IllegalArgumentException("empty class path");
        }
        return new BrowserEngine(List.copyOf(classPath), Objects.requireNonNull(chromium, "chromium"),
                Objects.requireNonNull(profile, "profile"), null, currentJava());
    }

    /**
     * A profile to start from while {@link #of profile} is still empty -
     * copied once, then the two live separate lives. A site that knows the
     * old browser knows this one from the first request.
     */
    public BrowserEngine seedProfile(Path seedProfile) {
        return new BrowserEngine(classPath, chromium, profile, seedProfile, java);
    }

    /**
     * The {@code java} to run the engine with; unless set, the one this JVM
     * runs on - in an installed application the bundled runtime, which must
     * keep its {@code bin/} (no jlink {@code --strip-native-commands}).
     */
    public BrowserEngine java(Path java) {
        return new BrowserEngine(classPath, chromium, profile, seedProfile, Objects.requireNonNull(java, "java"));
    }

    /** The command line that starts the engine, without the socket TinyFetch appends. */
    List<String> command() {
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
                "de.bsommerfeld.tinybrowser.BrowserMain",
                "--chromium", chromium.toString(),
                "--profile", profile.toString()));
        if (seedProfile != null) {
            command.addAll(List.of("--seed-profile", seedProfile.toString()));
        }
        return command;
    }

    private static Path currentJava() {
        String executable = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }
}
