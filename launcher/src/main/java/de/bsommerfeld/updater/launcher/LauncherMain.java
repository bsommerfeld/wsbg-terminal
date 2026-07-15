package de.bsommerfeld.updater.launcher;

import de.bsommerfeld.updater.api.ConnectivityProbe;
import de.bsommerfeld.updater.api.GitHubRepository;
import de.bsommerfeld.updater.api.TinyUpdateClient;
import de.bsommerfeld.updater.api.UpdateResult;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
 * visible error dialog before exiting — see {@link LauncherDialogs}.
 *
 * <h3>Thread model</h3>
 * The update/setup/launch pipeline runs on a virtual thread to keep the
 * Swing EDT responsive. All window updates go through {@link SwingUtilities}.
 */
public final class LauncherMain {

    private static final GitHubRepository REPO = GitHubRepository.of("bsommerfeld/wsbg-terminal");

    /**
     * Three extra steps after the update pipeline: the Ollama platform install,
     * the model downloads, and the browser (JCEF) runtime — the last is a
     * ~150 MB download that deserves its own step instead of hiding under
     * "Setting up environment". Fonts are a fast tail with no numbered step.
     * All are driven by the setup script (setup.sh/.ps1). Folded into the
     * update pipeline's step total so the "(n/total)" label stays consistent.
     */
    private static final int ENVIRONMENT_STEPS = 3;

    private LauncherMain() {
    }

    public static void main(String[] args) {
        // macOS reads this for the menu-bar app name and the dock tooltip.
        // Must be set before any AWT/JavaFX class touches the system.
        System.setProperty("apple.awt.application.name", "WSBG Terminal");

        Path appDir = StorageResolver.resolve();

        if (!ensureDirectories(appDir))
            return;

        SessionLog log = new SessionLog(appDir);
        log.log("Launcher started");

        // If a terminal is already running, hand control off and exit
        // immediately — second double-click on the dock icon should
        // raise the existing window, not run a parallel install flow.
        //
        // Best-effort: any failure to detect (timeout, future protocol
        // change, port collision) falls through to the normal install
        // path. The native HULL can't be auto-updated (only its jar, via
        // StagedLauncher) and pre-self-update launchers stay in the wild,
        // so the fallback matters whenever we change the contract.
        if (TerminalRaiser.raise()) {
            log.log("Existing terminal detected — raised it, launcher exiting.");
            System.exit(0);
        }

        // Stage-loader: if the OTA-synced launcher jar in <appDir>/launcher/
        // is strictly newer than this hull's embedded one, hand the whole
        // startup over to it and exit — the native hull stays a dumb
        // bootstrap that never needs reinstalling for jar-level changes.
        // The staged child skips this block (env guard) and otherwise runs
        // this exact same pipeline. Every failure path continues embedded.
        if (StagedLauncher.handoff(appDir, log, args)) {
            System.exit(0);
        }

        LauncherI18n i18n = new LauncherI18n(appDir);
        log.log("Language: " + i18n.language());

        // Auto-update is opt-out (config.toml: user.auto-update, default true).
        // The terminal's in-app "update now" button relaunches us with
        // --force-update so a one-off update still happens while auto-update is
        // off. The flag is stripped before forwarding the rest to the terminal.
        LaunchArgs launchArgs = LaunchArgs.parse(args);
        final boolean forceUpdate = launchArgs.forceUpdate();
        final boolean autoUpdate = LaunchArgs.configAutoUpdate(appDir);
        final String[] forwardArgs = launchArgs.forwardArgs();
        log.log("auto-update=" + autoUpdate + (forceUpdate ? " (forced by in-app update)" : ""));

        TinyUpdateClient updateClient = new TinyUpdateClient(REPO, appDir);

        boolean firstRun = updateClient.currentVersion() == null;
        LauncherWindow window = new LauncherWindow();
        EnvironmentSetup envSetup = new EnvironmentSetup(appDir);

        // Hardware check + model choice (backend only, no UI yet): probes the
        // machine, logs + persists the recommendation for a future model-choice
        // UI, and resolves the tag the setup script installs — the user's
        // config.toml choice (agent.model-tag) or the managed default.
        ModelSelection.Result modelChoice = ModelSelection.resolve(appDir, log);
        envSetup.setReasoningModelTag(modelChoice.effectiveTag());

        // Ensures child processes (winget, ollama pull) are killed when the
        // launcher exits — not just on timeout. Without this, closing the
        // window leaves orphaned downloads consuming resources indefinitely.
        Runtime.getRuntime().addShutdownHook(Thread.ofVirtual()
                .name("cleanup")
                .unstarted(envSetup::killActiveProcess));

        Thread.ofVirtual().name("update-thread").start(() -> {
            try {
                int downloadSteps = runUpdatePhase(updateClient, window, log, firstRun, i18n,
                        autoUpdate, forceUpdate, ENVIRONMENT_STEPS);
                // Launcher self-update rides the same phase, quietly (log-only,
                // no window steps): a newly staged jar takes over on the NEXT
                // start, so there is nothing to show now. Same auto-update
                // gate + --force-update override as the terminal update above.
                StagedLauncher.sync(REPO, appDir, log, autoUpdate, forceUpdate);
                runEnvironmentPhase(envSetup, window, log, i18n, downloadSteps);
                runLaunchPhase(appDir, window, log, forwardArgs, i18n);
            } catch (Throwable e) {
                LauncherDialogs.handleFatalError(appDir, window, log, e);
            }
        });
    }

    // =====================================================================
    // Pipeline Phases
    // =====================================================================

