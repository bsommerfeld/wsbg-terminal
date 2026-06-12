package de.bsommerfeld.wsbg.terminal.agent;
import de.bsommerfeld.wsbg.terminal.embedding.EmbeddingService;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.store.embedding.CosineSimilarity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * The single source of truth for <em>cluster assignment</em>: embed a thread
 * (title + body + vision) and route it into the best-matching
 * {@link InvestigationCluster}, or create a new one.
 *
 * <p>Pulled out of {@link PassiveMonitorService} so the assignment logic is one
 * reusable unit driven by both the live scanner and the offline
 * {@code editorial-lab} harness — the lab exercises the exact same code path the
 * production scan loop does, so a tweak made while optimising clustering takes
 * effect everywhere. The scanner still owns delta-tracking, vision pre-fetch,
 * and scheduling; this class only owns "given a thread (+ its vision text and
 * deltas), where does it belong?".
 *
 * <p>Merging converged clusters and pruning dead ones is owned by
 * {@link ClusterRebalancer}, not here.
 */
@Singleton
public class ClusterEngine {

    private static final Logger LOG = LoggerFactory.getLogger(ClusterEngine.class);

    private final ClusterRegistry clusterRegistry;
    private final EmbeddingService embeddings;
    private final double similarityThreshold;

    @Inject
    public ClusterEngine(ClusterRegistry clusterRegistry, GlobalConfig config, EmbeddingService embeddings) {
        this.clusterRegistry = clusterRegistry;
        this.similarityThreshold = config.getReddit().getSimilarityThreshold();
        this.embeddings = embeddings;
    }

    /**
     * Embeds thread content and assigns it to the best matching cluster, or
     * creates a new investigation if no cluster exceeds the similarity
     * threshold. Mutates the {@link ClusterRegistry} (add / addUpdate) and fires
     * {@link ClusterRegistry#notifyChange} exactly as the old in-scanner method
     * did. Returns an {@link AssignOutcome} describing what happened — the live
     * scanner ignores it; the lab renders it.
     *
     * @param visionText pre-computed image description(s) for this thread, or
     *                   empty string when there are no images / none are ready
     */
    public AssignOutcome assign(RedditThread t, int deltaScore, int deltaComments, String visionText) {
        // Vision description joins the embedding input so image-only posts —
        // where the title is often generic ("Abwarten!!!") — still land in the
        // right cluster based on what the picture actually shows.
        String safeVision = visionText == null ? "" : visionText;
        String content = t.title() + " "
                + (t.textContent() != null ? t.textContent() : "") + " "
                + safeVision;
        Embedding embedding = Embedding.from(embeddings.embed(content));

        // Ticker-overlap fast path. Vector similarity often fails on short
        // German titles that share an instrument ("SNOW SNOW SNOW" vs
        // "Snowflake mit Rakete"); ticker mentions are the most reliable
        // topic key the WSBG community uses. If this thread names any
        // ticker that an existing cluster has already accumulated, force
        // the merge regardless of cosine score.
        Set<String> threadTickers = new HashSet<>();
        threadTickers.addAll(TickerExtractor.extract(t.title()));
        threadTickers.addAll(TickerExtractor.extract(t.textContent()));
        threadTickers.addAll(TickerExtractor.extract(safeVision));

        if (!threadTickers.isEmpty()) {
            for (InvestigationCluster inv : clusterRegistry.getAllClusters()) {
                if (inv.tickers.isEmpty()) continue;
                Set<String> overlap = new HashSet<>(inv.tickers);
                overlap.retainAll(threadTickers);
                if (!overlap.isEmpty()) {
                    boolean newToCluster = !inv.activeThreadIds.contains(t.id());
                    // Ticker overlap settles membership; the delta only
                    // decides whether this is worth waking the agent for.
                    if (newToCluster || deltaScore > 0 || deltaComments > 0) {
                        LOG.info("Ticker-merge '{}' → '{}' (overlap {})",
                                t.title(), inv.initialTitle, overlap);
                        inv.addUpdate(t, deltaScore, deltaComments, embedding);
                        clusterRegistry.notifyChange(inv.id);
                    }
                    return new AssignOutcome(
                            newToCluster ? Kind.JOIN_TICKER : Kind.UPDATE_TICKER,
                            inv.id, inv.initialTitle, Double.NaN, overlap);
                }
            }
        }

        InvestigationCluster bestMatch = null;
        double bestScore = -1.0;
        for (InvestigationCluster inv : clusterRegistry.getAllClusters()) {
            double score = CosineSimilarity.between(embedding, inv.centroid());
            if (score > bestScore) {
                bestScore = score;
                bestMatch = inv;
            }
        }

        if (bestMatch != null && bestScore >= similarityThreshold) {
            // Membership is decided by the embedding alone — if the post is
            // similar enough, it belongs here, period. The delta is NOT a
            // gate for joining; a thread we've never seen carries a zero
            // delta on first sight, so gating on delta used to silently drop
            // brand-new posts that matched an existing topic but named no
            // ticker. We attach when the thread is new to the cluster, and
            // additionally re-notify when an already-tracked thread shows
            // fresh activity.
            boolean newToCluster = !bestMatch.activeThreadIds.contains(t.id());
            if (newToCluster || deltaScore > 0 || deltaComments > 0) {
                LOG.info("[CLUSTER] {} '{}' → '{}' (sim={}, +{} score, +{} comments)",
                        newToCluster ? "join" : "update",
                        t.title(), bestMatch.initialTitle,
                        String.format("%.2f", bestScore), deltaScore, deltaComments);
                bestMatch.addUpdate(t, deltaScore, deltaComments, embedding);
                clusterRegistry.notifyChange(bestMatch.id);
            }
            return new AssignOutcome(
                    newToCluster ? Kind.JOIN_COSINE : Kind.UPDATE_COSINE,
                    bestMatch.id, bestMatch.initialTitle, bestScore, threadTickers);
        }

        InvestigationCluster newInv = new InvestigationCluster(t, embedding);
        clusterRegistry.add(newInv);
        LOG.info("[CLUSTER] new {} '{}'{}{}",
                newInv.id, t.title(),
                bestScore > 0 ? " (best existing sim " + String.format("%.2f", bestScore) + " below " + similarityThreshold + ")" : "",
                threadTickers.isEmpty() ? "" : " (tickers " + threadTickers + ")");
        return new AssignOutcome(Kind.NEW, newInv.id, newInv.initialTitle, bestScore, threadTickers);
    }

    /** How the most recent {@link #assign} routed a thread. */
    public enum Kind {
        /** Created a brand-new cluster. */
        NEW,
        /** Joined an existing cluster via ticker overlap (new member). */
        JOIN_TICKER,
        /** Re-touched a cluster it already belonged to, via ticker overlap. */
        UPDATE_TICKER,
        /** Joined an existing cluster via cosine similarity (new member). */
        JOIN_COSINE,
        /** Re-touched a cluster it already belonged to, via cosine similarity. */
        UPDATE_COSINE
    }

    /**
     * Result of one {@link #assign} call. {@code similarity} is the cosine score
     * for the cosine kinds, {@code NaN} for ticker-overlap matches, and the
     * best-rejected score for {@link Kind#NEW} (or negative when there were no
     * clusters to compare against).
     */
    public record AssignOutcome(
            Kind kind,
            String clusterId,
            String clusterTitle,
            double similarity,
            Set<String> tickers) {
    }
}
