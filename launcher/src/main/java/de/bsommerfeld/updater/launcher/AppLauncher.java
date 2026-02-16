package de.bsommerfeld.updater.launcher;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Spawns the main application in a separate JVM process.
 *
 * <p>Constructs module-path and classpath from JARs in {@code lib/}, launches the
 * configured main class, and redirects output to {@code logs/app.log}. The launcher
 * exits after the application process is started.
 */
final class AppLauncher {

    private static final String MAIN_CLASS = "de.bsommerfeld.wsbg.terminal.ui.AppMain";

    private final Path appDirectory;

    AppLauncher(Path appDirectory) {
        this.appDirectory = appDirectory;
    }

    /**
     * Launches the application. Does not block — returns immediately after the process is started.
     */
    void launch(String... extraArgs) throws IOException {
        Path libDir = appDirectory.resolve("lib");
        if (!Files.isDirectory(libDir)) {
            throw new IOException("lib/ directory not found — update may have failed");
        }

        Path logFile = appDirectory.resolve("logs/app.log");
        Files.createDirectories(logFile.getParent());

        // JavaFX JARs go on the module-path, everything else on the classpath.
        // --add-modules only works when JavaFX is discoverable as modules, which
        // requires module-path — classpath-only would cause "Module not found".
        String modulePath = buildJavaFxModulePath(libDir);
        String classpath = buildClasspath(libDir);

        String[] jfxModules = {"javafx.controls", "javafx.fxml", "javafx.web", "javafx.graphics", "javafx.media"};

        List<String> command = new ArrayList<>();
        command.add(resolveJava());

        // macOS dock icon — ignored on other platforms
        if (System.getProperty("os.name", "").toLowerCase().contains("mac")) {
            Path icon = appDirectory.resolve("lib/app-icon.png");
            if (!Files.exists(icon)) icon = appDirectory.resolve("images/app-icon.png");
            if (Files.exists(icon)) {
                command.add("-Xdock:name=WSBG Terminal");
                command.add("-Xdock:icon=" + icon);
            }
        }

        command.add("--enable-preview");
        command.add("--module-path");
        command.add(modulePath);
        command.add("--add-modules");
        command.add(String.join(",", jfxModules));
        command.add("-cp");
        command.add(classpath);

        // JavaFX modules need to read the unnamed module (classpath) to find the
        // Application subclass. Without this, JavaFX's internal launcher fails with
        // "Missing JavaFX application class".
        for (String mod : jfxModules) {
            command.add("--add-reads");
            command.add(mod + "=ALL-UNNAMED");
        }

        command.add("--add-opens");
        command.add("javafx.graphics/com.sun.javafx.tk=ALL-UNNAMED");
        command.add("--add-opens");
        command.add("javafx.graphics/javafx.stage=ALL-UNNAMED");
        command.add("--add-opens");
        command.add("javafx.graphics/com.sun.javafx.application=ALL-UNNAMED");
        command.add("--enable-native-access=javafx.graphics,javafx.media,javafx.web");
        command.add(MAIN_CLASS);
        command.addAll(List.of(extraArgs));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(appDirectory.toFile());
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));

        // Same PATH extension as EnvironmentSetup — subprocess needs access
        // to user-installed tools (e.g. ollama) even when launched via JPackage.
        String os = System.getProperty("os.name", "").toLowerCase();
        if (!os.contains("win")) {
            String path = pb.environment().getOrDefault("PATH", "/usr/bin:/bin");
            pb.environment().put("PATH", path
                    + ":/usr/local/bin:/opt/homebrew/bin:/opt/homebrew/sbin"
                    + ":" + System.getProperty("user.home") + "/.local/bin");
        }

        pb.start();
    }

    /**
     * Collects JavaFX JARs for the current platform onto the module-path.
     * Includes base API JARs (no classifier) and the exact platform-specific
     * JARs. Other platforms are excluded to avoid module conflicts.
     */
    private String buildJavaFxModulePath(Path libDir) throws IOException {
        String classifier = detectClassifier();

        try (Stream<Path> jars = Files.list(libDir)) {
            return jars.filter(p -> {
                        String name = p.getFileName().toString();
                        if (!name.startsWith("javafx-")) return false;

                        boolean hasPlatformClassifier = name.contains("-mac") || name.contains("-win") || name.contains("-linux");
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
     * Detects the exact JavaFX classifier for this OS + architecture combo.
     * Classifiers: mac, mac-aarch64, win, linux, linux-aarch64.
     */
    private String detectClassifier() {
        String os = System.getProperty("os.name", "").toLowerCase();
        String arch = System.getProperty("os.arch", "").toLowerCase();
        boolean isArm = arch.contains("aarch64") || arch.contains("arm");

        if (os.contains("mac")) return isArm ? "mac-aarch64" : "mac";
        if (os.contains("win")) return "win";
        return isArm ? "linux-aarch64" : "linux";
    }

    /**
     * Builds a classpath from all JARs in lib/ (including JavaFX — harmless duplication).
     */
    private String buildClasspath(Path libDir) throws IOException {
        try (Stream<Path> jars = Files.list(libDir)) {
            return jars.filter(p -> p.toString().endsWith(".jar"))
                    .map(Path::toString)
                    .reduce((a, b) -> a + File.pathSeparator + b)
                    .orElseThrow(() -> new IOException("No JARs found in lib/"));
        }
    }

    /**
     * Finds the {@code java} executable.
     * Prefers JAVA_HOME if set, otherwise falls back to PATH.
     */
    private String resolveJava() {
        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null) {
            Path java = Path.of(javaHome, "bin", "java");
            if (Files.isExecutable(java)) return java.toString();
        }
        return "java";
    }
}
