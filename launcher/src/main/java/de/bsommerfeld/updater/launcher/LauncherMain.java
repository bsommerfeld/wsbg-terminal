package de.bsommerfeld.updater.launcher;

import de.bsommerfeld.updater.catalog.ModelCatalog;

import de.bsommerfeld.updater.api.ConnectivityProbe;
import de.bsommerfeld.updater.api.GitHubRepository;
import de.bsommerfeld.updater.api.ReleaseChannel;
import de.bsommerfeld.updater.api.TinyUpdateClient;
import de.bsommerfeld.updater.api.UpdateResult;
import de.bsommerfeld.updater.update.VersionFile;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Native launcher entry point. Orchestrates the full startup sequence:
 * <strong>update → environment setup → application launch</strong>.
 *
 * <h3>An already-running terminal</h3>
 * There is never a second terminal. A start that finds one raises its window
 * immediately and then asks a single question: is there an update to apply? If
 * not, the launcher exits and leaves the app untouched. If there is, the app
 * has to close — the update replaces the very files it runs off — so the
 * launcher waits for it to be gone and then runs the normal pipeline, which
 * ends by starting the fresh build. See {@link #runningTerminalGate}.
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

    /**
     * The numbered setup steps for this run. With an external AI endpoint the
     * Ollama install and the model pulls do not happen, so only the browser
     * runtime is left - counting all three would leave the label stuck at
     * "(3/5)" while the run is already finished.
     */
    private static int environmentSteps(boolean managedAi) {
        return managedAi ? ENVIRONMENT_STEPS : 1;
    }

    private LauncherMain() {
    }

    public static void main(String[] args) {
        // macOS reads this for the menu-bar app name and the dock tooltip.
        // Must be set before any AWT/JavaFX class touches the system.
        System.setProperty("apple.awt.application.name", "WSBG Terminal");

        // No dock tile for the launcher. It spawns the terminal and exits
        // within milliseconds, but LaunchServices keeps an exited app
        // registered as long as processes it spawned are alive
        // ("exited-with-subordinates") — so its tile stayed in the dock next
        // to the terminal's own, both with our icon, for the whole session.
        // A tile that never exists cannot linger. The window is unaffected
        // (verified: type="UIElement", window still shown) and stays reachable
        // via always-on-top, since an accessory app has no tile to click.
        // Other platforms ignore the key.
        System.setProperty("apple.awt.UIElement", "true");

        // Render the launcher via OpenGL instead of Metal on macOS. Java2D's
        // Metal pipeline presents the per-pixel-translucent splash window's
        // surface empty under animation load — the whole window blinks away
        // to the desktop (visually confirmed during the model-choice morph;
        // the same pipeline also carries the known flusher SIGSEGV). Must be
        // set before AWT initializes; other platforms ignore the key.
        System.setProperty("sun.java2d.metal", "false");

        Path appDir = StorageResolver.resolve();

        if (!ensureDirectories(appDir))
            return;

        SessionLog log = new SessionLog(appDir);
        log.log("Launcher started");

        // If a terminal is already running, raise it right now — a second
        // double-click on the dock icon must bring the existing window
        // forward instantly, never start a parallel install flow. Whether
        // we may then close it again for an update is decided later, once
        // the update check has an answer (see runningTerminalGate).
        //
        // Best-effort: any failure to detect (timeout, future protocol
        // change, port collision) falls through to the normal install
        // path. The native HULL can't be auto-updated (only its jar, via
        // StagedLauncher) and pre-self-update launchers stay in the wild,
        // so the fallback matters whenever we change the contract.
        final boolean terminalRunning = TerminalRaiser.raise();
        if (terminalRunning) {
            log.log("Existing terminal detected — raised its window.");
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

        // Which releases this install accepts (config.toml:
        // user.experimental-updates). Unanswered means stable — nothing ever
        // lands on a pre-release without being asked for it. The update client
        // is built inside the pipeline below, once the answer is final.
        ChannelSelection.Result channelChoice = ChannelSelection.resolve(appDir, log);

        // Read straight off version.txt: the channel decides what we may fetch,
        // never what is already installed, so this predates the client.
        boolean firstRun = new VersionFile(appDir).read() == null;
        LauncherWindow window = new LauncherWindow();
        EnvironmentSetup envSetup = new EnvironmentSetup(appDir);

        // Hardware check + model choice: probes the machine, persists the
        // recommendation, and resolves the tag the setup script installs — the
        // user's config.toml choice (agent.model-tag) or the managed default.
        ModelSelection.Result modelChoice = ModelSelection.resolve(appDir, log);
        envSetup.setReasoningModelTag(modelChoice.effectiveTag());
        envSetup.setManagedAi(modelChoice.managedAi());

        // Ensures child processes (winget, ollama pull) are killed when the
        // launcher exits — not just on timeout. Without this, closing the
        // window leaves orphaned downloads consuming resources indefinitely.
        Runtime.getRuntime().addShutdownHook(Thread.ofVirtual()
                .name("cleanup")
                .unstarted(envSetup::killActiveProcess));

        Thread.ofVirtual().name("update-thread").start(() -> {
            try {
                // No language on record yet — a fresh install, or a launcher
                // that was closed before the choice was made. Ask first, so
                // everything after it (including the model choice) speaks the
                // user's language. One OK persists the key, so this shows
                // exactly once.
                if (!i18n.explicit()) {
                    String language = runLanguageChoicePhase(window, log);
                    ConfigWriter.write(appDir, "[user]", "language", language, log);
                    i18n.switchTo(language);
                }

                // No answer on record for the update channel yet. Ask before
                // anything talks to GitHub — the very next step already checks
                // for updates, and it has to check the right channel.
                ReleaseChannel channel = channelChoice.channel();
                if (!channelChoice.userChosen()) {
                    String answer = runChannelChoicePhase(window, i18n, log);
                    ConfigWriter.write(appDir, "[user]", ChannelSelection.CONFIG_KEY, answer, log);
                    channel = ChannelSelection.channelOf(answer);
                }
                TinyUpdateClient updateClient = new TinyUpdateClient(REPO, appDir, channel);

                // A terminal is already up: either there is nothing to apply
                // (then we are done — its window is already in front) or we
                // close it first and run the normal pipeline over its install.
                if (terminalRunning
                        && !runningTerminalGate(updateClient, window, log, i18n,
                                appDir, channel, autoUpdate, forceUpdate)) {
                    System.exit(0);
                }

                // No explicit model choice on record yet: morph the window into
                // the choice list and wait for the user's pick. One OK persists
                // the key, so this shows exactly once per install. With an
                // external endpoint there is nothing to choose - no model of
                // ours goes on this machine - so the screen never appears.
                // Whether an AI runtime gets installed at all. Starts as what
                // the config said and can still change HERE: the model screen's
                // advanced sheet may answer with an external endpoint instead
                // of a tier, and everything after this point - the step count,
                // the setup script's env - has to follow that answer.
                boolean managedAi = modelChoice.managedAi();
                if (managedAi && !modelChoice.userChosen()) {
                    String chosen = runModelChoicePhase(window, i18n, modelChoice, log);
                    AdvancedEndpointSheet.Endpoint endpoint = window.chosenEndpoint();
                    if (endpoint != null) {
                        // The sheet wins over the stack: an endpoint means no
                        // model of ours belongs on this machine at all.
                        EndpointConfigWriter.write(appDir, endpoint, log);
                        envSetup.setManagedAi(false);
                        managedAi = false;
                        log.log("External AI endpoint configured in the launcher: "
                                + endpoint.url() + " (" + endpoint.model() + ")");
                    } else {
                        ModelConfigWriter.write(appDir, chosen, log);
                        envSetup.setReasoningModelTag(chosen);
                    }
                }
                int downloadSteps = runUpdatePhase(updateClient, window, log, firstRun, i18n,
                        autoUpdate, forceUpdate, environmentSteps(managedAi));
                // Launcher self-update rides the same phase, quietly (log-only,
                // no window steps): a newly staged jar takes over on the NEXT
                // start, so there is nothing to show now. Same auto-update
                // gate + --force-update override as the terminal update above.
                StagedLauncher.sync(REPO, appDir, log, channel, autoUpdate, forceUpdate);
                runEnvironmentPhase(envSetup, window, log, i18n, downloadSteps, managedAi);
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
     * How long we give a running terminal to shut down before giving up on the
     * update. Generous on purpose: its teardown stops the spawned Ollama, the
     * repositories and Chromium's helper processes, which on a busy machine is
     * seconds, not milliseconds.
     */
    private static final Duration QUIT_TIMEOUT = Duration.ofSeconds(45);

    /**
     * The gate for a start with a terminal already running. Its window was
     * raised the moment we detected it, so from here there are exactly two
     * outcomes:
     *
     * <ul>
     *   <li><strong>Nothing to apply</strong> — we quietly stage a launcher
     *       self-update (that one takes effect on the next start and never
     *       disturbs a running app) and leave the terminal alone.</li>
     *   <li><strong>An update is pending</strong> — the files we would replace
     *       are the ones that terminal is running off, so it has to go first:
     *       we ask it to close, wait for the process to be verifiably gone, and
     *       then run the normal pipeline, which ends by starting the fresh
     *       build. A terminal that refuses to die aborts the whole thing — a
     *       half-applied update under a live app is the one outcome worth
     *       avoiding at any price.</li>
     * </ul>
     *
     * <p>A failed check (offline, GitHub hiccup) counts as "nothing pending":
     * never close a running app over a network glitch.
     *
     * @return {@code true} if the pipeline should continue, {@code false} if
     *         the launcher is done and should exit
     */
    private static boolean runningTerminalGate(TinyUpdateClient updateClient, LauncherWindow window,
            SessionLog log, LauncherI18n i18n, Path appDir, ReleaseChannel channel,
            boolean autoUpdate, boolean forceUpdate) {

        if (!isUpdatePending(updateClient, log, autoUpdate, forceUpdate)) {
            log.log("Nothing to apply — leaving the running terminal alone.");
            StagedLauncher.sync(REPO, appDir, log, channel, autoUpdate, forceUpdate);
            log.log("Launcher exiting, terminal keeps running.");
            return false;
        }

        log.log("Update pending — closing the running terminal to apply it.");
        SwingUtilities.invokeLater(() -> {
            window.setStatus(i18n.get("Closing running terminal"));
            window.setVisible(true);
        });

        if (!TerminalRaiser.requestQuit(QUIT_TIMEOUT, log)) {
            log.log("Running terminal did not close — update postponed to the next start.");
            SwingUtilities.invokeLater(() -> {
                window.setVisible(false);
                window.dispose();
            });
            return false;
        }

        log.log("Running terminal closed — applying the update.");
        return true;
    }

    /**
     * Read-only "would the update phase change anything?" — the manifest hash
     * diff, without downloading an archive. Mirrors the update phase's own
     * gates so the answer matches what would actually happen: auto-update off
     * (and no in-app force) means nothing is pending, and offline means nothing
     * is pending either.
     */
    private static boolean isUpdatePending(TinyUpdateClient client, SessionLog log,
            boolean autoUpdate, boolean forceUpdate) {
        if (!autoUpdate && !forceUpdate) {
            log.log("Auto-update disabled — no update check against the running terminal.");
            return false;
        }
        if (!ConnectivityProbe.isOnline()) {
            log.log("No internet connection — no update check against the running terminal.");
            return false;
        }
        try {
            return client.isUpdatePending();
        } catch (Exception e) {
            log.log("Update check failed (" + e.getMessage() + ") — treating as nothing pending.");
            return false;
        }
    }

    /**
     * The app's primary language — preselected in the language choice, and
     * what an unanswered choice keeps producing until one is made.
     */
    private static final String DEFAULT_LANGUAGE = "de";

    /**
     * Blocks until the user has picked a display language. The screen labels
     * every option in its OWN language — the one screen that cannot assume a
     * language is already known — and German arrives preselected, so
     * confirming the default is a single click.
     */
    private static String runLanguageChoicePhase(LauncherWindow window, SessionLog log)
            throws Exception {
        java.util.List<LanguageChoicePanel.Row> rows = new java.util.ArrayList<>();
        for (String code : LauncherI18n.LANGUAGES) {
            rows.add(new LanguageChoicePanel.Row(code,
                    java.util.Locale.forLanguageTag(code)
                            .getDisplayLanguage(java.util.Locale.forLanguageTag(code)),
                    LauncherI18n.translate("Choose your language", code),
                    LauncherI18n.translate("You can change this later in the settings", code),
                    LauncherI18n.translate("OK", code)));
        }

        log.log("Language choice UI shown (no explicit choice on record)");
        String chosen = window.showLanguageChoice(rows, DEFAULT_LANGUAGE).get();
        log.log("Language choice confirmed: " + chosen);
        return chosen;
    }

    /**
     * Blocks until the user has decided which releases this install accepts.
     * Both rows name the thing plainly and carry the room's own reading of the
     * decision beside it; the cautious answer arrives preselected, so taking
     * the risky one is always a deliberate act.
     */
    private static String runChannelChoicePhase(LauncherWindow window, LauncherI18n i18n,
            SessionLog log) throws Exception {
        java.util.List<ChannelChoicePanel.Row> rows = java.util.List.of(
                new ChannelChoicePanel.Row(ChannelSelection.NO,
                        i18n.get("Stable"), i18n.get("Risk management")),
                new ChannelChoicePanel.Row(ChannelSelection.YES,
                        i18n.get("Experimental"), i18n.get("100x leverage")));

        ChannelChoicePanel.Labels labels = new ChannelChoicePanel.Labels(
                i18n.get("Which updates do you want?"),
                i18n.get("You can change this later in the settings"),
                i18n.get("OK"));

        log.log("Channel choice UI shown (no answer on record)");
        String chosen = window.showChannelChoice(rows, ChannelSelection.NO, labels).get();
        log.log("Channel choice confirmed: " + chosen);
        return chosen;
    }

    /**
     * Blocks until the user has picked a model tier in the morphing choice
     * list. The tiers are translated for a non-technical audience: the model's
     * name plus quality and speed on a 0–10 scale and a plain-language fit
     * verdict — never RAM figures or parameter counts. The name is the tier's
     * own ({@link ModelCatalog#displayName()}), not the Ollama tag, and not
     * translated: it is a product name. The hardware recommendation arrives
     * preselected, so confirming the default is a single click.
     */
    private static String runModelChoicePhase(LauncherWindow window, LauncherI18n i18n,
            ModelSelection.Result modelChoice, SessionLog log) throws Exception {
        java.util.List<ModelChoicePanel.Row> rows = new java.util.ArrayList<>();
        for (ModelCatalog tier : ModelCatalog.values()) {
            String tag = tier.tagFor(modelChoice.appleSilicon());
            boolean recommended = tag.equals(modelChoice.recommendedTag());
            ModelCatalog.Fit fit = modelChoice.totalRamGb() <= 0
                    ? ModelCatalog.Fit.COMFORTABLE
                    : tier.fitFor(modelChoice.totalRamGb());
            String verdict = i18n.get(switch (fit) {
                case COMFORTABLE -> recommended ? "Recommended" : "Good fit";
                case TIGHT -> "Tight fit";
                case TOO_LARGE -> "Too large";
            });
            String size = String.format(
                    "de".equals(i18n.language()) ? java.util.Locale.GERMAN : java.util.Locale.ROOT,
                    "%.1f GB", tier.diskGbFor(modelChoice.appleSilicon()));
            // The MLX chip derives from the EFFECTIVE tag, never a second
            // list: only tags tagFor() actually suffixed carry it, so tiers
            // without an MLX twin (Granite) stay unmarked on Apple Silicon
            // and no card is ever marked on Windows/Linux.
            rows.add(new ModelChoicePanel.Row(tag, tier.displayName(),
                    tier.quality(), tier.speed(), size, fit, recommended, verdict,
                    tag.endsWith("-mlx")));
        }

        ModelChoicePanel.Labels labels = new ModelChoicePanel.Labels(
                i18n.get("Choose your AI model"),
                i18n.get("Quality"),
                i18n.get("Speed"),
                i18n.get("OK"),
                i18n.get("Without MLX"),
                i18n.get("Advanced"));

        AdvancedEndpointSheet.Labels advanced = new AdvancedEndpointSheet.Labels(
                i18n.get("Your own AI server"),
                i18n.get("Address"),
                i18n.get("Model"),
                i18n.get("Key"),
                i18n.get("Test"),
                i18n.get("Asking..."),
                i18n.get("Answers"),
                i18n.get("No answer"),
                i18n.get("Nothing is downloaded then"),
                i18n.get("Not suited to hosted providers. Costs can vary widely."));

        log.log("Model choice UI shown (no explicit choice on record)");
        String chosen = window.showModelChoice(rows, modelChoice.recommendedTag(), labels,
                advanced).get();
        log.log("Model choice confirmed: " + chosen);
        return chosen;
    }

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
            LauncherWindow window, SessionLog log, LauncherI18n i18n, int downloadStepCount,
            boolean managedAi)
            throws IOException, InterruptedException {

        // Snap to dot before environment setup — clean transition from update phase
        window.resetProgress();
        window.setSpeed(-1);

        // After the update download steps, the Ollama install, the model pulls,
        // and the browser (JCEF) runtime slot in as the final three numbered
        // steps. Fonts run after as a quick, unnumbered tail.
        SetupProgressAdapter adapter =
                new SetupProgressAdapter(window, i18n, log, downloadStepCount, managedAi);

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
