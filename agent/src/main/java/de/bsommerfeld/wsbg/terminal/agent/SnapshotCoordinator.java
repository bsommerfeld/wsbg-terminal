package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import de.bsommerfeld.wsbg.terminal.db.RedditSnapshotStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Owns short-TTL session persistence (extracted from {@link PassiveMonitorService}):
 * saving the full session (Reddit data + vision cache + published headlines + cluster
 * and subject-unit state) and restoring it verbatim on a quick restart, including the
 * delta-baseline seeding that keeps restored threads from reading as brand-new.
 *
 * <p>Gated on the configured TTL ({@code 0} disables it). {@link #restore()} runs
 * synchronously on the scanner thread before the scheduler starts, and returns the
 * {@code clustersRestored} signal the monitor uses to choose verbatim-resume vs local
 * re-seed vs a cold fetch.
 */
final class SnapshotCoordinator {

    private static final Logger LOG = LoggerFactory.getLogger(SnapshotCoordinator.class);

    private final RedditRepository repository;
    private final AgentRepository agentRepository;
    private final AgentBrain brain;
    private final ClusterRegistry clusterRegistry;
    private final SubjectRegistry subjectRegistry;
    private final RedditSnapshotStore snapshotStore;
    private final AgentSnapshotStore agentSnapshotStore;
    private final DeltaTracker deltaTracker;
    private final long snapshotTtlMinutes;
    private final boolean enabled;

    SnapshotCoordinator(RedditRepository repository, AgentRepository agentRepository, AgentBrain brain,
            ClusterRegistry clusterRegistry, SubjectRegistry subjectRegistry,
            RedditSnapshotStore snapshotStore, AgentSnapshotStore agentSnapshotStore,
            DeltaTracker deltaTracker, long snapshotTtlMinutes) {
        this.repository = repository;
        this.agentRepository = agentRepository;
        this.brain = brain;
        this.clusterRegistry = clusterRegistry;
        this.subjectRegistry = subjectRegistry;
        this.snapshotStore = snapshotStore;
        this.agentSnapshotStore = agentSnapshotStore;
        this.deltaTracker = deltaTracker;
        this.snapshotTtlMinutes = snapshotTtlMinutes;
        this.enabled = snapshotTtlMinutes > 0;
    }

    /** Whether snapshot persistence is enabled (TTL &gt; 0). */
    boolean enabled() {
        return enabled;
    }

    /** Outcome of a restore: whether Reddit data came back, and whether the cluster set did. */
    record RestoreOutcome(boolean dataRestored, boolean clustersRestored) {
    }

    /** Writes the full session (Reddit data + vision cache + headlines + clusters + subjects) to disk. */
    void save() {
        if (!enabled) return;
        try {
            snapshotStore.save(repository.getAllThreads(), repository.getAllComments());
            List<InvestigationCluster.Snapshot> clusterSnapshots = clusterRegistry.getAllClusters()
                    .stream().map(InvestigationCluster::toSnapshot).collect(Collectors.toList());
            agentSnapshotStore.save(brain.exportVisionCache(),
                    agentRepository.getAllHeadlines(), clusterSnapshots,
                    subjectRegistry.snapshotAll());
        } catch (Exception e) {
            LOG.warn("Snapshot save failed: {}", e.getMessage());
        }
    }

    /**
     * Restores threads + comments (and the AI-derived state) from a fresh on-disk
     * snapshot, if one exists within the configured TTL. Seeds delta baselines so
     * restored threads don't read as brand-new on the first scan.
     */
    RestoreOutcome restore() {
        if (!enabled) return new RestoreOutcome(false, false);
        var snapshot = snapshotStore.loadIfFresh(snapshotTtlMinutes);
        if (snapshot.isEmpty()) return new RestoreOutcome(false, false);

        RedditSnapshotStore.RedditSnapshot s = snapshot.get();
        if (s.threads() != null) {
            repository.saveThreadsBatch(s.threads());
        }
        if (s.comments() != null) {
            for (RedditComment c : s.comments()) {
                repository.saveComment(c);
            }
        }
        LOG.info("Restored {} threads + {} comments from snapshot.",
                s.threads() != null ? s.threads().size() : 0,
                s.comments() != null ? s.comments().size() : 0);

        // Seed delta baselines so restored threads don't read as brand-new on
        // the first scan (which would re-trigger comment fan-out).
        for (RedditThread t : repository.getAllThreads()) {
            deltaTracker.seedBaseline(t);
        }

        // Restore the AI-derived state too: already-analysed images (no re-vision),
        // published headlines (UI shows them immediately), and the full cluster state
        // verbatim (evidence, shown-image markers) so the agent resumes exactly where
        // it left off.
        boolean[] clustersRestored = {false};
        agentSnapshotStore.loadIfFresh(snapshotTtlMinutes).ifPresent(a -> {
            brain.importVisionCache(a.visionCache());
            agentRepository.restoreHeadlines(a.headlines());
            if (a.clusters() != null && !a.clusters().isEmpty()) {
                List<InvestigationCluster> restored = a.clusters().stream()
                        .map(InvestigationCluster::new)
                        .collect(Collectors.toList());
                clusterRegistry.restore(restored);
                clustersRestored[0] = true;
            }
            // Subject units are the editorial atom — restore them verbatim too, so
            // accumulated evidence, the price anchor, published-headline history and
            // covered-news ids survive a quick restart.
            subjectRegistry.restore(a.subjectUnits());
            LOG.info("Restored {} vision entries, {} headlines, {} clusters, {} subject units from agent snapshot.",
                    a.visionCache() != null ? a.visionCache().size() : 0,
                    a.headlines() != null ? a.headlines().size() : 0,
                    a.clusters() != null ? a.clusters().size() : 0,
                    a.subjectUnits() != null ? a.subjectUnits().size() : 0);
        });
        return new RestoreOutcome(true, clustersRestored[0]);
    }
}
