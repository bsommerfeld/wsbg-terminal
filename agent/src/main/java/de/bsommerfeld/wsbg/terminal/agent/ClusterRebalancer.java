package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import dev.langchain4j.store.embedding.CosineSimilarity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodic deep cluster maintenance — the <b>single</b> owner of cluster
 * consolidation. {@link PassiveMonitorService} only assigns threads to clusters
 * on arrival ("assign on arrival"); everything that touches more than one
 * cluster — merging converged pairs and pruning dead ones — happens here on a
 * fixed cadence ("consolidate every 30 s"). There used to be a second,
 * single-pass merge + a separate idle-prune inside {@code PassiveMonitorService}
 * running every scan tick; they were redundant (same threshold) and split the
 * merge story across two files, so they were folded into this rebalancer.
 *
 * <p>
 * The merge runs iteratively until the cluster set is stable (a single pass
 * misses transitive A ↔ B ↔ C collapses). Pruning has two criteria: idle
 * clusters past the investigation TTL, and stale thin single-thread clusters
 * that never picked up engagement.
 *
 * <p>
 * Not HDBSCAN. The greedy + iterative approach is O(n² · passes) and works
 * cleanly for the ~50–200 active cluster scale we see in practice. If cluster
 * count ever crosses ~1000 this would need to be replaced with a proper
 * density-based algorithm.
 */
@Singleton
public class ClusterRebalancer {

    private static final Logger LOG = LoggerFactory.getLogger(ClusterRebalancer.class);

    /**
     * Run cadence. Pure JVM work — O(n² · passes) cosine similarity on
     * 768-dim float vectors. At our scale (~50 active clusters) one pass
     * is sub-10 ms, so we can afford to run every 30 s instead of every
     * 10 min. Cluster fragmentation is what users notice; making the
     * reconciliation latency a function of seconds rather than minutes
     * is the cheapest fix that helps.
     */
    static final Duration INTERVAL = Duration.ofSeconds(30);

    /**
     * Outlier criterion: single-thread cluster older than this without ever
     * picking up additional engagement (no new comments crossing 3 total) gets
     * pruned. The threshold matches the editorial significance floor — if it
     * never crossed that bar, it never will.
     */
    static final Duration OUTLIER_AGE = Duration.ofMinutes(45);
    static final int OUTLIER_COMMENT_FLOOR = 3;

    private final ClusterRegistry registry;
    private final double rebalanceThreshold;
    /** Idle clusters whose last activity is older than this are pruned. */
    private final Duration investigationTtl;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "cluster-rebalancer");
                t.setDaemon(true);
                return t;
            });

    @Inject
    public ClusterRebalancer(ClusterRegistry registry, GlobalConfig config) {
        this.registry = registry;
        // Merge bias of +0.14 keeps the merge bar conservative even when
        // the assignment threshold drops aggressively (0.48 → merge 0.62).
        // Two clusters need real semantic overlap to collapse, not just
        // "marginally above the join bar".
        this.rebalanceThreshold = Math.max(0.60, config.getReddit().getSimilarityThreshold() + 0.14);
        this.investigationTtl = Duration.ofMinutes(config.getReddit().getInvestigationTtlMinutes());
        scheduler.scheduleAtFixedRate(this::safeRun,
                INTERVAL.toSeconds(), INTERVAL.toSeconds(), TimeUnit.SECONDS);
        LOG.info("ClusterRebalancer scheduled every {}s (mergeThreshold={})",
                INTERVAL.toSeconds(), String.format("%.2f", rebalanceThreshold));
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }

    private void safeRun() {
        try {
            RebalanceStats stats = run();
            if (stats.merges > 0 || stats.outliers > 0) {
                LOG.info("Rebalance: {} merges in {} pass(es), {} outlier(s) pruned",
                        stats.merges, stats.passes, stats.outliers);
            }
        } catch (Exception e) {
            LOG.warn("Rebalance pass failed: {}", e.getMessage());
        }
    }

    /**
     * Runs the rebalance synchronously. Exposed so tests can drive it without
     * waiting for the scheduler.
     */
    public RebalanceStats run() {
        int totalMerges = 0;
        int passes = 0;
        boolean changed;
        do {
            changed = false;
            passes++;
            List<InvestigationCluster> snapshot = new ArrayList<>(registry.getAllClusters());
            for (int i = 0; i < snapshot.size(); i++) {
                InvestigationCluster a = snapshot.get(i);
                if (!registry.contains(a))
                    continue;
                for (int j = i + 1; j < snapshot.size(); j++) {
                    InvestigationCluster b = snapshot.get(j);
                    if (!registry.contains(b))
                        continue;
                    double sim = CosineSimilarity.between(a.centroid(), b.centroid());
                    if (sim >= rebalanceThreshold) {
                        InvestigationCluster primary = a.threadCount >= b.threadCount ? a : b;
                        InvestigationCluster secondary = primary == a ? b : a;
                        primary.absorb(secondary);
                        registry.remove(secondary.id);
                        registry.notifyChange(primary.id);
                        totalMerges++;
                        changed = true;
                    }
                }
            }
        } while (changed && passes < 5);

        int outliers = prune();
        return new RebalanceStats(passes, totalMerges, outliers);
    }

    /**
     * Removes clusters that have aged out, on two criteria:
     * <ul>
     *   <li><b>idle</b> — last activity older than the investigation TTL
     *       (the topic has gone quiet);</li>
     *   <li><b>stale outlier</b> — a single-thread cluster older than
     *       {@link #OUTLIER_AGE} that never crossed the comment floor (it
     *       never took off and never will).</li>
     * </ul>
     */
    private int prune() {
        Instant now = Instant.now();
        int pruned = 0;
        for (InvestigationCluster c : new ArrayList<>(registry.getAllClusters())) {
            boolean idle = Duration.between(c.lastActivity, now).compareTo(investigationTtl) > 0;
            boolean staleOutlier = Duration.between(c.firstSeen, now).compareTo(OUTLIER_AGE) > 0
                    && c.threadCount <= 1 && c.totalComments < OUTLIER_COMMENT_FLOOR;
            if (idle || staleOutlier) {
                registry.remove(c.id);
                pruned++;
            }
        }
        return pruned;
    }

    /** Stats about a single rebalance pass. */
    public record RebalanceStats(int passes, int merges, int outliers) {
    }
}
