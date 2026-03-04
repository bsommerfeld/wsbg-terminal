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

        TinyUpdateClient updateClient = new TinyUpdateClient(REPO, appDir);

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
                runUpdatePhase(updateClient, window, appDir, firstRun);
                runEnvironmentPhase(envSetup, window, appDir);
                runLaunchPhase(appDir, window, args);
            } catch (Exception e) {
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
            Path appDir, boolean showWindow) {
        try {
            if (showWindow) {
                SwingUtilities.invokeLater(() -> window.setVisible(true));
            }

            boolean updated = client.update(progress -> {
                if (!window.isVisible() && progress.progressRatio() >= 0 && progress.progressRatio() < 1.0) {
                    SwingUtilities.invokeLater(() -> window.setVisible(true));
                }
                window.setStatus(progress.phase());
                window.setDetail(progress.detail());
                window.setProgress(progress.progressRatio());
            });

            log(appDir, updated
                    ? "Updated to " + client.currentVersion()
                    : "Already up to date: " + client.currentVersion());

        } catch (Exception e) {
            log(appDir, "Update check failed: " + e.getMessage());
            if (client.currentVersion() != null) {
                window.setStatus("Update check failed");
                window.setDetail("launching cached version");
                sleep(2000);
            } else {
                throw new RuntimeException(
                        "First run requires network — no cached version available", e);
            }
        }
    }

    /**
     * Runs the environment setup script (installs/updates ollama, pulls models).
     * Non-zero exit is logged but not fatal — the application may still work.
     *
     * <p>
     * The window only becomes visible once the script emits a phase that
     * indicates real work (model pull, install). When everything is already
     * present, the script finishes in under a second and the user never
     * sees the launcher flash.
     */
    private static void runEnvironmentPhase(EnvironmentSetup setup, LauncherWindow window,
            Path appDir) throws IOException, InterruptedException {

        boolean success = setup.run((phase, detail) -> {
            log(appDir, "[setup] " + phase + (detail != null ? " — " + detail : ""));

            // Only show the window when actual work is happening — not for
            // instant "already available" checks that finish in milliseconds.
            // "Pulling" = model downloads, "Installing" = Ollama setup.
            // Ignore "already installed" to prevent the launcher from flashing when no work
            // is needed.
            boolean isWork = phase.startsWith("Pulling")
                    || (detail != null && detail.contains("install") && !detail.contains("already installed"));
            if (!window.isVisible() && isWork) {
                SwingUtilities.invokeLater(() -> window.setVisible(true));
            }

            window.setStatus(phase);
            window.setDetail(detail);

            // Drive the progress bar from percentage in detail (format: "42% — ...")
            if (detail != null && !detail.isEmpty() && Character.isDigit(detail.charAt(0))) {
                int pctIdx = detail.indexOf('%');
                if (pctIdx > 0) {
                    try {
                        int pct = Integer.parseInt(detail.substring(0, pctIdx).strip());
                        window.setProgress(pct / 100.0);
                        return;
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (phase.startsWith("Pulling")) {
                window.setProgress(-1);
            }
        });

        if (!success) {
            log(appDir, "Environment setup returned non-zero — proceeding anyway");
            window.setStatus("Setup completed with warnings");
            window.setDetail(null);
            sleep(2000);
        }
    }

    /**
     * Spawns the application process and exits the launcher. A brief delay
     * ensures the user sees "Launching..." before the window disappears.
     */
    private static void runLaunchPhase(Path appDir, LauncherWindow window, String[] args)
            throws IOException {
        window.setStatus("Launching application...");
        window.setDetail(null);
        window.setProgress(1.0);
        log(appDir, "Launching application");

        AppLauncher launcher = new AppLauncher(appDir);
        launcher.launch(args);

        sleep(800);

        SwingUtilities.invokeLater(() -> {
            window.setVisible(false);
            window.dispose();
        });

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
    private static void handleFatalError(Path appDir, LauncherWindow window, Exception e) {
        log(appDir, "Fatal: " + e.getMessage());
        SwingUtilities.invokeLater(() -> {
            window.setVisible(true);
            window.setStatus("Error: " + e.getMessage());
            window.setDetail(null);
            window.setProgress(0);
        });
        showErrorDialog("Launcher failed", e);
        System.exit(1);
    }

    /**
     * Presents a Swing error dialog with the exception's stack trace, truncated
     * to 500 characters to avoid overflowing the dialog bounds.
     */
    private static void showErrorDialog(String message, Exception e) {
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
     * Creates a new per-session log file named by execution timestamp
     * (e.g. {@code 2026-03-04_21-32-39.log}) and purges old files beyond
     * {@link #MAX_LOG_FILES}. Each launcher invocation gets its own file
     * so sessions never bleed into each other.
     */
    private static void initLogging(Path appDir) {
        String name = LocalDateTime.now().format(LOG_FILENAME) + ".log";
        sessionLogFile = appDir.resolve("logs/launcher/" + name);
        purgeOldLogs(appDir.resolve("logs/launcher"));
        log(appDir, "--- Launcher session ---");
    }

    /**
     * Deletes the oldest {@code .log} files when the count exceeds
     * {@link #MAX_LOG_FILES}. Sorted lexicographically — the timestamp
     * format guarantees chronological order.
     */
    private static void purgeOldLogs(Path logsDir) {
        try (Stream<Path> files = Files.list(logsDir)) {
            var logs = files
                    .filter(p -> p.toString().endsWith(".log"))
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
