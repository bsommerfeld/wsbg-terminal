package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.agent.AgentSnapshotStore;
import de.bsommerfeld.wsbg.terminal.agent.ClusterRegistry;
import de.bsommerfeld.wsbg.terminal.agent.SubjectRegistry;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import de.bsommerfeld.wsbg.terminal.db.RedditSnapshotStore;
import de.bsommerfeld.wsbg.terminal.core.util.StorageUtils;
import de.bsommerfeld.wsbg.terminal.ui.CefHost;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The Settings view's backend: the few user preferences that live in the
 * persisted {@link GlobalConfig} (not the client-only appearance toggles, which
 * the page keeps in localStorage). Each {@code set} mutates the config in memory,
 * persists it to {@code config.toml} ({@link GlobalConfig#save()}), and echoes the
 * full settings snapshot back so every connected client stays in sync.
 *
 * <p>Inbound: {@code {type:"settings", payload:{command:"get"|"set", key?, value?}}}.
 * Keys (all optional on the wire, ignored if unknown):
 * <ul>
 *   <li>{@code headlinesMode} — {@code "all"} (cluster-theme + subjects) vs
 *       {@code "tickers"} (subjects only, default) → {@code headlines.cluster-theme-enabled};</li>
 *   <li>{@code analyzeImages} — boolean (default true) → {@code headlines.analyze-images}
 *       (off = skip all vision for fast text-only headlines);</li>
 *   <li>{@code language} — {@code "de"}/{@code "en"} → {@code user.language};</li>
 *   <li>{@code autoUpdate} — boolean → {@code user.auto-update}.</li>
 * </ul>
 * Also handles {@code {command:"clear-data"}}: a full terminal wipe (threads,
 * clusters, subject units, the live wire AND the permanent archive), gated to once
 * per 10 minutes; the wire then refills from the next scan. And
 * {@code {command:"open-logs"}}: reveals the app-data folder (which holds
 * {@code logs/}) in the OS file manager.
 *
 * <p>Outbound (after every {@code set}, on {@code get}, and on client open): one
 * {@code settings} broadcast carrying the current value of every key.
 */
@Singleton
public final class SettingsBridge {

    private static final Logger LOG = LoggerFactory.getLogger(SettingsBridge.class);

    /** "Daten löschen" anti-self-sabotage cooldown — at most one full wipe per 10 min. */
    private static final long CLEAR_GATE_SECONDS = 600;

    private final GlobalConfig config;
    private final AgentRepository agentRepository;
    private final RedditRepository redditRepository;
    private final ClusterRegistry clusterRegistry;
    private final SubjectRegistry subjectRegistry;
    private final RedditSnapshotStore redditSnapshotStore;
    private final AgentSnapshotStore agentSnapshotStore;
    private final PushHub hub;

    @Inject
    public SettingsBridge(GlobalConfig config, AgentRepository agentRepository,
            RedditRepository redditRepository, ClusterRegistry clusterRegistry,
            SubjectRegistry subjectRegistry, RedditSnapshotStore redditSnapshotStore,
            AgentSnapshotStore agentSnapshotStore, PushHub hub) {
        this.config = config;
        this.agentRepository = agentRepository;
        this.redditRepository = redditRepository;
        this.clusterRegistry = clusterRegistry;
        this.subjectRegistry = subjectRegistry;
        this.redditSnapshotStore = redditSnapshotStore;
        this.agentSnapshotStore = agentSnapshotStore;
        this.hub = hub;
        hub.on("settings", this::onCommand);
        hub.onClientOpen(this::push);
    }

    private void onCommand(Map<String, Object> payload) {
        try {
            Object cmd = payload.get("command");
            if ("set".equals(cmd)) {
                String key = payload.get("key") instanceof String s ? s : null;
                Object value = payload.get("value");
                if (key != null && apply(config, key, value)) {
                    config.save();
                }
            } else if ("clear-data".equals(cmd)) {
                clearData();
            } else if ("open-logs".equals(cmd)) {
                CefHost.openFolder(StorageUtils.getAppDataDir());
            }
            // "get" (and any "set") answers with the full snapshot.
            push();
        } catch (Exception e) {
            LOG.warn("settings command failed: {}", e.getMessage());
        }
    }

    /**
     * The user's "Daten löschen": a FULL terminal wipe — threads, clusters, subject
     * units, the live wire AND the permanent archive — then the wire simply refills
     * from the next scan, as if freshly started (no forced re-fetch). Gated to once
     * per {@link #CLEAR_GATE_SECONDS} (persisted in config) so a mis-click can't
     * wipe-and-rewipe before anything has come back.
     */
    private void clearData() {
        long now = System.currentTimeMillis() / 1000;
        long last = config.getUser().getLastDataClearEpoch();
        if (last > 0 && (now - last) < CLEAR_GATE_SECONDS) {
            LOG.info("Daten löschen blocked — {}s cooldown active ({}s left).",
                    CLEAR_GATE_SECONDS, CLEAR_GATE_SECONDS - (now - last));
            return;
        }
        config.getUser().setLastDataClearEpoch(now);
        config.save();
        redditRepository.clear();
        clusterRegistry.clear();
        subjectRegistry.clear();
        agentRepository.clearAll();
        // Also wipe the on-disk session snapshots so a quick restart can't restore the cleared state.
        redditSnapshotStore.clear();
        agentSnapshotStore.clear();
        hub.broadcast("headlines", HeadlineJson.toJson(agentRepository.getRecentHeadlines()));
        LOG.info("Daten gelöscht (full terminal wipe + snapshots) by user; wire will refill from the next scan.");
    }

    /** Applies one key=value to the config. Returns whether anything changed. Package-private for testing. */
    static boolean apply(GlobalConfig config, String key, Object value) {
        switch (key) {
            case "headlinesMode" -> {
                // "all" = cluster-theme + per-subject; "tickers" = subjects only.
                boolean all = "all".equals(value);
                config.getHeadlines().setClusterThemeEnabled(all);
                return true;
            }
            case "language" -> {
                if (value instanceof String s && (s.equals("de") || s.equals("en"))) {
                    config.getUser().setLanguage(s);
                    return true;
                }
                return false;
            }
            case "autoUpdate" -> {
                config.getUser().setAutoUpdate(asBool(value));
                return true;
            }
            case "analyzeImages" -> {
                config.getHeadlines().setAnalyzeImages(asBool(value));
                return true;
            }
            default -> {
                LOG.debug("settings: ignoring unknown key '{}'", key);
                return false;
            }
        }
    }

    private static boolean asBool(Object value) {
        if (value instanceof Boolean b) return b;
        return value instanceof String s && Boolean.parseBoolean(s.toLowerCase(Locale.ROOT));
    }

    private void push() {
        hub.broadcast("settings", snapshot(config));
    }

    /** The full settings payload the page reads. Package-private for testing. */
    static Map<String, Object> snapshot(GlobalConfig config) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("headlinesMode", config.getHeadlines().isClusterThemeEnabled() ? "all" : "tickers");
        out.put("analyzeImages", config.getHeadlines().isAnalyzeImages());
        out.put("language", config.getUser().getLanguage());
        out.put("autoUpdate", config.getUser().isAutoUpdate());
        return out;
    }
}
