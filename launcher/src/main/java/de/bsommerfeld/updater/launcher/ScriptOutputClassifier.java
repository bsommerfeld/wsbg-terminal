package de.bsommerfeld.updater.launcher;

import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps a clean setup-script stdout line into a structured {@code (phase,
 * detail)} event. This is the phase-token producer whose output strings form a
 * contract with {@code LauncherI18n} (translation keys) and {@code
 * SetupProgressAdapter} (step numbering, group snap, pip control) — the token
 * literals here must not change.
 *
 * <p>Holds the small amount of cross-line phase state (which model is being
 * pulled, whether the Ollama binary or the browser runtime is installing) so a
 * bare progress line inherits the right phase. A fresh instance is used per
 * script run.
 */
final class ScriptOutputClassifier {

    private static final Pattern ANSI_PATTERN = Pattern.compile("\\[[0-9;?]*[a-zA-Z]");

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

    // The script's "[*] Cleaning up old models..." header — the model-store GC
    // step, emitted ONLY when at least one no-longer-desired model is about to
    // be removed. Surfaced under its own "Räume Altlasten weg" label so the
    // user sees the reconcile removing Altlasten rather than a silent gap.
    private static final Pattern CLEANUP_PATTERN = Pattern.compile(
            "(?i)\\[\\*]\\s*cleaning\\s+up\\s+old\\s+models");

    // Each "> Removing stale model <name> (idx/total)..." line under that step.
    // Group 1 is the model name; it inherits the cleanup phase and rides as the
    // detail (log only — the label stays the plain translated phase).
    private static final Pattern MODEL_REMOVE_PATTERN = Pattern.compile(
            "(?i)>\\s*removing\\s+stale\\s+model\\s+(\\S+)");

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

    /** Tracks which model is currently being pulled so lines inherit the name. */
    private String currentModelName;

    /** Tracks the maximum layer size currently being processed. */
    private long maxTotalBytes;

    /** Active while the script installs/updates the Ollama binary. */
    private boolean installingOllama;

    /** Active while the script downloads the JCEF (browser) runtime. */
    private boolean installingBrowser;

    /**
     * Classifies a clean output line into a structured {@code (phase, detail)}
     * pair. Ollama-specific patterns are checked first because they are the
     * most frequent output during model pulls.
     */
    void classify(String line, BiConsumer<String, String> consumer) {
        if (tryEmitOllamaInstall(line, consumer))
            return;
        if (tryEmitBrowserInstall(line, consumer))
            return;
        if (tryEmitFontsInstall(line, consumer))
            return;
        if (tryEmitCleanup(line, consumer))
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
     * (so the launcher can derive speed and a remaining-time estimate, exactly
     * like a model pull); falls back to a bare {@code "pct%"} for curl's
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
     * {@link ByteSizes} understands. Returns {@code null} for empty input.
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
     * Surfaces the model-store cleanup (GC) step. The header line opens the
     * phase; each "> Removing stale model <name>..." line rides under it with
     * the model name as detail. Both clear the install flags so a later stray
     * progress line is not misattributed to a download phase. The step only
     * appears when the script actually has Altlasten to drop.
     */
    private boolean tryEmitCleanup(String line, BiConsumer<String, String> consumer) {
        if (CLEANUP_PATTERN.matcher(line).find()) {
            installingOllama = false;
            installingBrowser = false;
            consumer.accept("Cleaning up old models", null);
            return true;
        }
        Matcher m = MODEL_REMOVE_PATTERN.matcher(line);
        if (m.find()) {
            installingOllama = false;
            installingBrowser = false;
            consumer.accept("Cleaning up old models", m.group(1).strip());
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
            // carries "total/started" so the launcher can draw one pip per
            // model. Emitted before the "Pulling" phase; the 1-based index IS
            // the started count, so the current model's pip lights up the
            // moment its pull begins, not only once it finished.
            if (m.group(2) != null && m.group(3) != null) {
                int idx = Integer.parseInt(m.group(2));
                int total = Integer.parseInt(m.group(3));
                consumer.accept("ModelCount", total + "/" + idx);
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

            long totalBytes = ByteSizes.parseOrZero(total);
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
    boolean isNoise(String line) {
        return NOISE_PATTERN.matcher(line).find();
    }

    String stripAnsi(String input) {
        return ANSI_PATTERN.matcher(input).replaceAll("");
    }
}
