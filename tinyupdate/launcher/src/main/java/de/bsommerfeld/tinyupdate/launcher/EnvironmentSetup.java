package de.bsommerfeld.tinyupdate.launcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Executes OS-appropriate setup scripts before launching the application.
 *
 * <p>Runs {@code bin/setup.sh} (macOS/Linux) or {@code bin/setup.ps1} (Windows).
 * The launcher blocks until the script completes. Output is forwarded to
 * the provided consumer for log/UI display.
 */
final class EnvironmentSetup {

    private static final long TIMEOUT_MINUTES = 15;

    private final Path appDirectory;

    EnvironmentSetup(Path appDirectory) {
        this.appDirectory = appDirectory;
    }

    /**
     * Runs the platform-specific setup script.
     *
     * @param outputConsumer receives each line of script output
     * @return {@code true} if the script exited with code 0
     */
    boolean run(Consumer<String> outputConsumer) throws IOException, InterruptedException {
        Path script = resolveScript();
        if (script == null || !Files.exists(script)) {
            outputConsumer.accept("No setup script found — skipping environment check");
            return true;
        }

        outputConsumer.accept("Running environment setup: " + script.getFileName());

        ProcessBuilder pb = createProcessBuilder(script);
        pb.redirectErrorStream(true);
        pb.directory(appDirectory.toFile());

        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                outputConsumer.accept(line);
            }
        }

        boolean finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            outputConsumer.accept("Setup script timed out after " + TIMEOUT_MINUTES + " minutes");
            return false;
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            outputConsumer.accept("Setup script exited with code " + exitCode);
        }
        return exitCode == 0;
    }

    private Path resolveScript() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return appDirectory.resolve("bin/setup.ps1");
        }
        return appDirectory.resolve("bin/setup.sh");
    }

    private ProcessBuilder createProcessBuilder(Path script) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return new ProcessBuilder(
                    "powershell.exe", "-ExecutionPolicy", "Bypass", "-File", script.toString()
            );
        }

        // Ensure script is executable on Unix
        script.toFile().setExecutable(true);
        return new ProcessBuilder("bash", script.toString());
    }
}
