package de.bsommerfeld.wsbg.terminal.update;

import de.bsommerfeld.tinyupdate.api.GitHubRepository;
import de.bsommerfeld.tinyupdate.api.Platform;
import de.bsommerfeld.tinyupdate.api.ReleaseAssetNames;
import de.bsommerfeld.tinyupdate.api.ReleaseChannel;
import de.bsommerfeld.tinyupdate.api.TinyUpdateClient;
import de.bsommerfeld.tinyupdate.api.UpdateMonitor;
import de.bsommerfeld.tinyupdate.handoff.Handoff;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * The terminal's side of updating. While it runs, it asks in the background
 * whether its stream has an update; to apply one, it brings the updater up
 * to date, hands off to it and leaves - the updater swaps the files once the
 * terminal is gone and starts it again.
 *
 * <p>
 * Two streams in each release, one per install, each updated by the other:
 *
 * <pre>
 * wsbg-&lt;platform&gt;-...          the terminal  → applied by the updater
 * wsbg-updater-&lt;platform&gt;-...  the updater   → applied by the terminal, right before the handoff
 * </pre>
 *
 * So the updater that applies a release is always that release's updater.
 */
public final class TerminalUpdates implements AutoCloseable {

    private static final GitHubRepository REPOSITORY = GitHubRepository.of("bsommerfeld/wsbg-terminal");
    private static final ReleaseChannel CHANNEL = ReleaseChannel.STABLE;
    private static final String ASSET_PREFIX = "wsbg";
    private static final String UPDATER_ASSET_PREFIX = "wsbg-updater";
    private static final String NAME = "WSBG Terminal";

    /** How often the running terminal asks; GitHub allows 60 unauthenticated API calls an hour. */
    private static final Duration CHECK_INTERVAL = Duration.ofHours(3);

    private final Installation installation;
    private final String platform = Platform.current();
    private UpdateMonitor monitor;

    private TerminalUpdates(Installation installation) {
        this.installation = installation;
    }

    /** Updates for the running terminal - none when it was not started installed. */
    public static Optional<TerminalUpdates> forRunningTerminal() {
        return Installation.current().map(TerminalUpdates::new);
    }

    /** Starts asking; {@code onPending} runs once, off the FX thread, when an update is there. */
    public synchronized void watch(Runnable onPending) {
        if (monitor != null) {
            throw new IllegalStateException("already watching");
        }
        TinyUpdateClient terminal = new TinyUpdateClient(REPOSITORY, installation.install(), CHANNEL,
                ReleaseAssetNames.of(ASSET_PREFIX, platform));
        monitor = UpdateMonitor.of(terminal, CHECK_INTERVAL, onPending);
        monitor.start();
    }

    /**
     * Brings the updater up to date and starts it with the handoff. Blocks
     * for the updater's own update - call it off the FX thread. The terminal
     * has to exit right after; the updater waits for exactly that.
     *
     * <p>
     * An updater that cannot be updated (offline, its assets still
     * uploading) is started as it is: the handoff is the same, and a
     * missing updater install is laid out by its launcher from the bundle.
     */
    public void handOff() throws IOException {
        TinyUpdateClient updater = new TinyUpdateClient(REPOSITORY, installation.updaterInstall(), CHANNEL,
                ReleaseAssetNames.of(UPDATER_ASSET_PREFIX, platform));
        try {
            updater.update(_ -> { }, 0);
        } catch (Exception e) {
            System.err.println("[update] Updater not updated, starting it as it is: " + e);
        }

        Handoff handoff = new Handoff(ProcessHandle.current().pid(), NAME, installation.install(),
                REPOSITORY, CHANNEL, ASSET_PREFIX, platform, setupScript(),
                List.of(installation.launcher().toString()));
        Files.createDirectories(installation.logDirectory());
        handoff.start(List.of(installation.updaterLauncher().toString()),
                installation.logDirectory().resolve("handoff.log"));
    }

    /** The package's setup script for this platform - {@code bin/setup.*}, as the release packs it. */
    private String setupScript() {
        return platform.startsWith("windows") ? "bin/setup.ps1" : "bin/setup.sh";
    }

    @Override
    public synchronized void close() {
        if (monitor != null) {
            monitor.close();
        }
    }
}
