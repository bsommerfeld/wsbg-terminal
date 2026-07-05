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
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The "Daten löschen" full terminal wipe, extracted from {@link SettingsBridge} so
 * the settings feature no longer drags in the whole persistence layer. Owns the six
 * repos/stores the wipe touches + the anti-self-sabotage cooldown; the settings
 * bridge just delegates the {@code clear-data} command here.
 */
@Singleton
public final class DataWipeService {

    private static final Logger LOG = LoggerFactory.getLogger(DataWipeService.class);

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
    public DataWipeService(GlobalConfig config, AgentRepository agentRepository,
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
    }

    /**
     * The user's "Daten löschen": a FULL terminal wipe — threads, clusters, subject
     * units, the live wire AND the permanent archive — then the wire simply refills
     * from the next scan, as if freshly started (no forced re-fetch). Gated to once
     * per {@link #CLEAR_GATE_SECONDS} (persisted in config) so a mis-click can't
     * wipe-and-rewipe before anything has come back.
     */
    void clearData() {
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
}
