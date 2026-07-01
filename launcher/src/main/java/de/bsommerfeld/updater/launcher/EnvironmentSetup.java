package de.bsommerfeld.updater.launcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Executes OS-appropriate setup scripts before the application launches.
 *
 * <p>
 * Runs {@code bin/setup.sh} (macOS/Linux) or {@code bin/setup.ps1}
 * (Windows). For local development the script directory can be overridden
 * via the {@code wsbg.setup.script.dir} system property or the
 * {@code WSBG_SETUP_SCRIPT_DIR} environment variable, so dev launches
 * always test against the repo's {@code .script/} instead of the
 * release-cached {@code bin/} copies.
 *
 * <h3>Watchdog</h3>
 * The launcher blocks until the script completes. There is no fixed overall
 * deadline — a first-run model download legitimately takes as long as the
 * connection needs — but an <em>idle</em> watchdog kills the script when it
 * produces no output for {@link #DEFAULT_IDLE_TIMEOUT}: every long-running
 * step (curl, ollama pull) emits steady progress lines, so prolonged silence
 * means the process is hung, not slow. {@link #TIMEOUT_MINUTES} only caps
 * the residual gap between stdout EOF and process exit.
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

    /** Caps only the stdout-EOF → process-exit gap, not the script runtime. */
    private static final long TIMEOUT_MINUTES = 15;

    /** Kill the script when it emits no output at all for this long. */
    static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(10);

    /**
     * Script exit code meaning "finished, but at least one step degraded"
     * (e.g. a font download failed, a model update was skipped offline).
     * Distinct from a hard failure so the launcher can phrase it as a
     * warning. Must match the {@code exit 10} in setup.sh / setup.ps1.
     */
    static final int EXIT_WITH_WARNINGS = 10;

    /** Dev-only override for the setup-script directory (see class javadoc). */
    static final String SCRIPT_DIR_PROPERTY = "wsbg.setup.script.dir";
    static final String SCRIPT_DIR_ENV = "WSBG_SETUP_SCRIPT_DIR";

    private static final Pattern ANSI_PATTERN = Pattern.compile("\u001B\\[[0-9;?]*[a-zA-Z]");

    // Only matches "> Pulling model (idx/total)..." from our script — not
    // ollama-internal "pulling manifest" / "pulling <hash>" lines, which would
    // overwrite the tracked model name with garbage. Group 1 is the model name
    // (no spaces, e.g. "gemma4:e4b"); the optional "(idx/total)" (groups 2/3)
    // lets the launcher render one pip per model. The count is optional so a
    // bare legacy "> Pulling model..." line still matches.
    private static final Pattern OLLAMA_PULL_PATTERN = Pattern.compile(
            ">\\s*[Pp]ulling\\s+(\\S+?)(?:\\s+\\((\\d+)/(\\d+)\\))?(?:\\.{2,3})?$");
    private static final Pattern OLLAMA_PROGRESS_PATTERN = Pattern
            .compile("(\\d+)%.*?(\\d+(?:\\.\\d+)?\\s*(?:MB|GB|KB|B))\\s*/\\s*(\\d+(?:\\.\\d+)?\\s*(?:MB|GB|KB|B))");

    // Detects the script's Ollama install/update announcement to separate
    // platform setup from model downloads in the UI. Matches all wordings used
    // over time: "[*] Installing Ollama", "[*] Updating Ollama", the legacy
    // "[*] Installing/updating Ollama", and the isolated-install line
    // "[*] Installing isolated Ollama 0.24.0 into ..." — any "installing/
    // updating ... ollama" on a "[*]" status line.
    private static final Pattern OLLAMA_INSTALL_PATTERN = Pattern.compile(
            "(?i)\\[\\*]\\s*(?:installing|updating)\\b.*\\bollama");

    // The script's "[*] Installing browser runtime (macosx-arm64)..." line —
    // the JCEF (~150 MB Chromium) download, which is otherwise the slowest
    // step with no dedicated phase. Only the active install matches; the
    // "[*] Browser runtime already installed." short-circuit deliberately does
    // not (no download happens, so no phase is needed).
    private static final Pattern JCEF_INSTALL_PATTERN = Pattern.compile(
            "(?i)\\[\\*]\\s*installing\\s+browser\\s+runtime");

    // The script's "[*] Installing terminal fonts..." line — a handful of small
    // woff2 files, fast enough that it needs only a label, not a progress bar.
    private static final Pattern FONTS_INSTALL_PATTERN = Pattern.compile(
            "(?i)\\[\\*]\\s*installing\\s+(?:terminal\\s+)?fonts");

    // Extracts trailing percentage from curl-style progress lines
    // (e.g. "####   8.8%" → group 1 = "8.8").
    private static final Pattern CURL_PROGRESS_PATTERN = Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*%\\s*$");

    // curl's DEFAULT transfer meter, which it prints line-by-line (one row per
    // update) when stdout/stderr is a pipe rather than a TTY — exactly how it
    // runs under the launcher. The leading "% Total" column is the download
    // percentage, e.g. "  2 1.92G    2 52.78M    0     0  50.2M ..." → 2%.
    // The header rows ("% Total..." / "Dload Upload...") start with '%'/a letter
    // and are skipped. Used to drive the bar during the Ollama binary download.
    private static final Pattern CURL_METER_PATTERN = Pattern.compile(
            "^(\\d{1,3})\\s+[\\d.]+\\s*[KMGTP]?B?\\b");

    // Same curl default meter, but also capturing the Total (group 2) and
    // Received (group 3) size columns so the install phase can report a rich
    // "pct% — downloaded / total" detail — driving the speed + ETA readouts
    // exactly like an ollama model pull. Layout: "<%tot> <total> <%recv> <recv>".
    private static final Pattern CURL_METER_BYTES_PATTERN = Pattern.compile(
            "^(\\d{1,3})\\s+([\\d.]+[KMGTP]?B?)\\s+\\d{1,3}\\s+([\\d.]+[KMGTP]?B?)\\b");

    /**
     * Matches raw progress bars, spinner characters, and bare percentages.
     * Winget emits single-char spinners (\, |, /, -) and block-character
     * progress bars (███▒▒▒) that carry no useful semantic info.
     */
    private static final Pattern NOISE_PATTERN = Pattern.compile(
            "^[#=\\-\\s▕▏█░▒]+$|^[\\d.]+%$|^[/\\\\|\\-]$|\\d+\\s*[KMG]?B\\s*/\\s*\\d|^[▕▏█░▒\\s]+\\d+%");

    private final Path appDirectory;
    private final Duration idleTimeout;
    private final AtomicReference<Process> activeProcess = new AtomicReference<>();

    /** Timestamp of the last script output line, driving the idle watchdog. */
    private final AtomicLong lastOutputAt = new AtomicLong();

    /** Set by the watchdog when it killed the script for being silent. */
    private volatile boolean idleTimedOut;

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

    /**
     * Active while the script downloads the JCEF (browser) runtime. Cleared
     * when the script reports the runtime ready or moves on to fonts/config.
     */
    private boolean installingBrowser;

    EnvironmentSetup(Path appDirectory) {
        this(appDirectory, DEFAULT_IDLE_TIMEOUT);
    }

    /** Test seam: inject a short idle timeout. */
    EnvironmentSetup(Path appDirectory, Duration idleTimeout) {
        this.appDirectory = appDirectory;
        this.idleTimeout = idleTimeout;
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
        lastOutputAt.set(System.currentTimeMillis());
        idleTimedOut = false;

        Thread watchdog = Thread.ofVirtual().name("setup-watchdog")
                .start(() -> watchIdle(process));
        try {
            streamOutput(process, outputConsumer);
            return awaitCompletion(process, outputConsumer);
        } finally {
            watchdog.interrupt();
            activeProcess.set(null);
        }
    }

    /**
     * Kills the script (and its children) once it has been silent for
     * {@link #idleTimeout}. Active downloads emit progress lines continuously,
     * so this never fires on a slow connection — only on a genuine hang.
     */
    private void watchIdle(Process process) {
        try {
            while (process.isAlive()) {
                long remaining = idleTimeout.toMillis()
                        - (System.currentTimeMillis() - lastOutputAt.get());
                if (remaining <= 0) {
                    idleTimedOut = true;
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                    return;
                }
                Thread.sleep(Math.min(remaining, 1000));
            }
        } catch (InterruptedException ignored) {
            // run() finished — watchdog no longer needed
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
                // Any output — even noise — proves the script is alive.
                lastOutputAt.set(System.currentTimeMillis());

                String clean = stripAnsi(line).strip();
                if (clean.isEmpty())
                    continue;

                classifyAndEmit(clean, consumer);
            }
        }
    }

    /**
     * Waits for the process to finish. Stdout is already at EOF here, so the
     * {@link #TIMEOUT_MINUTES} wait only covers the exit gap; a script hung
     * mid-run was already killed by the idle watchdog.
     *
     * @return {@code true} if the process exited with code 0
     */
    private boolean awaitCompletion(Process process, BiConsumer<String, String> consumer)
            throws InterruptedException {
        boolean finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);

        if (idleTimedOut) {
            consumer.accept("Setup timed out",
                    "no output for " + idleTimeout.toMinutes() + " minutes");
            return false;
        }
        if (!finished) {
            process.destroyForcibly();
            consumer.accept("Setup timed out", "after " + TIMEOUT_MINUTES + " minutes");
            return false;
        }

        int exitCode = process.exitValue();
        if (exitCode == EXIT_WITH_WARNINGS) {
            consumer.accept("Setup completed with warnings", null);
            return false;
        }
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
        if (tryEmitBrowserInstall(line, consumer))
            return;
        if (tryEmitFontsInstall(line, consumer))
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
            // Prefer the rich "pct% — downloaded / total" detail so the UI shows
            // speed + ETA for the (large) Ollama binary download, not just a bar.
            String detail = parseDownloadDetail(line);
            consumer.accept("Installing AI platform", detail != null ? detail : line);
            return;
        }

        if (installingBrowser) {
            // The runtime-ready line ends the phase; anything else is the curl
            // --progress-bar transfer, surfaced as a bare "pct%" bar (no byte
            // figures in --progress-bar output, hence no speed/ETA).
            if (line.contains("Browser runtime ready")) {
                installingBrowser = false;
                return;
            }
            String detail = parseDownloadDetail(line);
            consumer.accept("Installing browser runtime", detail);
            return;
        }

        consumer.accept("Setting up environment", line);
    }

    /**
     * Extracts a download percentage (0–100) from a curl progress line during the
     * Ollama install phase, or {@code -1} if the line carries no percentage.
     * Handles both curl's default transfer meter (leading "% Total" column) and
     * the {@code --progress-bar} style (trailing "45.0%").
     */
    static int parseDownloadPercent(String line) {
        Matcher meter = CURL_METER_PATTERN.matcher(line);
        if (meter.find()) {
            int pct = Integer.parseInt(meter.group(1));
            return pct <= 100 ? pct : -1;
        }
        Matcher trailing = CURL_PROGRESS_PATTERN.matcher(line);
        if (trailing.find()) {
            int pct = (int) Double.parseDouble(trailing.group(1));
            return Math.min(pct, 100);
        }
        return -1;
    }

    /**
     * Builds the progress detail for the Ollama binary download from a curl
     * progress line. Prefers the rich {@code "pct% — downloaded / total"} form
     * (so {@code LauncherMain} can derive speed and a remaining-time estimate,
     * exactly like a model pull); falls back to a bare {@code "pct%"} for curl's
     * {@code --progress-bar} style, or {@code null} when the line carries no
     * progress at all.
     */
    static String parseDownloadDetail(String line) {
        Matcher meter = CURL_METER_BYTES_PATTERN.matcher(line);
        if (meter.find()) {
            int pct = Integer.parseInt(meter.group(1));
            if (pct > 100) return null;
            String total = normalizeCurlSize(meter.group(2));
            String downloaded = normalizeCurlSize(meter.group(3));
            if (total != null && downloaded != null) {
                return pct + "% — " + downloaded + " / " + total;
            }
            return pct + "%";
        }
        int pct = parseDownloadPercent(line);
        return pct >= 0 ? pct + "%" : null;
    }

    /**
     * Normalizes a curl-meter size token ("1.92G", "52.78M", "739K", optionally
     * with a trailing 'B') into the spaced "{@code 1.92 GB}" form that
     * {@code LauncherMain.parseByteSize} understands. Returns {@code null} for
     * empty input.
     */
    static String normalizeCurlSize(String s) {
        if (s == null) return null;
        String t = s.toUpperCase().strip();
        if (t.endsWith("B")) t = t.substring(0, t.length() - 1); // tolerate "1.92GB"
        if (t.isEmpty()) return null;
        char unit = t.charAt(t.length() - 1);
        if (Character.isLetter(unit)) {
            String num = t.substring(0, t.length() - 1);
            return switch (unit) {
                case 'G' -> num + " GB";
                case 'M' -> num + " MB";
                case 'K' -> num + " KB";
                default -> num + " B";
            };
        }
        return t + " B";
    }

    /**
     * Activates the Ollama install phase when the script announces
     * "[*] Installing/updating Ollama...". All subsequent lines are
     * emitted under "Installing AI platform" until model pulls begin.
     */
    private boolean tryEmitOllamaInstall(String line, BiConsumer<String, String> consumer) {
        if (OLLAMA_INSTALL_PATTERN.matcher(line).find()) {
            installingOllama = true;
            installingBrowser = false;
            consumer.accept("Installing AI platform", null);
            return true;
        }
        return false;
    }

    /**
     * Activates the browser-runtime (JCEF) download phase when the script
     * announces "[*] Installing browser runtime ...". The ~150 MB Chromium
     * download is the slowest single step, so it gets its own phase rather
     * than hiding under a generic "Setting up environment".
     */
    private boolean tryEmitBrowserInstall(String line, BiConsumer<String, String> consumer) {
        if (JCEF_INSTALL_PATTERN.matcher(line).find()) {
            installingBrowser = true;
            installingOllama = false;
            consumer.accept("Installing browser runtime", null);
            return true;
        }
        return false;
    }

    /**
     * Surfaces the font-install step under its own label. The downloads are a
     * few small woff2 files, so there is no progress to track — just the phase.
     */
    private boolean tryEmitFontsInstall(String line, BiConsumer<String, String> consumer) {
        if (FONTS_INSTALL_PATTERN.matcher(line).find()) {
            installingOllama = false;
            installingBrowser = false;
            consumer.accept("Installing fonts", null);
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
            installingBrowser = false;
            currentModelName = m.group(1).strip();
            maxTotalBytes = 0; // Reset for new model

            // "ModelCount" is a control message (not a user-visible phase): it
            // carries "total/completed" so the launcher can draw one pip per
            // model. Emitted before the "Pulling" phase; the index is 1-based,
            // so "completed" = idx - 1 (the models pulled before this one).
            if (m.group(2) != null && m.group(3) != null) {
                int idx = Integer.parseInt(m.group(2));
                int total = Integer.parseInt(m.group(3));
                consumer.accept("ModelCount", total + "/" + (idx - 1));
            }

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
