package de.bsommerfeld.updater.launcher;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Spawns the main application in a separate JVM process with the correct
 * module-path, classpath, and JVM flags.
 *
 * <h3>Main-class resolution</h3>
 * The target class is discovered dynamically from the {@code Main-Class}
 * attribute in the first non-JavaFX JAR's manifest under {@code lib/}. This
 * eliminates a hardcoded coupling between the launcher and the application
 * module — if the entry point is renamed, a rebuild of the terminal module
 * is sufficient; the launcher adapts automatically.
 *
 * <p>
 * If no manifest entry is found (corrupted deployment), the launcher
 * aborts with a descriptive error rather than silently failing.
 *
 * <h3>Module-path vs classpath</h3>
 * JavaFX JARs are placed on the module-path (required for {@code --add-modules}
 * to discover them as named modules). All other JARs go on the classpath.
 * Both are constructed dynamically from the contents of {@code lib/}.
 *
 * @see EnvironmentSetup
 * @see LauncherMain
 */
final class AppLauncher {

    /**
     * JDK-internal packages JCEF reaches into for native AWT integration:
     * <ul>
     *   <li>{@code sun.awt}            — common AWT internals</li>
     *   <li>{@code sun.lwawt}          — lightweight AWT base class</li>
     *   <li>{@code sun.lwawt.macosx}   — Cocoa peer (no-op on Win/Linux)</li>
     *   <li>{@code java.awt.peer}      — heavyweight peer access</li>
     * </ul>
     * Without these {@code --add-opens}, the Chromium-NSWindow reparent
     * inside {@code CefBrowserWindowMac.getWindowHandle()} fails with
     * {@code IllegalAccessError} on macOS + JDK 17+. The Linux/Windows
     * paths don't strictly need {@code sun.lwawt.macosx} but opening a
     * non-existent module is silently ignored, so the same flag set is
     * safe on every platform.
     */
    private static final String[] JCEF_OPENS = {
            "java.desktop/sun.awt",
            "java.desktop/sun.lwawt",
            "java.desktop/sun.lwawt.macosx",
            "java.desktop/java.awt.peer"
    };

    private static final String APP_ICON_NAME = "app-icon.png";
    private static final String APP_NAME = "WSBG Terminal";
    private static final int MAX_LOG_FILES = 10;

    private final Path appDirectory;

    AppLauncher(Path appDirectory) {
        this.appDirectory = appDirectory;
    }

    /**
     * Launches the application in a detached process. Returns immediately
     * after the process is started — does not wait for exit.
     *
     * @param extraArgs forwarded to the application's {@code main(String[])} as-is
     * @throws IOException if {@code lib/} is missing, no JARs are found, or
     *                     the main class cannot be resolved
     */
    void launch(String... extraArgs) throws IOException {
        Path libDir = appDirectory.resolve("lib");
        if (!Files.isDirectory(libDir)) {
            throw new IOException("lib/ directory not found — update may have failed");
        }

        Path logDir = appDirectory.resolve("logs/terminal");
        Files.createDirectories(logDir);
        archiveLatestLog(logDir);
        purgeOldLogs(logDir);

        Path logFile = logDir.resolve("latest.log");

        String mainClass = resolveMainClass(libDir);
        String classpath = buildClasspath(libDir);

        List<String> command = buildCommand(mainClass, classpath, extraArgs);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(appDirectory.toFile());
        pb.redirectOutput(ProcessBuilder.Redirect.to(logFile.toFile()));
        pb.redirectError(ProcessBuilder.Redirect.to(logFile.toFile()));

        PathEnricher.enrich(pb);

        // Hand the terminal our own executable path so its in-app "update now"
        // button can relaunch us (cleanly close → restart the launcher, which
        // applies the update). Absent in dev runs (run.sh starts the terminal
        // directly) — the terminal hides the update button when this is unset.
        ProcessHandle.current().info().command().ifPresent(
                exe -> pb.environment().put("WSBG_LAUNCHER_EXECUTABLE", exe));

        pb.start();
    }

    /**
     * Archives the previous session's {@code latest.log} by renaming it to a
     * timestamp derived from its last-modified time. Called before each launch
     * so that {@code latest.log} always represents the current session alone.
     */
    private void archiveLatestLog(Path logDir) {
        Path latest = logDir.resolve("latest.log");
        if (!Files.exists(latest)) return;
        try {
            String timestamp = Files.getLastModifiedTime(latest).toInstant()
                    .atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
            Files.move(latest, logDir.resolve(timestamp + ".log"));
        } catch (IOException ignored) {
        }
    }

    /**
     * Deletes the oldest archived {@code .log} files in the terminal log
     * directory when the count exceeds {@link #MAX_LOG_FILES}. {@code latest.log}
     * is excluded — it is the active session, not an archive.
     */
    private void purgeOldLogs(Path logDir) {
        try (Stream<Path> files = Files.list(logDir)) {
            var logs = files
                    .filter(p -> p.toString().endsWith(".log"))
                    .filter(p -> !p.getFileName().toString().equals("latest.log"))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .toList();

            if (logs.size() >= MAX_LOG_FILES) {
                for (int i = 0; i < logs.size() - MAX_LOG_FILES + 1; i++) {
                    Files.deleteIfExists(logs.get(i));
                }
            }
        } catch (IOException ignored) {
        }
    }

