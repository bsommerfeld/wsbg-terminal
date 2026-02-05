package de.bsommerfeld.updater.launcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
 * {@code (phase, detail)} pairs. The phase always identifies the
 * concrete model being pulled (e.g. "Pulling model:version") so the
 * launcher UI can communicate transparently what it installs.
 * <ul>
 * <li><strong>Script pull announcements</strong> — "> Pulling model..." lines
 * set the active model name for all subsequent output</li>
 * <li><strong>Ollama progress</strong> — percentage + downloaded/total
 * sizes</li>
 * <li><strong>Ollama status</strong> — "verifying", "writing manifest",
 * "success" are forwarded under the active model phase</li>
 * <li><strong>Progress noise</strong> — bare progress bars, spinners, and
 * raw percentages from curl/ollama/winget are suppressed</li>
 * </ul>
 *
 * @see PathEnricher
 */
final class EnvironmentSetup {

    private static final long TIMEOUT_MINUTES = 15;

    private static final Pattern ANSI_PATTERN = Pattern.compile("\u001B\\[[0-9;?]*[a-zA-Z]");

    // Only matches "> Pulling model..." from our script — not ollama-internal
    // "pulling manifest" / "pulling <hash>" lines, which would overwrite the
    // tracked model name with garbage.
    private static final Pattern OLLAMA_PULL_PATTERN = Pattern.compile(">\\s*[Pp]ulling\\s+(.+?)(?:\\.{2,3})?$");
    private static final Pattern OLLAMA_PROGRESS_PATTERN = Pattern
            .compile("(\\d+)%.*?(\\d+(?:\\.\\d+)?\\s*(?:MB|GB|KB|B))\\s*/\\s*(\\d+(?:\\.\\d+)?\\s*(?:MB|GB|KB|B))");

    // Detects the script's Ollama install/update announcement to separate
    // platform setup from model downloads in the UI. Handles both the
    // current format "[*] Installing Ollama" / "[*] Updating Ollama" and
    // the legacy deployed format "[*] Installing/updating Ollama".
    private static final Pattern OLLAMA_INSTALL_PATTERN = Pattern.compile(
            "(?i)\\[\\*]\\s*(?:installing(?:/updating)?|updating)\\s+ollama");

    // Extracts trailing percentage from curl-style progress lines
    // (e.g. "####   8.8%" → group 1 = "8.8").
    private static final Pattern CURL_PROGRESS_PATTERN = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*%\\s*$");

    /**
     * Matches raw progress bars, spinner characters, and bare percentages.
     * Winget emits single-char spinners (\, |, /, -) and block-character
     * progress bars (███▒▒▒) that carry no useful semantic info.
     */
    private static final Pattern NOISE_PATTERN = Pattern.compile(
            "^[#=\\-\\s▕▏█░▒]+$|^[\\d.]+%$|^[/\\\\|\\-]$|\\d+\\s*[KMG]?B\\s*/\\s*\\d|^[▕▏█░▒\\s]+\\d+%");

    private final Path appDirectory;
    private final AtomicReference<Process> activeProcess = new AtomicReference<>();

    /**
     * Tracks which model is currently being pulled so progress/status lines inherit
     * the name.
     */
    private String currentModelName;

    /**
     * Tracks the maximum layer size currently being processed.
     */
    private long maxTotalBytes;

    /**
     * Active while the script installs/updates the Ollama binary. Cleared
     * when the script transitions to model config/pulls.
     */
    private boolean installingOllama;

    EnvironmentSetup(Path appDirectory) {
        this.appDirectory = appDirectory;
    }

    /**
     * Forcefully terminates the running setup process. Called by the launcher's
     * shutdown hook to prevent orphaned child processes (e.g. winget, ollama
     * pull) from running indefinitely after the user closes the window.
     */
    void killActiveProcess() {
        Process p = activeProcess.getAndSet(null);
        if (p != null && p.isAlive()) {
            p.descendants().forEach(ProcessHandle::destroyForcibly);
            p.destroyForcibly();
        }
    }

