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
 * Executes OS-appropriate setup scripts before the application launches.
 *
 * <p>
 * Runs {@code bin/setup.sh} (macOS/Linux) or {@code bin/setup.ps1}
 * (Windows). The launcher blocks until the script completes or the
 * {@link #TIMEOUT_MINUTES} deadline is reached.
 *
 * <h3>Output parsing</h3>
 * Script output is streamed line-by-line and parsed into structured
 * {@code (phase, detail)} pairs. The parser recognizes three categories:
 * <ul>
 * <li><strong>Ollama model pulls</strong> — model name, download percentage,
 * and transfer size are extracted and forwarded separately</li>
 * <li><strong>Ollama status lines</strong> — "verifying", "writing manifest",
 * "success" are forwarded as-is under the "Pulling model" phase</li>
 * <li><strong>Progress noise</strong> — bare progress bars and raw percentages
 * from curl/ollama install are suppressed</li>
 * </ul>
 *
 * @see PathEnricher
 */
final class EnvironmentSetup {

    private static final long TIMEOUT_MINUTES = 15;

    private static final Pattern ANSI_PATTERN = Pattern.compile("\u001B\\[[0-9;?]*[a-zA-Z]");

    private static final Pattern OLLAMA_PULL_PATTERN = Pattern.compile("(?:>\\s*)?[Pp]ulling\\s+(.+?)(?:\\.{2,3})?$");
    private static final Pattern OLLAMA_PROGRESS_PATTERN = Pattern
            .compile("(\\d+)%.*?(\\d+(?:\\.\\d+)?\\s*(?:MB|GB|KB|B))");

    /**
     * Matches raw progress bars (###, ▕██▏) and bare percentages that carry no
     * useful info.
     */
    private static final Pattern NOISE_PATTERN = Pattern.compile("^[#=\\-\\s▕▏█░]+$|^[\\d.]+%$");

    private final Path appDirectory;

    EnvironmentSetup(Path appDirectory) {
        this.appDirectory = appDirectory;
    }

    /**
     * Runs the platform-specific setup script synchronously.
     *
     * @param outputConsumer receives {@code (phase, detail)} — phase is the
     *                       high-level step ("Pulling model"), detail is context
     *                       like "gemma3:4b — 42% (1.2 GB)". Detail may be
     *                       {@code null}.
     * @return {@code true} if the script exited with code 0
     * @throws IOException          if the script cannot be started
     * @throws InterruptedException if the thread is interrupted while waiting
     */
    boolean run(BiConsumer<String, String> outputConsumer) throws IOException, InterruptedException {
        Path script = resolveScript();
        if (script == null || !Files.exists(script)) {
            outputConsumer.accept("No setup script found", "skipping environment check");
            return true;
        }

        outputConsumer.accept("Running environment setup", script.getFileName().toString());

        ProcessBuilder pb = createProcessBuilder(script);
        Process process = pb.start();

        streamOutput(process, outputConsumer);

        return awaitCompletion(process, outputConsumer);
    }

    // =====================================================================
    // Process I/O
    // =====================================================================

    /**
     * Reads process stdout line-by-line, strips ANSI escapes, classifies each
     * line, and emits structured (phase, detail) events to the consumer.
     */
    private void streamOutput(Process process, BiConsumer<String, String> consumer) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String clean = stripAnsi(line).strip();
                if (clean.isEmpty())
                    continue;

                classifyAndEmit(clean, consumer);
            }
        }
    }

    /**
     * Waits for the process to finish within the timeout. If the process
     * does not complete in time, it is force-killed.
     *
     * @return {@code true} if the process exited with code 0
     */
    private boolean awaitCompletion(Process process, BiConsumer<String, String> consumer)
            throws InterruptedException {
        boolean finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);
        if (!finished) {
            process.destroyForcibly();
            consumer.accept("Setup timed out", "after " + TIMEOUT_MINUTES + " minutes");
            return false;
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            consumer.accept("Setup warning", "exited with code " + exitCode);
        }
        return exitCode == 0;
    }

    // =====================================================================
    // Output Classification
    // =====================================================================

    /**
     * Classifies a clean output line into a structured {@code (phase, detail)}
     * pair. Ollama-specific patterns are checked first because they are the
     * most frequent output during model pulls.
     */
    private void classifyAndEmit(String line, BiConsumer<String, String> consumer) {
        if (tryEmitOllamaPull(line, consumer))
            return;
        if (tryEmitOllamaProgress(line, consumer))
            return;
        if (tryEmitOllamaStatus(line, consumer))
            return;
        if (isNoise(line))
            return;

        if (line.contains("Setup Complete") || line.contains("=====")) {
            consumer.accept("Setup complete", null);
            return;
        }

        consumer.accept("Setting up environment", line);
    }

    /**
     * Matches "Pulling gemma3:4b..." → phase "Pulling model", detail "gemma3:4b".
     */
    private boolean tryEmitOllamaPull(String line, BiConsumer<String, String> consumer) {
        Matcher m = OLLAMA_PULL_PATTERN.matcher(line);
        if (m.find()) {
            consumer.accept("Pulling model", m.group(1).strip());
            return true;
        }
        return false;
    }

    /** Matches "42% ▕██▏ 1.2 GB" → phase "Pulling model", detail "42% — 1.2 GB". */
    private boolean tryEmitOllamaProgress(String line, BiConsumer<String, String> consumer) {
        Matcher m = OLLAMA_PROGRESS_PATTERN.matcher(line);
        if (m.find()) {
            consumer.accept("Pulling model", m.group(1) + "% — " + m.group(2));
            return true;
        }
        return false;
    }

    /** Matches "verifying", "writing manifest", "success". */
    private boolean tryEmitOllamaStatus(String line, BiConsumer<String, String> consumer) {
        if (line.startsWith("verifying") || line.startsWith("writing manifest") || line.equals("success")) {
            consumer.accept("Pulling model", line);
            return true;
        }
        return false;
    }

    /**
     * Returns {@code true} for raw progress bar noise that should be suppressed.
     */
    private boolean isNoise(String line) {
        return NOISE_PATTERN.matcher(line).matches();
    }

    private String stripAnsi(String input) {
        return ANSI_PATTERN.matcher(input).replaceAll("");
    }

    // =====================================================================
    // Process Setup
    // =====================================================================

    private Path resolveScript() {
        if (isWindows()) {
            return appDirectory.resolve("bin/setup.ps1");
        }
        return appDirectory.resolve("bin/setup.sh");
    }

    private ProcessBuilder createProcessBuilder(Path script) {
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
