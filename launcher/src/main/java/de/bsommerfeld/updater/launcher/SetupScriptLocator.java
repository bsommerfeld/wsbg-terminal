package de.bsommerfeld.updater.launcher;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Locates the platform-appropriate setup script and builds the process that
 * runs it. For local development the script directory can be overridden via the
 * {@code wsbg.setup.script.dir} system property or the
 * {@code WSBG_SETUP_SCRIPT_DIR} environment variable (property takes
 * precedence), so dev launches test the repo's {@code .script/} instead of the
 * release-cached {@code bin/} copies — but only when the override actually
 * contains the script (existence gate).
 */
final class SetupScriptLocator {

    /** Dev-only override for the setup-script directory. */
    static final String SCRIPT_DIR_PROPERTY = "wsbg.setup.script.dir";
    static final String SCRIPT_DIR_ENV = "WSBG_SETUP_SCRIPT_DIR";

    private final Path appDirectory;

    SetupScriptLocator(Path appDirectory) {
        this.appDirectory = appDirectory;
    }

    /**
     * Resolves the setup script path. Honors the dev override (system property
     * then env var) only if the candidate exists; otherwise falls back to
     * {@code <appDir>/bin/setup.(sh|ps1)}.
     */
    Path resolveScript() {
        String name = isWindows() ? "setup.ps1" : "setup.sh";

        // Dev override: point at the repo's .script/ so local launcher runs
        // always test the checked-in scripts, not the release-cached bin/
        // copies (which the update phase would otherwise restore first).
        String override = System.getProperty(SCRIPT_DIR_PROPERTY, System.getenv(SCRIPT_DIR_ENV));
        if (override != null && !override.isBlank()) {
            Path candidate = Path.of(override).resolve(name);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }

        return appDirectory.resolve("bin").resolve(name);
    }

    /**
     * Builds the {@link ProcessBuilder} for the given script, enriched with the
     * PATH additions the setup steps need and rooted at the app directory, with
     * stderr merged into stdout.
     */
    ProcessBuilder createProcessBuilder(Path script) {
        ProcessBuilder pb;
        if (isWindows()) {
            pb = new ProcessBuilder("powershell.exe", "-ExecutionPolicy", "Bypass", "-File", script.toString());
        } else {
            script.toFile().setExecutable(true);
            pb = new ProcessBuilder("bash", script.toString());
        }

        pb.redirectErrorStream(true);
        pb.directory(appDirectory.toFile());

        PathEnricher.enrich(pb);

        return pb;
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
