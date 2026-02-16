package de.bsommerfeld.tinyupdate.launcher;

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
 * <p>Constructs a classpath from all JARs in {@code lib/}, launches the configured
 * main class, and redirects output to {@code logs/app.log}. The launcher exits
 * after the application process is started.
 */
final class AppLauncher {

    private static final String MAIN_CLASS = "de.bsommerfeld.wsbg.terminal.ui.WsbgTerminalApp";

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

        String classpath = buildClasspath(libDir);
        Path logFile = appDirectory.resolve("logs/app.log");
        Files.createDirectories(logFile.getParent());

        List<String> command = new ArrayList<>();
        command.add(resolveJava());
        command.add("--enable-preview");
        command.add("-cp");
        command.add(classpath);

        addModuleFlags(command);

        command.add(MAIN_CLASS);
        command.addAll(List.of(extraArgs));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(appDirectory.toFile());
        pb.redirectOutput(logFile.toFile());
        pb.redirectErrorStream(true);

        // Detach from launcher — the app lives on its own
        pb.inheritIO();
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()));
        pb.redirectError(ProcessBuilder.Redirect.appendTo(logFile.toFile()));

        pb.start();
    }

    /**
     * Builds a platform-specific classpath string from all JARs in lib/.
     */
    private String buildClasspath(Path libDir) throws IOException {
        try (Stream<Path> jars = Files.list(libDir)) {
            String cp = jars.filter(p -> p.toString().endsWith(".jar"))
                    .map(Path::toString)
                    .reduce((a, b) -> a + File.pathSeparator + b)
                    .orElseThrow(() -> new IOException("No JARs found in lib/"));
            return cp;
        }
    }

    /**
     * JavaFX requires module-system flags when running from the classpath.
     * These open the necessary internal APIs without requiring a full modular build.
     */
    private void addModuleFlags(List<String> command) {
        String[] modules = {
                "javafx.controls", "javafx.fxml", "javafx.web", "javafx.graphics"
        };
        for (String mod : modules) {
            command.add("--add-modules");
            command.add(mod);
        }
        command.add("--add-opens");
        command.add("javafx.graphics/com.sun.javafx.tk=ALL-UNNAMED");
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
