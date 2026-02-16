package de.bsommerfeld.tinyupdate.launcher;

import de.bsommerfeld.tinyupdate.api.GitHubRepository;
import de.bsommerfeld.tinyupdate.api.TinyUpdateClient;
import de.bsommerfeld.tinyupdate.api.UpdateProgress;

import javax.swing.*;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Native launcher entry point. Orchestrates the full startup sequence:
 * update → environment setup → application launch.
 *
 * <p>The window is only shown if an update is needed. If the app is up-to-date
 * and environment is ready, the launcher is invisible.
 */
public final class LauncherMain {

    private static final GitHubRepository REPO = GitHubRepository.of("bsommerfeld/wsbg-terminal");

    private LauncherMain() {}

    public static void main(String[] args) {
        Path appDir = StorageResolver.resolve();

        try {
            Files.createDirectories(appDir);
            Files.createDirectories(appDir.resolve("logs"));
        } catch (IOException e) {
            showFatalError("Cannot create app directory: " + appDir, e);
            return;
        }

        initLogging(appDir);
        log(appDir, "Launcher started");

        TinyUpdateClient updateClient = new TinyUpdateClient(REPO, appDir);

        // Only show window if update is needed (version file missing = first run)
        boolean firstRun = updateClient.currentVersion() == null;
        LauncherWindow window = new LauncherWindow();

        Thread updateThread = Thread.ofVirtual().name("update-thread").start(() -> {
            try {
                runUpdatePhase(updateClient, window, appDir, firstRun);
                runEnvironmentPhase(appDir, window);
                runLaunchPhase(appDir, window, args);
            } catch (Exception e) {
                log(appDir, "Fatal: " + e.getMessage());
                SwingUtilities.invokeLater(() -> {
                    window.setVisible(true);
                    window.setStatus("Error: " + e.getMessage());
                    window.setProgress(0);
                });
                showFatalError("Update failed", e);
            }
        });
    }

    private static void runUpdatePhase(TinyUpdateClient client, LauncherWindow window,
                                       Path appDir, boolean showWindow) {
        try {
            // Show window on first run or if update is available
            if (showWindow) {
                SwingUtilities.invokeLater(() -> window.setVisible(true));
            }

            boolean updated = client.update(progress -> {
                // Show window as soon as we know an update exists
                if (!window.isVisible() && progress.progressRatio() >= 0) {
                    SwingUtilities.invokeLater(() -> window.setVisible(true));
                }
                window.setStatus(progress.phase());
                window.setProgress(progress.progressRatio());
            });

            if (updated) {
                log(appDir, "Updated to " + client.currentVersion());
            } else {
                log(appDir, "Already up to date: " + client.currentVersion());
            }
        } catch (Exception e) {
            log(appDir, "Update check failed: " + e.getMessage());
            // Non-fatal if we have a previous version installed — launch anyway
            if (client.currentVersion() != null) {
                window.setStatus("Update check failed — launching cached version");
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
            } else {
                throw new RuntimeException("First run requires network — no cached version available", e);
            }
        }
    }

    private static void runEnvironmentPhase(Path appDir, LauncherWindow window) throws IOException, InterruptedException {
        window.setStatus("Checking environment...");
        window.setProgress(-1);
        SwingUtilities.invokeLater(() -> window.setVisible(true));

        EnvironmentSetup setup = new EnvironmentSetup(appDir);
        boolean success = setup.run(line -> {
            log(appDir, "[setup] " + line);
            window.setStatus(line);
        });

        if (!success) {
            log(appDir, "Environment setup returned non-zero — proceeding anyway");
            window.setStatus("Setup completed with warnings");
            Thread.sleep(2000);
        }
    }

    private static void runLaunchPhase(Path appDir, LauncherWindow window, String[] args) throws IOException {
        window.setStatus("Launching application...");
        window.setProgress(1.0);
        log(appDir, "Launching application");

        AppLauncher launcher = new AppLauncher(appDir);
        launcher.launch(args);

        // Brief delay so the user sees "Launching..." before the window disappears
        try { Thread.sleep(800); } catch (InterruptedException ignored) {}

        SwingUtilities.invokeLater(() -> {
            window.setVisible(false);
            window.dispose();
        });

        log(appDir, "Launcher exiting");
        System.exit(0);
    }

    private static void showFatalError(String message, Exception e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        JOptionPane.showMessageDialog(null,
                message + "\n\n" + sw.toString().substring(0, Math.min(sw.toString().length(), 500)),
                "WSBG Terminal — Error",
                JOptionPane.ERROR_MESSAGE
        );
    }

    private static void initLogging(Path appDir) {
        log(appDir, "--- Launcher session ---");
    }

    private static void log(Path appDir, String message) {
        try {
            Path logFile = appDir.resolve("logs/launcher.log");
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String line = "[" + timestamp + "] " + message + "\n";
            Files.writeString(logFile, line,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // Logging failure should never crash the launcher
        }
    }
}
