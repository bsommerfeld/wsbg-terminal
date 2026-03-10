package de.bsommerfeld.updater.launcher;

import de.bsommerfeld.updater.api.GitHubRepository;
import de.bsommerfeld.updater.api.TinyUpdateClient;

import javax.swing.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Native launcher entry point. Orchestrates the full startup sequence:
 * <strong>update → environment setup → application launch</strong>.
 *
 * <h3>Visibility</h3>
 * The launcher window stays hidden when everything is up-to-date and no
 * setup work is needed. It only becomes visible when an actual download
 * starts or an error occurs — first-run users always see it.
 *
 * <h3>Error handling philosophy</h3>
 * The launcher must <strong>never crash silently</strong>. Every failure path
 * either recovers gracefully (launching a cached version) or presents a
 * visible error dialog before exiting. This is the first thing users see;
 * a blank screen with no feedback is unacceptable.
 *
 * <h3>Thread model</h3>
 * The update/setup/launch pipeline runs on a virtual thread to keep the
 * Swing EDT responsive. All window updates go through {@link SwingUtilities}.
 */
public final class LauncherMain {

    private static final GitHubRepository REPO = GitHubRepository.of("bsommerfeld/wsbg-terminal");
    private static final int MAX_LOG_FILES = 10;
    private static final DateTimeFormatter LOG_TIMESTAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter LOG_FILENAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

    /** Set once per session in {@link #initLogging}; all log calls write here. */
    private static Path sessionLogFile;

    private LauncherMain() {
    }

    public static void main(String[] args) {
        Path appDir = StorageResolver.resolve();

        if (!ensureDirectories(appDir))
            return;

        initLogging(appDir);
        log(appDir, "Launcher started");

        LauncherI18n i18n = new LauncherI18n(appDir);
        log(appDir, "Language: " + i18n.language());

        TinyUpdateClient updateClient = new TinyUpdateClient(REPO, appDir);
        // AI model install counts as a step in the unified pipeline
        updateClient.setExtraSteps(1);

        boolean firstRun = updateClient.currentVersion() == null;
        LauncherWindow window = new LauncherWindow();
        EnvironmentSetup envSetup = new EnvironmentSetup(appDir);

        // Ensures child processes (winget, ollama pull) are killed when the
        // launcher exits — not just on timeout. Without this, closing the
        // window leaves orphaned downloads consuming resources indefinitely.
        Runtime.getRuntime().addShutdownHook(Thread.ofVirtual()
                .name("cleanup")
                .unstarted(envSetup::killActiveProcess));

        Thread.ofVirtual().name("update-thread").start(() -> {
            try {
                runUpdatePhase(updateClient, window, appDir, firstRun, i18n);
                runEnvironmentPhase(envSetup, updateClient, window, appDir, i18n);
                runLaunchPhase(appDir, window, args, i18n);
            } catch (Throwable e) {
                handleFatalError(appDir, window, e);
            }
        });
    }

    // =====================================================================
    // Pipeline Phases
    // =====================================================================

    /**
     * Checks for updates and downloads them if available. On network failure,
     * falls back to the cached version — unless this is the first run, in
     * which case there is nothing to fall back to.
     */
    private static void runUpdatePhase(TinyUpdateClient client, LauncherWindow window,
            Path appDir, boolean showWindow, LauncherI18n i18n) {
        try {
            log(appDir, "Starting update check");
            if (showWindow) {
                SwingUtilities.invokeLater(() -> window.setVisible(true));
            }

            String[] lastPhase = {""};

            boolean updated = client.update(progress -> {
                if (!window.isVisible() && progress.progressRatio() >= 0 && progress.progressRatio() < 1.0) {
                    SwingUtilities.invokeLater(() -> window.setVisible(true));
                }

                String phase = progress.phase();

                // Format: "Translated Phase (2/5)"
                String label = i18n.get(phase);
                if (progress.step() > 0 && progress.totalSteps() > 0) {
                    label += " (" + progress.step() + "/" + progress.totalSteps() + ")";
                }

                if (!label.equals(lastPhase[0])) {
                    log(appDir, "[update] " + label);
                    lastPhase[0] = label;
                }

                window.setStatus(label);
                window.setProgress(progress.progressRatio());
                window.setSpeed(progress.speedBytesPerSec());
            });

            log(appDir, updated
                    ? "Updated to " + client.currentVersion()
                    : "Already up to date: " + client.currentVersion());

        } catch (Exception e) {
            log(appDir, "Update check failed: " + e.getMessage());
            logStackTrace(appDir, e);
            if (client.currentVersion() != null) {
                window.setStatus(i18n.get("Update check failed"));
                sleep(2000);
            } else {
                throw new RuntimeException(
                        "First run requires network — no cached version available", e);
            }
        }
    }

