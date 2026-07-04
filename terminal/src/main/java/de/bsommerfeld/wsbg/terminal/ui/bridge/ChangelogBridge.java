package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

/**
 * The "Was hat sich geändert" overlay's backend: after a fresh update, the first
 * client open finds the installed version ({@code version.txt}) ahead of the last
 * version whose notes the user saw ({@code user.last-seen-changelog-version}) and
 * pushes the recent GitHub release bodies so the page can open the overlay once.
 * The GitHub plumbing lives in {@link GitHubReleases}.
 *
 * <p>Inbound: {@code {type:"changelog", payload:{command:"open"|"seen"}}} —
 * {@code open} pushes the releases unconditionally (manual/dev trigger),
 * {@code seen} persists the installed version so the overlay stays closed until
 * the next update. Outbound: one {@code changelog} broadcast
 * {@code {show, current, releases:[{tag, name, publishedAt, body}]}}.
 *
 * <p>A fresh install (empty last-seen) records the installed version silently —
 * the overlay is an update digest, not a first-run greeting. A dev {@code run.sh}
 * start has no {@code version.txt} and never auto-opens.
 */
@Singleton
public final class ChangelogBridge {

    private static final Logger LOG = LoggerFactory.getLogger(ChangelogBridge.class);

    private final GlobalConfig config;
    private final PushHub hub;
    private final GitHubReleases releases;
    // The GitHub fetch happens off the websocket thread; one lane is plenty.
    private final ExecutorService worker = DaemonSchedulers.single("changelog-fetch");

    @Inject
    public ChangelogBridge(GlobalConfig config, PushHub hub, GitHubReleases releases) {
        this.config = config;
        this.hub = hub;
        this.releases = releases;
        hub.on("changelog", this::onCommand);
        hub.onClientOpen(this::maybeShowFreshUpdate);
    }

    private void onCommand(Map<String, Object> payload) {
        Object cmd = payload.get("command");
        if ("open".equals(cmd)) {
            worker.submit(() -> pushReleases(GitHubReleases.readLocalVersion()));
        } else if ("seen".equals(cmd)) {
            markSeen();
        }
    }

    /** Auto-open check, run on every client connect (idempotent until "seen"). */
    private void maybeShowFreshUpdate() {
        String current = GitHubReleases.readLocalVersion();
        if (current == null) return; // dev run — no version.txt, never auto-open
        String lastSeen = config.getUser().getLastSeenChangelogVersion();
        if (lastSeen == null || lastSeen.isBlank()) {
            // Fresh install: nothing was updated, just remember where we start.
            config.getUser().setLastSeenChangelogVersion(current);
            config.save();
            return;
        }
        if (lastSeen.equals(current)) return;
        worker.submit(() -> pushReleases(current));
    }

    private void markSeen() {
        String current = GitHubReleases.readLocalVersion();
        if (current == null || current.equals(config.getUser().getLastSeenChangelogVersion())) return;
        config.getUser().setLastSeenChangelogVersion(current);
        config.save();
    }

    private void pushReleases(String current) {
        try {
            List<Map<String, Object>> recent = releases.recentReleases();
            if (recent.isEmpty()) return; // GitHub unreachable → retry on the next connect
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("show", true);
            m.put("current", current);
            m.put("releases", recent);
            hub.broadcast("changelog", m);
        } catch (Exception e) {
            LOG.debug("changelog fetch failed: {}", e.getMessage());
        }
    }
}
