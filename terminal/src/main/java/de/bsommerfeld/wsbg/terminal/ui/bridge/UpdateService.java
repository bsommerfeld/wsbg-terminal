package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.ui.AppMain;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * In-app update indicator + relaunch. The running app periodically checks
 * GitHub for a newer release and, if one exists, lights the titlebar's green
 * "update now" button ({@code update-available} broadcast). Clicking it
 * ({@code update} / {@code apply}) hands off to {@link AppMain#relaunchForUpdate()},
 * which closes cleanly and relaunches the launcher (with {@code --force-update})
 * to apply the update.
 *
 * <p>Active whenever the launcher told us its executable path
 * ({@code WSBG_LAUNCHER_EXECUTABLE}) — <b>regardless of the auto-update setting</b>.
 * With auto-update OFF the button is the only update path; with auto-update ON the
 * launcher would apply the update at the next start anyway, but the button lets a
 * user with the terminal open pull a waiting update <i>now</i> instead of having to
 * restart. It only stays dark in a dev {@code run.sh} start, where there is no
 * launcher to relaunch.
 */
@Singleton
public final class UpdateService {

    private static final Logger LOG = LoggerFactory.getLogger(UpdateService.class);

    private static final long INITIAL_DELAY_SECONDS = 25;
    // 5-minute poll: a single unauthenticated GitHub call (releases/latest),
    // so ~12 requests/hour — well under GitHub's 60/hour-per-IP anonymous cap,
    // and each install runs on its own IP. Keeps the button prompt without
    // making a long-open terminal wait hours for a fresh release to surface.
    private static final long CHECK_INTERVAL_SECONDS = 5 * 60;

    private final PushHub hub;
    private final GitHubReleases releases;
    private final boolean enabled;

    private volatile boolean available = false;
    private volatile String latestVersion = null;

    @Inject
    public UpdateService(PushHub hub, GitHubReleases releases) {
        this.hub = hub;
        this.releases = releases;
        String launcherExe = System.getenv("WSBG_LAUNCHER_EXECUTABLE");
        boolean haveLauncher = launcherExe != null && !launcherExe.isBlank();
        // Run whenever a launcher is present, regardless of the auto-update
        // setting: with auto-update off the button is the only update path;
        // with it on, the button lets an open terminal pull a waiting update
        // now instead of waiting for the next launcher start. Only a dev
        // run.sh start (no launcher) leaves this disabled.
        this.enabled = haveLauncher;

        hub.on("update", this::onCommand);
        hub.onClientOpen(this::push);

        if (enabled) {
            ScheduledExecutorService scheduler = DaemonSchedulers.scheduled("update-check");
            scheduler.scheduleAtFixedRate(this::check,
                    INITIAL_DELAY_SECONDS, CHECK_INTERVAL_SECONDS, TimeUnit.SECONDS);
        } else {
            LOG.info("In-app update check disabled (no launcher — WSBG_LAUNCHER_EXECUTABLE unset).");
        }
    }

    private void onCommand(Map<String, Object> payload) {
        if (enabled && available && "apply".equals(payload.get("command"))) {
            LOG.info("User requested in-app update → relaunching launcher.");
            AppMain.relaunchForUpdate();
        }
    }

    /** One GitHub poll: compare the latest release tag to our installed version. */
    private void check() {
        try {
            String local = GitHubReleases.readLocalVersion();
            if (local == null) return; // unknown installed version (dev) → never nag
            String remote = releases.latestTag();
            if (remote == null) return;
            boolean nowAvailable = !remote.equals(local);
            if (nowAvailable != available || !remote.equals(latestVersion)) {
                available = nowAvailable;
                latestVersion = remote;
                push();
            }
        } catch (Exception e) {
            LOG.debug("Update check failed: {}", e.getMessage());
        }
    }

    private void push() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", available);
        m.put("version", latestVersion);
        hub.broadcast("update-available", m);
    }
}
