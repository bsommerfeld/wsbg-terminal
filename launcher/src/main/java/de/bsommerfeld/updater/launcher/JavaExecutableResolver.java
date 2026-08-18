package de.bsommerfeld.updater.launcher;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Finds the executable to spawn the terminal with.
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
 *
 * <h3>The macOS name</h3>
 * The Dock and the ⌘-Tab switcher label a process by the <em>file name</em> of
 * its executable, so spawning a plain {@code bin/java} put a tile reading
 * "java" next to ours — same icon, different name. {@code -Xdock:name} does not
 * fix that: it only sets the LaunchServices display name (measured — the tile
 * kept saying "java"). The CI build therefore copies the runtime's launcher
 * binary to {@link #MAC_BRANDED_BINARY} beside {@code java}, and this resolver
 * prefers it. {@code java.home} is derived from the binary's path, not its
 * name, so the copy behaves identically.
 *
 * <p>Dev runs use a system JDK without that copy and fall back to {@code java}
 * — a dev tile keeps saying "java", which is nobody's problem.
 */
final class JavaExecutableResolver {

    /**
     * Name of the launcher-binary copy inside the bundled runtime's {@code bin/}.
     * Must stay in sync with the copy step in {@code .github/workflows/package.yml}
     * and with the app name jpackage is given.
     */
    private static final String MAC_BRANDED_BINARY = "WSBG Terminal";

    private JavaExecutableResolver() {
    }

    static String resolve() {
        return resolve(System.getProperty("os.name", ""),
                System.getProperty("java.home"),
                System.getenv("JAVA_HOME"));
    }

    /**
     * Test seam: the same resolution against an explicit OS name and the two
     * candidate homes, so every platform branch is verifiable off-platform.
     */
    static String resolve(String os, String javaHome, String envJavaHome) {
        String fromRuntime = javaBinary(os, javaHome);
        if (fromRuntime != null)
            return fromRuntime;

        String fromEnv = javaBinary(os, envJavaHome);
        if (fromEnv != null)
            return fromEnv;

        return "java";
    }

    /**
     * Resolves the launcher binary inside a JAVA_HOME-style directory: the
     * branded copy first on macOS, then the platform-correct default
     * ({@code java.exe} on Windows, {@code java} elsewhere). Returns
     * {@code null} if the directory is blank or no binary is executable.
     */
    private static String javaBinary(String os, String home) {
        if (home == null || home.isBlank())
            return null;

        String lower = os.toLowerCase();
        if (lower.contains("mac")) {
            Path branded = Path.of(home, "bin", MAC_BRANDED_BINARY);
            if (Files.isExecutable(branded))
                return branded.toString();
        }

        Path java = Path.of(home, "bin", lower.contains("win") ? "java.exe" : "java");
        return Files.isExecutable(java) ? java.toString() : null;
    }
}