    /**
     * Checks for updates and downloads them if available. On any failure —
     * no internet, repository/release missing, or a mid-download error — the
     * launcher falls back to the cached install and starts it, so an offline
     * user is never locked out of an already-installed version. The only hard
     * stop is a first run with nothing cached to fall back to.
     *
     * <p>
     * An upfront connectivity probe handles the common offline case cleanly:
     * it lets us skip straight to the cached version instead of waiting out a
     * 30 s connect timeout. The {@code try/catch} below remains the safety net
     * for everything the probe can't foresee (repo 404, no published release,
     * a connection that drops mid-update).
     */
    private static int runUpdatePhase(TinyUpdateClient client, LauncherWindow window,
            SessionLog log, boolean showWindow, LauncherI18n i18n,
            boolean autoUpdate, boolean forceUpdate, int extraSteps) {
        boolean hasCachedVersion = client.currentVersion() != null;

        // Auto-update opt-out: with a cached version to fall back on and no
        // explicit in-app "update now", skip the check entirely and launch what
        // is installed. (A first run with nothing cached must always update.)
        if (!autoUpdate && !forceUpdate && hasCachedVersion) {
            log.log("Auto-update disabled — skipping update, launching cached version "
                    + client.currentVersion());
            return 0;
        }

        if (!ConnectivityProbe.isOnline()) {
            if (hasCachedVersion) {
                log.log("No internet connection — skipping update, launching cached version "
                        + client.currentVersion());
                return 0;
            }
            // First run with no network: nothing is installed yet, so there is
            // genuinely nothing to launch. Fail loudly with a clear message.
            throw new IllegalStateException(
                    "No internet connection and no installed version available. "
                    + "Connect to the internet and restart to complete the first-time setup.");
        }

        try {
            log.log("Starting update check");
            if (showWindow) {
                SwingUtilities.invokeLater(() -> window.setVisible(true));
            }

            String[] lastPhase = {""};

            UpdateResult result = client.update(progress -> {
                if (!window.isVisible() && progress.progressRatio() >= 0 && progress.progressRatio() < 1.0) {
                    SwingUtilities.invokeLater(() -> window.setVisible(true));
                }

                String phase = progress.phase();

                // Format: "Translated Phase (2/5)"
                String label = i18n.get(phase);
                if (progress.step() > 0 && progress.totalSteps() > 0) {
                    label += " (" + progress.step() + "/" + progress.totalSteps() + ")";
                }

                // Snap indicator to dot on phase transitions — prevents stale
                // fill from flashing during the next phase's expand animation.
                if (!label.equals(lastPhase[0])) {
                    log.log("[update] " + label);
                    lastPhase[0] = label;
                    window.resetProgress();
                    window.setSpeed(-1);
                }

                window.setStatus(label);
                if (progress.progressRatio() >= 0) {
                    window.setProgress(progress.progressRatio());
                }
                window.setSpeed(progress.speedBytesPerSec());
            }, extraSteps);

            log.log(result.updated()
                    ? "Updated to " + result.version()
                    : "Already up to date: " + result.version());

            return result.downloadSteps();

        } catch (Exception e) {
            // Covers everything past the connectivity probe: repository not
            // found, no published release, malformed release JSON, or a
            // connection that dropped mid-update. In all of these the right
            // move is to run whatever is already installed.
            log.log("Update check failed: " + e.getMessage());
            log.logStackTrace(e);
            if (client.currentVersion() != null) {
                window.setStatus(i18n.get("Update check failed"));
                sleep(2000);
                // Nothing downloaded — the environment phase numbers its steps
                // from zero, exactly as before.
                return 0;
            } else {
                throw new IllegalStateException(
                        "Update check failed and no installed version is available to launch "
                        + "(first run needs a reachable release): " + e.getMessage(), e);
            }
        }
    }

    /**
     * Runs the environment setup script (installs/updates ollama, pulls models,
     * fetches the browser runtime). The stateful translation of the script's
     * {@code (phase, detail)} events into window updates lives in
     * {@link SetupProgressAdapter}; a non-zero exit is a warning, not a stop.
     */
    private static void runEnvironmentPhase(EnvironmentSetup setup,
            LauncherWindow window, SessionLog log, LauncherI18n i18n, int downloadStepCount)
            throws IOException, InterruptedException {

        // Snap to dot before environment setup — clean transition from update phase
        window.resetProgress();
        window.setSpeed(-1);

        // After the update download steps, the Ollama install, the model pulls,
        // and the browser (JCEF) runtime slot in as the final three numbered
        // steps. Fonts run after as a quick, unnumbered tail.
        SetupProgressAdapter adapter =
                new SetupProgressAdapter(window, i18n, log, downloadStepCount);

        boolean success = setup.run(adapter);

        window.setSpeed(-1);

        if (!success) {
            log.log("Environment setup returned non-zero — proceeding anyway");
            window.setStatus(i18n.get("Setup completed with warnings"));
            sleep(2000);
        }
    }

    /**
     * Spawns the application process and exits the launcher. When the window
     * was never shown (everything cached, no setup work), the launch is silent
     * — no delay, no visible feedback. When visible, a brief delay ensures
     * the user sees "Launching..." before the window disappears.
     */
    private static void runLaunchPhase(Path appDir, LauncherWindow window, SessionLog log,
            String[] args, LauncherI18n i18n) throws IOException {
        log.log("Launching application");

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

        log.log("Launcher exiting");
        System.exit(0);
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
            LauncherI18n i18n = new LauncherI18n(appDir);
            LauncherDialogs.showErrorDialog(i18n.get("Cannot create app directory") + ": " + appDir,
                    "WSBG Terminal - " + i18n.get("Error"), e);
            return false;
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
