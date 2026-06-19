package de.bsommerfeld.wsbg.terminal.agent;
import de.bsommerfeld.wsbg.terminal.embedding.EmbeddingService;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import dev.langchain4j.data.embedding.Embedding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * The single source of truth for <em>cluster assignment</em>. Since the cutover to
 * feed-wide {@link SubjectUnit}s, the rule is simple: <b>one cluster == one
 * thread</b> (the cluster id is the thread id). A thread we've already seen updates
 * its own cluster; a brand-new thread creates a fresh one. There is no longer any
 * cross-thread merging.
 *
 * <p><b>Why no embedding-clustering anymore:</b> the embedding-based routing
 * (cosine-vs-centroid + ticker-overlap merge) and the periodic rebalancer existed
 * to dedup related threads back when a cluster drove one headline (3 ceasefire
 * threads → 1 cluster → 1 headline). The feed-wide {@link SubjectRegistry} is now
 * the cross-thread aggregation layer — NVIDIA from five threads folds into one
 * unit regardless of clustering — so merging threads was doing that same job twice,
 * one layer down. It was removed; a cluster is now a faithful 1:1 wrapper of a
 * single Reddit thread (the room's own grouping), which is also the atom the
 * cluster-theme producer writes from.
 *
 * <p>Pulled out of {@link PassiveMonitorService} so the assignment logic is one
 * reusable unit driven by both the live scanner and the {@code .lab} harness.
 *
 * <p>NOTE: the content embedding is still computed and stored as the cluster's
 * centroid purely to keep the snapshot shape stable; it no longer drives any
 * routing decision and is a cleanup candidate (drop it + the {@link
 * EmbeddingService} dependency once the snapshot format is migrated).
 */
@Singleton
public class ClusterEngine {

    private static final Logger LOG = LoggerFactory.getLogger(ClusterEngine.class);

    private final ClusterRegistry clusterRegistry;
    private final EmbeddingService embeddings;

    @Inject
    public ClusterEngine(ClusterRegistry clusterRegistry, EmbeddingService embeddings) {
        this.clusterRegistry = clusterRegistry;
        this.embeddings = embeddings;
    }

    /**
     * Assigns a thread to its own cluster (cluster id == thread id): creates the
     * cluster on first sight, otherwise applies the deltas to the existing one.
     * Mutates the {@link ClusterRegistry} (add / addUpdate) and fires
     * {@link ClusterRegistry#notifyChange} when there is fresh material. Returns an
     * {@link AssignOutcome} — the live scanner ignores it; the lab renders it.
     *
     * @param visionText pre-computed image description(s) for this thread, or
     *                   empty string when there are no images / none are ready
     */
    public AssignOutcome assign(RedditThread t, int deltaScore, int deltaComments, String visionText) {
        String safeVision = visionText == null ? "" : visionText;

        // Tickers are still surfaced on the outcome (the lab shows them, the
        // attributor uses them) — they just no longer route membership.
        Set<String> threadTickers = new HashSet<>();
        threadTickers.addAll(TickerExtractor.extract(t.title()));
        threadTickers.addAll(TickerExtractor.extract(t.textContent()));
        threadTickers.addAll(TickerExtractor.extract(safeVision));

        // Centroid kept only for snapshot-shape stability (see class javadoc).
        String content = t.title() + " "
                + (t.textContent() != null ? t.textContent() : "") + " "
                + safeVision;
        Embedding embedding = Embedding.from(embeddings.embed(content));

        InvestigationCluster existing = clusterRegistry.getCluster(t.id());
        if (existing != null) {
            boolean firstTime = !existing.activeThreadIds.contains(t.id());
            // Wake the agent on first sight or on real new activity; a zero-delta
            // re-scan of an unchanged thread must not re-notify.
            if (firstTime || deltaScore > 0 || deltaComments > 0) {
                LOG.info("[CLUSTER] update '{}' (+{} score, +{} comments)",
                        t.title(), deltaScore, deltaComments);
                existing.addUpdate(t, deltaScore, deltaComments, embedding);
                clusterRegistry.notifyChange(existing.id);
            }
            return new AssignOutcome(Kind.UPDATE, existing.id, existing.initialTitle, threadTickers);
        }

        InvestigationCluster newInv = new InvestigationCluster(t, embedding);
        clusterRegistry.add(newInv);
        LOG.info("[CLUSTER] new {} '{}'{}", newInv.id, t.title(),
                threadTickers.isEmpty() ? "" : " (tickers " + threadTickers + ")");
        return new AssignOutcome(Kind.NEW, newInv.id, newInv.initialTitle, threadTickers);
    }

    /** How the most recent {@link #assign} routed a thread. */
    public enum Kind {
        /** Created a brand-new cluster (first time this thread was seen). */
        NEW,
        /** Re-touched the thread's existing cluster with fresh deltas. */
        UPDATE
    }

    /** Result of one {@link #assign} call. */
    public record AssignOutcome(
            Kind kind,
            String clusterId,
            String clusterTitle,
            Set<String> tickers) {
    }
}