    /**
     * Runs the platform-specific setup script synchronously.
     *
     * @param outputConsumer receives {@code (phase, detail)} — phase identifies
     *                       the concrete action (e.g. "Pulling model:version"),
     *                       detail is context like "42% — 739 MB / 3.3 GB".
     *                       Detail may be {@code null}.
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
        activeProcess.set(process);

        try {
            streamOutput(process, outputConsumer);
            return awaitCompletion(process, outputConsumer);
        } finally {
            activeProcess.set(null);
        }
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
        if (tryEmitOllamaInstall(line, consumer))
            return;
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

        // Config/mode lines end the Ollama install phase — they belong
        // to the configuration section, not the platform download.
        if (installingOllama && (line.contains("Mode") || line.contains("Configuration")
                || line.contains("Roadmap"))) {
            installingOllama = false;
        }

        if (installingOllama) {
            Matcher cm = CURL_PROGRESS_PATTERN.matcher(line);
            if (cm.find()) {
                // Emit integer percentage so LauncherMain's progress parser handles it
                int pct = (int) Double.parseDouble(cm.group(1));
                consumer.accept("Installing AI platform", pct + "%");
            } else {
                consumer.accept("Installing AI platform", line);
            }
            return;
        }

        consumer.accept("Setting up environment", line);
    }

    /**
     * Activates the Ollama install phase when the script announces
     * "[*] Installing/updating Ollama...". All subsequent lines are
     * emitted under "Installing AI platform" until model pulls begin.
     */
    private boolean tryEmitOllamaInstall(String line, BiConsumer<String, String> consumer) {
        if (OLLAMA_INSTALL_PATTERN.matcher(line).find()) {
            installingOllama = true;
            consumer.accept("Installing AI platform", null);
            return true;
        }
        return false;
    }

    /**
     * Matches script-emitted "> Pulling model:version..." lines and tracks the
     * model name for subsequent progress/status emissions.
     */
    private boolean tryEmitOllamaPull(String line, BiConsumer<String, String> consumer) {
        Matcher m = OLLAMA_PULL_PATTERN.matcher(line);
        if (m.find()) {
            installingOllama = false;
            currentModelName = m.group(1).strip();
            maxTotalBytes = 0; // Reset for new model
            consumer.accept("Pulling " + currentModelName, null);
            return true;
        }
        return false;
    }

    /** Matches "42% ▕██▏ 739 MB/3.3 GB" → detail "42% — 739 MB / 3.3 GB". */
    private boolean tryEmitOllamaProgress(String line, BiConsumer<String, String> consumer) {
        Matcher m = OLLAMA_PROGRESS_PATTERN.matcher(line);
        if (m.find()) {
            String percent = m.group(1);
            String current = m.group(2);
            String total = m.group(3);

            long totalBytes = parseBytes(total);
            if (totalBytes < maxTotalBytes) {
                // Only the largest layer determines progress to prevent UI flickering.
                // Emitting every layer's progress would cause erratic values, as Ollama
                // downloads multiple layers concurrently.
                return true;
            }
            maxTotalBytes = totalBytes;

            String phase = currentModelName != null ? "Pulling " + currentModelName : "Pulling model";
            consumer.accept(phase, percent + "% — " + current + " / " + total);
            return true;
        }
        return false;
    }

    private long parseBytes(String sizeStr) {
        try {
            String normalized = sizeStr.toUpperCase().replaceAll("\\s+", "");
            double val = Double.parseDouble(normalized.replaceAll("[^0-9.]", ""));
            if (normalized.endsWith("GB"))
                return (long) (val * 1_000_000_000L);
            if (normalized.endsWith("MB"))
                return (long) (val * 1_000_000L);
            if (normalized.endsWith("KB"))
                return (long) (val * 1_000L);
            return (long) val;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Matches ollama-internal status lines ("pulling manifest", "verifying",
     * "writing manifest", "success").
     */
    private boolean tryEmitOllamaStatus(String line, BiConsumer<String, String> consumer) {
        String phase = currentModelName != null ? "Pulling " + currentModelName : "Pulling model";

        if (line.contains("writing manifest")) {
            consumer.accept(phase, "writing manifest");
            return true;
        } else if (line.contains("verifying sha256 digest")) {
            consumer.accept(phase, "verifying sha256 digest");
            return true;
        } else if (line.contains("pulling manifest")) {
            consumer.accept(phase, "pulling manifest");
            return true;
        } else if (line.equals("success") || line.contains("success")) {
            consumer.accept(phase, "success");
            return true;
        }

        // Unstructured pulling logs are silently consumed.
        // Emitting them would overwrite the active progress detail, leading to UI
        // flickering.
        if (line.startsWith("pulling ")) {
            return true;
        }

        return false;
    }

    /**
     * Uses find() because the pattern mixes full-line anchored segments
     * (^...$) with prefix-only segments (^...) — matches() would require
     * every alternative to cover the entire string, silently breaking
     * prefix patterns like the KB/MB size detection.
     */
    private boolean isNoise(String line) {
        return NOISE_PATTERN.matcher(line).find();
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