    /**
     * Assembles the full JVM command line. Separated from {@link #launch} to
     * keep the orchestration method focused on I/O and process lifecycle.
     */
    private List<String> buildCommand(String mainClass, String classpath, String[] extraArgs) {
        List<String> cmd = new ArrayList<>();
        cmd.add(resolveJava());

        addDockIconFlags(cmd);

        // JCEF native bridge needs unrestricted access to OS APIs (Cocoa,
        // GDI, etc.). Required for the embedded Chromium browser to
        // initialise; without it, the JNI helpers log a warning and the
        // browser refuses to load on JDK 22+.
        cmd.add("--enable-native-access=ALL-UNNAMED");
        for (String open : JCEF_OPENS) {
            cmd.add("--add-opens");
            cmd.add(open + "=ALL-UNNAMED");
        }

        cmd.add("-cp");
        cmd.add(classpath);

        cmd.add(mainClass);
        cmd.addAll(List.of(extraArgs));

        return cmd;
    }

    /**
     * Adds macOS dock icon/name flags. Silently skipped on other platforms
     * or if the icon file is absent.
     */
    private void addDockIconFlags(List<String> cmd) {
        if (!isMacOS())
            return;

        Path icon = appDirectory.resolve("lib/" + APP_ICON_NAME);
        if (!Files.exists(icon))
            icon = appDirectory.resolve("images/" + APP_ICON_NAME);
        if (!Files.exists(icon))
            return;

        cmd.add("-Xdock:name=" + APP_NAME);
        cmd.add("-Xdock:icon=" + icon);
    }

    // =====================================================================
    // Main-Class Discovery
    // =====================================================================

    /**
     * Discovers the application entry point by scanning JAR manifests in
     * {@code lib/}. The terminal JAR is preferred over third-party JARs
     * that may also declare a {@code Main-Class}.
     *
     * <p>
     * This keeps the launcher decoupled from the terminal module's class
     * name. As long as the terminal JAR declares its entry point in the
     * manifest (which Maven's jar-plugin does automatically via
     * {@code <mainClass>}), the launcher will find it.
     *
     * @throws IOException if no JAR with a Main-Class manifest entry is found
     */
    private String resolveMainClass(Path libDir) throws IOException {
        try (Stream<Path> jars = Files.list(libDir)) {
            List<Path> candidates = jars
                    .filter(p -> p.toString().endsWith(".jar"))
                    .toList();

            // Prefer our terminal jar over third-party jars that might have a Main-Class
            // (like opennlp)
            List<Path> orderedCandidates = new ArrayList<>();
            for (Path jar : candidates) {
                if (jar.getFileName().toString().startsWith("terminal-")) {
                    orderedCandidates.add(0, jar);
                } else {
                    orderedCandidates.add(jar);
                }
            }

            for (Path jar : orderedCandidates) {
                String mainClass = readMainClassFromManifest(jar);
                if (mainClass != null) {
                    // Ignore known third-party CLI entry points
                    if (!mainClass.contains(".wsbg.")) {
                        continue;
                    }
                    return mainClass;
                }
            }
        }

        throw new IOException("CORRUPT INSTALLATION: No JAR in lib/ declares a Main-Class. "
                + "The update may have delivered incomplete artifacts.");
    }

    /**
     * Reads the {@code Main-Class} attribute from a JAR's manifest. Returns
     * {@code null} on failure.
     */
    private String readMainClassFromManifest(Path jar) {
        try (JarFile jf = new JarFile(jar.toFile())) {
            Manifest manifest = jf.getManifest();
            if (manifest == null)
                return null;
            return manifest.getMainAttributes().getValue("Main-Class");
        } catch (IOException e) {
            return null;
        }
    }

    // =====================================================================
    // Classpath
    // =====================================================================

    /**
     * Builds a classpath from every JAR in {@code lib/}. The terminal
     * runs entirely off the unnamed module — no module-path setup
     * required, no platform-specific filtering. Stale JavaFX JARs from
     * pre-WebView installs are silently included but unused; the
     * updater drops them on the next sync anyway.
     */
    private String buildClasspath(Path libDir) throws IOException {
        try (Stream<Path> jars = Files.list(libDir)) {
            return jars.filter(p -> p.toString().endsWith(".jar"))
                    .map(Path::toString)
                    .reduce((a, b) -> a + File.pathSeparator + b)
                    .orElseThrow(() -> new IOException("No JARs found in lib/"));
        }
    }

    // =====================================================================
    // Platform Detection
    // =====================================================================

    private boolean isMacOS() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    /**
     * Finds the {@code java} executable to spawn the terminal with.
     *
     * <p>Resolution order:
     * <ol>
     *   <li>{@code java.home} of the launcher's own JVM — under jpackage this
     *       is the runtime bundled with the installer, so the terminal starts
     *       even on machines with no system JDK on the {@code PATH}. This is
     *       the production path; relying on a bare {@code "java"} previously
     *       failed with {@code CreateProcess error=2} on clean installs.</li>
     *   <li>{@code JAVA_HOME} environment variable.</li>
     *   <li>bare {@code java}, resolved via {@code PATH}.</li>
     * </ol>
     */
    private String resolveJava() {
        String fromRuntime = javaBinary(System.getProperty("java.home"));
        if (fromRuntime != null)
            return fromRuntime;

        String fromEnv = javaBinary(System.getenv("JAVA_HOME"));
        if (fromEnv != null)
            return fromEnv;

        return "java";
    }

    /**
     * Resolves the platform-correct {@code java} launcher inside a JAVA_HOME-style
     * directory ({@code java.exe} on Windows, {@code java} elsewhere). Returns
     * {@code null} if the directory is blank or the binary isn't executable.
     */
    private String javaBinary(String home) {
        if (home == null || home.isBlank())
            return null;
        String exe = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe" : "java";
        Path java = Path.of(home, "bin", exe);
        return Files.isExecutable(java) ? java.toString() : null;
    }
}
