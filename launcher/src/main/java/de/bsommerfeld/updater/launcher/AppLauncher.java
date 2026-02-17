package de.bsommerfeld.updater.launcher;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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

    private static final String[] JAVAFX_MODULES = {
            "javafx.controls", "javafx.fxml", "javafx.web",
            "javafx.graphics", "javafx.media"
    };

    /**
     * Internal packages that JavaFX must expose for undecorated-window and stage
     * access.
     */
    private static final String[] JAVAFX_OPENS = {
            "javafx.graphics/com.sun.javafx.tk",
            "javafx.graphics/javafx.stage",
            "javafx.graphics/com.sun.javafx.application"
    };

    private static final String APP_ICON_NAME = "app-icon.png";
    private static final String APP_NAME = "WSBG Terminal";

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

        Path logFile = appDirectory.resolve("logs/app.log");
        Files.createDirectories(logFile.getParent());

        String mainClass = resolveMainClass(libDir);
        String modulePath = buildJavaFxModulePath(libDir);
        String classpath = buildClasspath(libDir);

        List<String> command = buildCommand(mainClass, modulePath, classpath, extraArgs);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(appDirectory.toFile());
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));

        PathEnricher.enrich(pb);

        pb.start();
    }

    /**
     * Assembles the full JVM command line. Separated from {@link #launch} to
     * keep the orchestration method focused on I/O and process lifecycle.
     */
    private List<String> buildCommand(String mainClass, String modulePath,
            String classpath, String[] extraArgs) {
        List<String> cmd = new ArrayList<>();
        cmd.add(resolveJava());

        addDockIconFlags(cmd);

        cmd.add("--enable-preview");

        cmd.add("--module-path");
        cmd.add(modulePath);
        cmd.add("--add-modules");
        cmd.add(String.join(",", JAVAFX_MODULES));

        cmd.add("-cp");
        cmd.add(classpath);

        // JavaFX modules need to read the unnamed module (classpath) to find
        // the Application subclass. Without this, JavaFX's internal launcher
        // fails with "Missing JavaFX application class".
        for (String mod : JAVAFX_MODULES) {
            cmd.add("--add-reads");
            cmd.add(mod + "=ALL-UNNAMED");
        }

        for (String open : JAVAFX_OPENS) {
            cmd.add("--add-opens");
            cmd.add(open + "=ALL-UNNAMED");
        }

        cmd.add("--enable-native-access=" + String.join(",", JAVAFX_MODULES));

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
     * {@code lib/}. Non-JavaFX JARs are checked for a {@code Main-Class}
     * attribute — the first match wins.
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
                    .filter(p -> !p.getFileName().toString().startsWith("javafx-"))
                    .toList();

            for (Path jar : candidates) {
                String mainClass = readMainClassFromManifest(jar);
                if (mainClass != null)
                    return mainClass;
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
    // JavaFX Module-Path & Classpath
    // =====================================================================

    /**
     * Collects JavaFX JARs for the current platform onto the module-path.
     * Includes base API JARs (no classifier) and the exact platform-specific
     * JARs. Other platforms' native JARs are excluded to avoid module conflicts.
     */
    private String buildJavaFxModulePath(Path libDir) throws IOException {
        String classifier = detectPlatformClassifier();

        try (Stream<Path> jars = Files.list(libDir)) {
            return jars.filter(p -> {
                String name = p.getFileName().toString();
                if (!name.startsWith("javafx-"))
                    return false;

                boolean hasPlatformClassifier = name.contains("-mac")
                        || name.contains("-win") || name.contains("-linux");
                if (hasPlatformClassifier) {
                    // Exact suffix match prevents "mac" from also matching "mac-aarch64".
                    // Without this, both x86_64 and arm64 JARs land on the module-path
                    // and JavaFX caches whichever native libs it finds first.
                    return name.endsWith("-" + classifier + ".jar");
                }
                return true;
            })
                    .map(Path::toString)
                    .reduce((a, b) -> a + File.pathSeparator + b)
                    .orElse("");
        }
    }

    /**
     * Builds a classpath from all JARs in {@code lib/}. JavaFX JARs appear
     * on both module-path and classpath — harmless duplication since the module
     * system takes precedence for named modules.
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

    /**
     * Detects the exact JavaFX classifier for this OS + architecture combo.
     * Classifiers: {@code mac}, {@code mac-aarch64}, {@code win},
     * {@code linux}, {@code linux-aarch64}.
     */
    private String detectPlatformClassifier() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        boolean isArm = arch.contains("aarch64") || arch.contains("arm");

        if (os.contains("mac"))
            return isArm ? "mac-aarch64" : "mac";
        if (os.contains("win"))
            return "win";
        return isArm ? "linux-aarch64" : "linux";
    }

    private boolean isMacOS() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    /**
     * Finds the {@code java} executable. Prefers {@code JAVA_HOME} if set
     * and the binary is executable, otherwise falls back to {@code PATH}.
     */
    private String resolveJava() {
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null) {
            Path java = Path.of(javaHome, "bin", "java");
            if (Files.isExecutable(java))
                return java.toString();
        }
        return "java";
    }
}