    /**
     * Runs the environment setup script (installs/updates ollama, pulls models).
     * Model downloads are bundled under a single "Installing AI models" phase
     * with its own step counter entry. Logs periodically to avoid console spam.
     */
    private static void runEnvironmentPhase(EnvironmentSetup setup, TinyUpdateClient client,
            LauncherWindow window, Path appDir, LauncherI18n i18n)
            throws IOException, InterruptedException {

        // AI models = next step after the download steps
        int envStep = client.lastDownloadStepCount() + 1;
        int totalSteps = envStep; // downloads + this env step

        // Periodic logging — ollama emits many lines per second
        long[] logTracker = {0}; // [0]=lastLogTime
        // Speed tracking from ollama's size output
        long[] speedTracker = {0, 0}; // [0]=lastBytes, [1]=lastTime
        // Track last logged message to log transitions immediately
        String[] lastLoggedPhase = {""};

        boolean success = setup.run((phase, detail) -> {
            long now = System.currentTimeMillis();

            // Log transitions immediately, everything else periodically
            String logKey = phase + (detail != null && detail.contains("%") ? "" : detail);
            boolean isTransition = !logKey.equals(lastLoggedPhase[0]);
            if (isTransition || now - logTracker[0] >= 2000) {
                log(appDir, "[setup] " + phase + (detail != null ? " — " + detail : ""));
                logTracker[0] = now;
                lastLoggedPhase[0] = logKey;
            }

            boolean isWork = phase.startsWith("Pulling")
                    || (detail != null && detail.contains("install") && !detail.contains("already installed"));
            if (!window.isVisible() && isWork) {
                SwingUtilities.invokeLater(() -> window.setVisible(true));
            }

            // Bundle all model pulls under "Installing AI models (N/N)"
            if (phase.startsWith("Pulling")) {
                String label = i18n.get("Installing AI models")
                        + " (" + envStep + "/" + totalSteps + ")";
                window.setStatus(label);
            } else {
                window.setStatus(i18n.get(phase));
            }

            // Drive progress bar + speed from percentage in detail
            if (detail != null && !detail.isEmpty() && Character.isDigit(detail.charAt(0))) {
                int pctIdx = detail.indexOf('%');
                if (pctIdx > 0) {
                    try {
                        int pct = Integer.parseInt(detail.substring(0, pctIdx).strip());
                        window.setProgress(pct / 100.0);

                        int dashIdx = detail.indexOf('—');
                        if (dashIdx > 0) {
                            long currentBytes = parseByteSize(detail.substring(dashIdx + 1).strip().split("/")[0].strip());
                            if (speedTracker[1] > 0 && now - speedTracker[1] >= 500) {
                                long speed = ((currentBytes - speedTracker[0]) * 1000) / (now - speedTracker[1]);
                                if (speed >= 0) window.setSpeed(speed);
                            }
                            speedTracker[0] = currentBytes;
                            speedTracker[1] = now;
                        }
                        return;
                    } catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) {
                    }
                }
            }
            if (phase.startsWith("Pulling")) {
                window.setProgress(-1);
            }
        });

        window.setSpeed(-1);

