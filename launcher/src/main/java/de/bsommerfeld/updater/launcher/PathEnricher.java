package de.bsommerfeld.updater.launcher;

/**
 * Extends the {@code PATH} environment variable of a {@link ProcessBuilder}
 * with directories that JPackage strips from the launcher's environment.
 *
 * <p>
 * When the application is installed via JPackage, the native launcher
 * inherits a minimal {@code PATH} ({@code /usr/bin:/bin}). User-installed
 * tools like {@code ollama} or {@code brew} in {@code /usr/local/bin} or
 * {@code /opt/homebrew/bin} become invisible. This utility appends common
 * install locations so that both the setup script and the application process
 * can discover them.
 *
 * <p>
 * On Windows, no enrichment is needed — {@code PATH} is preserved by
 * the native launcher.
 */
final class PathEnricher {

    private static final String[] EXTRA_PATHS = {
            "/usr/local/bin",
            "/opt/homebrew/bin",
            "/opt/homebrew/sbin"
    };

    private PathEnricher() {
    }

    /**
     * Appends common Unix install locations and the user's {@code ~/.local/bin}
     * to the {@code PATH} of the given process builder. No-op on Windows.
     */
    static void enrich(ProcessBuilder pb) {
        if (isWindows())
            return;

        String path = pb.environment().getOrDefault("PATH", "/usr/bin:/bin");
        StringBuilder enriched = new StringBuilder(path);

        for (String extra : EXTRA_PATHS) {
            enriched.append(':').append(extra);
        }
        enriched.append(':').append(System.getProperty("user.home")).append("/.local/bin");

        pb.environment().put("PATH", enriched.toString());
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
