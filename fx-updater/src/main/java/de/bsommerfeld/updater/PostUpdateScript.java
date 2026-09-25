package de.bsommerfeld.updater;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Runs the script an application wants run after an update, with the
 * interpreter its extension calls for, inside the install.
 *
 * <p>
 * Exit codes follow the setup scripts' contract: {@code 0} ready,
 * {@value #EXIT_WITH_WARNINGS} ready with warnings (the application still
 * runs), anything else failed.
 */
final class PostUpdateScript {

    /** Ready, but some step warned - not a failure. */
    static final int EXIT_WITH_WARNINGS = 10;

    private PostUpdateScript() {
    }

    /** Runs the script to its end and returns its exit code; its output joins the updater's. */
    static int run(Path install, String script) throws IOException, InterruptedException {
        Path file = install.resolve(script);
        return new ProcessBuilder(command(file))
                .directory(install.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .start()
                .waitFor();
    }

    /** Whether an exit code means the script succeeded, warnings included. */
    static boolean succeeded(int exitCode) {
        return exitCode == 0 || exitCode == EXIT_WITH_WARNINGS;
    }

    static List<String> command(Path script) {
        String name = script.getFileName().toString().toLowerCase(Locale.ROOT);
        String path = script.toString();
        if (name.endsWith(".sh")) {
            return List.of("bash", path);
        }
        if (name.endsWith(".ps1")) {
            return List.of("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", path);
        }
        if (name.endsWith(".bat") || name.endsWith(".cmd")) {
            return List.of("cmd", "/c", path);
        }
        return List.of(path);
    }
}
