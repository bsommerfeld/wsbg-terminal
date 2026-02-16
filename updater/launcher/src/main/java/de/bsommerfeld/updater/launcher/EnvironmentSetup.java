package de.bsommerfeld.updater.launcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes OS-appropriate setup scripts before launching the application.
 *
 * <p>Runs {@code bin/setup.sh} (macOS/Linux) or {@code bin/setup.ps1} (Windows).
 * The launcher blocks until the script completes. Output is forwarded to
 * the provided consumer for log/UI display.
 */
final class EnvironmentSetup {

    private static final long TIMEOUT_MINUTES = 15;

    // Matches ANSI escape sequences (cursor control, colors, etc.)
    private static final Pattern ANSI_PATTERN = Pattern.compile("\u001B\\[[0-9;?]*[a-zA-Z]");

    // Matches Ollama's pull progress lines like "pulling a5db3381f2e5: 100% ▕██..▏ 957 MB"
    // and model-name lines like "> Pulling gemma3:4b..."
    private static final Pattern OLLAMA_PULL_PATTERN =
            Pattern.compile("(?:>\\s*)?[Pp]ulling\\s+(.+?)(?:\\.{2,3})?$");
    private static final Pattern OLLAMA_PROGRESS_PATTERN =
            Pattern.compile("(\\d+)%.*?(\\d+(?:\\.\\d+)?\\s*(?:MB|GB|KB|B))");

    private final Path appDirectory;

    EnvironmentSetup(Path appDirectory) {
        this.appDirectory = appDirectory;
    }

    /**
     * Runs the platform-specific setup script.
     *
     * @param outputConsumer receives (phase, detail) — phase is the high-level step,
     *                       detail is context like "gemma3:4b — 42% (1.2 GB)"
     * @return {@code true} if the script exited with code 0
     */
    boolean run(BiConsumer<String, String> outputConsumer) throws IOException, InterruptedException {
        Path script = resolveScript();
        if (script == null || !Files.exists(script)) {
            outputConsumer.accept("No setup script found", "skipping environment check");
            return true;
        }

        outputConsumer.accept("Running environment setup", script.getFileName().toString());

        ProcessBuilder pb = createProcessBuilder(script);
        pb.redirectErrorStream(true);
        pb.directory(appDirectory.toFile());

        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String clean = stripAnsi(line).strip();
                if (clean.isEmpty()) continue;

                parseAndEmit(clean, outputConsumer);
            }
        }

        boolean finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            outputConsumer.accept("Setup timed out", "after " + TIMEOUT_MINUTES + " minutes");
            return false;
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            outputConsumer.accept("Setup warning", "exited with code " + exitCode);
        }
        return exitCode == 0;
    }

    /**
     * Parses a clean output line into (phase, detail). Recognizes Ollama pull
     * progress and model names for structured UI display.
     */
    private void parseAndEmit(String line, BiConsumer<String, String> consumer) {
        // Ollama model pull "Pulling gemma3:4b..."
        Matcher pullMatcher = OLLAMA_PULL_PATTERN.matcher(line);
        if (pullMatcher.find()) {
            String model = pullMatcher.group(1).strip();
            consumer.accept("Pulling model", model);
            return;
        }

        // Ollama download progress "pulling abc123: 42% ▕██..▏ 1.2 GB"
        Matcher progressMatcher = OLLAMA_PROGRESS_PATTERN.matcher(line);
        if (progressMatcher.find()) {
            String percent = progressMatcher.group(1) + "%";
            String size = progressMatcher.group(2);
            consumer.accept("Pulling model", percent + " — " + size);
            return;
        }

        // Ollama status lines (verifying, writing manifest, success)
        if (line.startsWith("verifying") || line.startsWith("writing manifest") || line.equals("success")) {
            consumer.accept("Pulling model", line);
            return;
        }

        // Raw progress bar noise from curl/ollama install (###, ▕██▏, bare percentages).
        // These overflow the label and convey no useful info beyond what we already parse.
        if (line.matches("^[#=\\-\\s▕▏█░]+$") || line.matches("^[\\d.]+%$")) {
            return;
        }

        // Setup banner lines
        if (line.contains("Setup Complete") || line.contains("=====")) {
            consumer.accept("Setup complete", null);
            return;
        }

        // Generic setup output
        consumer.accept("Setting up environment", line);
    }

    private String stripAnsi(String input) {
        return ANSI_PATTERN.matcher(input).replaceAll("");
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

        script.toFile().setExecutable(true);

        ProcessBuilder pb = new ProcessBuilder("bash", script.toString());

        // JPackage strips PATH to a bare minimum, hiding user-installed tools
        // like ollama in /usr/local/bin or /opt/homebrew/bin. Without this,
        // setup.sh thinks ollama is missing and reinstalls it from scratch.
        String path = pb.environment().getOrDefault("PATH", "/usr/bin:/bin");
        pb.environment().put("PATH", path
                + ":/usr/local/bin:/opt/homebrew/bin:/opt/homebrew/sbin"
                + ":" + System.getProperty("user.home") + "/.local/bin");

        return pb;
    }
}