        if (!success) {
            log(appDir, "Environment setup returned non-zero — proceeding anyway");
            window.setStatus(i18n.get("Setup completed with warnings"));
            sleep(2000);
        }
    }

    /**
     * Parses a human-readable byte size like "739 MB" or "3.3 GB"
     * into raw bytes for speed calculation.
     */
    private static long parseByteSize(String sizeStr) {
        String normalized = sizeStr.toUpperCase().replaceAll("\\s+", "");
        double val = Double.parseDouble(normalized.replaceAll("[^0-9.]", ""));
        if (normalized.endsWith("GB")) return (long) (val * 1_000_000_000L);
        if (normalized.endsWith("MB")) return (long) (val * 1_000_000L);
        if (normalized.endsWith("KB")) return (long) (val * 1_000L);
        return (long) val;
    }

    /**
     * Spawns the application process and exits the launcher. When the window
     * was never shown (everything cached, no setup work), the launch is silent
     * — no delay, no visible feedback. When visible, a brief delay ensures
     * the user sees "Launching..." before the window disappears.
     */
    private static void runLaunchPhase(Path appDir, LauncherWindow window, String[] args,
            LauncherI18n i18n) throws IOException {
        log(appDir, "Launching application");

        // Only show launch status when the user already sees the window.
        // Flashing it for a cached no-op start is disruptive.
        boolean wasVisible = window.isVisible();
        if (wasVisible) {
            window.setStatus(i18n.get("Launching application"));
            window.setProgress(1.0);
        }

        AppLauncher launcher = new AppLauncher(appDir);
        launcher.launch(args);

        if (wasVisible) {
            sleep(800);
            SwingUtilities.invokeLater(() -> {
                window.setVisible(false);
                window.dispose();
            });
        }

        log(appDir, "Launcher exiting");
        System.exit(0);
    }

    // =====================================================================
    // Error Handling
    // =====================================================================

    /**
     * Shows the launcher window with the error and presents a modal dialog.
     * Ensures the user is never left staring at a blank screen.
     */
    private static void handleFatalError(Path appDir, LauncherWindow window, Throwable e) {
        // Log to file and stderr — the file may not exist yet, so stderr is the fallback
        String msg = "Fatal: " + e.getMessage();
        log(appDir, msg);
        logStackTrace(appDir, e);
        e.printStackTrace(System.err);

        SwingUtilities.invokeLater(() -> {
            window.setVisible(true);
            window.setStatus("Error: " + e.getMessage());
            window.setProgress(0);
        });
        showErrorDialog("Launcher failed", e);
        System.exit(1);
    }

    /** Writes the full stack trace to the session log file. */
    private static void logStackTrace(Path appDir, Throwable e) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            sb.append(t.getClass().getName()).append(": ").append(t.getMessage()).append('\n');
            for (StackTraceElement frame : t.getStackTrace()) {
                sb.append("  at ").append(frame).append('\n');
            }
        }
        log(appDir, sb.toString());
    }

    /**
     * Presents a Swing error dialog with the exception's stack trace, truncated
     * to 500 characters to avoid overflowing the dialog bounds.
     */
    private static void showErrorDialog(String message, Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String trace = sw.toString();
        JOptionPane.showMessageDialog(null,
                message + "\n\n" + trace.substring(0, Math.min(trace.length(), 500)),
                "WSBG Terminal — Error",
                JOptionPane.ERROR_MESSAGE);
    }

    // =====================================================================
    // Infrastructure
    // =====================================================================

    /**
     * Creates required directories. Returns {@code false} and shows an error
     * dialog if creation fails — the launcher cannot continue without a
     * writable data directory.
     */
    private static boolean ensureDirectories(Path appDir) {
        try {
            Files.createDirectories(appDir);
            Files.createDirectories(appDir.resolve("logs/launcher"));
            return true;
        } catch (IOException e) {
            showErrorDialog("Cannot create app directory: " + appDir, e);
            return false;
        }
    }

    /**
     * Archives any previous {@code latest.log}, then sets the session log file
     * to a fresh {@code latest.log}. Old archived sessions beyond
     * {@link #MAX_LOG_FILES} are purged.
     */
    private static void initLogging(Path appDir) {
        Path logsDir = appDir.resolve("logs/launcher");
        archiveLatestLog(logsDir);
        sessionLogFile = logsDir.resolve("latest.log");
        purgeOldLogs(logsDir);
        log(appDir, "--- Launcher session ---");
    }

    /**
     * Archives the previous session's {@code latest.log} by renaming it to a
     * timestamp derived from its last-modified time. Called before each session
     * so that {@code latest.log} always represents the current session alone.
     */
    private static void archiveLatestLog(Path logsDir) {
        Path latest = logsDir.resolve("latest.log");
        if (!Files.exists(latest)) return;
        try {
            String timestamp = Files.getLastModifiedTime(latest).toInstant()
                    .atZone(ZoneId.systemDefault())
                    .format(LOG_FILENAME);
            Files.move(latest, logsDir.resolve(timestamp + ".log"));
        } catch (IOException ignored) {
        }
    }

    /**
     * Deletes the oldest archived {@code .log} files when the count exceeds
     * {@link #MAX_LOG_FILES}. {@code latest.log} is excluded — it is the
     * active session, not an archive.
     */
    private static void purgeOldLogs(Path logsDir) {
        try (Stream<Path> files = Files.list(logsDir)) {
            var logs = files
                    .filter(p -> p.toString().endsWith(".log"))
                    .filter(p -> !p.getFileName().toString().equals("latest.log"))
                    .sorted(Comparator.comparing(Path::getFileName))
                    .toList();

            if (logs.size() >= MAX_LOG_FILES) {
                for (int i = 0; i < logs.size() - MAX_LOG_FILES + 1; i++) {
                    Files.deleteIfExists(logs.get(i));
                }
            }
        } catch (IOException ignored) {
            // Cleanup failure must never prevent the launcher from starting
        }
    }

    /**
     * Appends a timestamped line to the current session's log file. Logging
     * failures are silently ignored — the launcher must never crash because
     * of a log write.
     */
    private static void log(Path appDir, String message) {
        if (sessionLogFile == null)
            return;
        try {
            String timestamp = LocalDateTime.now().format(LOG_TIMESTAMP);
            String line = "[" + timestamp + "] " + message + "\n";
            System.err.print(line);
            Files.writeString(sessionLogFile, line,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Logging failure must never crash the launcher
        }
    }

    /** Interruptible sleep that silently swallows the exception. */
    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
        }
    }
}
