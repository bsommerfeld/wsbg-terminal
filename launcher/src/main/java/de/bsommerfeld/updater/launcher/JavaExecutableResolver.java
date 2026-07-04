package de.bsommerfeld.updater.launcher;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Finds the {@code java} executable to spawn the terminal with.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>{@code java.home} of the launcher's own JVM — under jpackage this is the
 *       runtime bundled with the installer, so the terminal starts even on
 *       machines with no system JDK on the {@code PATH}. This is the production
 *       path; relying on a bare {@code "java"} previously failed with
 *       {@code CreateProcess error=2} on clean installs.</li>
 *   <li>{@code JAVA_HOME} environment variable.</li>
 *   <li>bare {@code java}, resolved via {@code PATH}.</li>
 * </ol>
 */
final class JavaExecutableResolver {

    private JavaExecutableResolver() {
    }

    static String resolve() {
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
    private static String javaBinary(String home) {
        if (home == null || home.isBlank())
            return null;
        String exe = System.getProperty("os.name", "").toLowerCase().contains("win")
                ? "java.exe" : "java";
        Path java = Path.of(home, "bin", exe);
        return Files.isExecutable(java) ? java.toString() : null;
    }
}
