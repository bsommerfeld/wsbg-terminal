package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;

import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A 1:1 wrapper of a single Reddit thread (cluster id == thread id) that
 * accumulates score/comment deltas — the ingestion bucket the subject layer
 * reads its evidence from.
 *
 * <p>
 * State is intentionally focused: editorial decisions (which headlines were
 * published when) live in {@link de.bsommerfeld.wsbg.terminal.db.AgentRepository},
 * not on the cluster — that's the source of truth the agent reads through
 * tools. The cluster only holds the data the scanner produces.
 */
public class InvestigationCluster {

    public final String id;
    public final String initialTitle;
    public final Instant firstSeen;
    // Concurrent + lock-free: mutated by the scan thread (addUpdate) and the
    // rebalancer thread (absorb) while the editorial/coordinator thread and the
    // snapshot writer iterate them. Same discipline as tickers / shownImageUrls
    // below — a plain HashSet/ArrayList here would throw
    // ConcurrentModificationException on those concurrent reads.
    public final Set<String> activeThreadIds = ConcurrentHashMap.newKeySet();
    final List<String> evidenceLog = new CopyOnWriteArrayList<>();

    /**
     * Ticker symbols mentioned in any thread of this cluster (canonical
     * uppercase). Populated by {@link TickerExtractor} on every addUpdate.
     * Used by the clustering layer for ticker-overlap matching that
     * bypasses the cosine-similarity threshold.
     */
    public final Set<String> tickers = ConcurrentHashMap.newKeySet();

    /**
     * Image URLs whose vision description has already been surfaced to the
     * agent in a report (thread/gallery slides + comment images alike).
     * Lets {@link ReportBuilder} re-surface a thread/comment that was marked
     * covered BEFORE its (slow, async) image analysis landed — the late
     * description gets one shot in a later tick instead of being lost behind
     * the coverage filter. Once shown, the URL stays here so it isn't
     * re-surfaced forever.
     */
    public final Set<String> shownImageUrls = ConcurrentHashMap.newKeySet();

    public Instant lastActivity;
    public int threadCount;
    public int totalScore;
    public int totalComments;
    public double currentSignificance;
    public int headlineCount;

    public String latestThreadId;
    public String bestThreadId;
    int bestThreadScore = -1;
    public long threadCreatedUTC;

    public InvestigationCluster(RedditThread initial) {
        // Deterministic ID: the initial thread's Reddit ID survives restarts,
        // so historical headlines can be matched back when the same thread
        // surfaces again.
        this.id = initial.id();
        this.initialTitle = initial.title();
        this.firstSeen = Instant.now();
        this.lastActivity = Instant.now();

        this.threadCount = 1;
        this.totalScore = initial.score();
        this.totalComments = initial.numComments();
        this.latestThreadId = initial.id();
        this.bestThreadId = initial.id();
        this.bestThreadScore = initial.score();
        this.threadCreatedUTC = initial.createdUtc();

        this.evidenceLog.add("[" + LocalTime.now() + "] New Thread: " + initial.title());
        this.activeThreadIds.add(initial.id());
        this.tickers.addAll(TickerExtractor.extract(initial.title()));
        this.tickers.addAll(TickerExtractor.extract(initial.textContent()));
    }

    /**
     * Restore constructor — rebuilds a cluster verbatim from a persisted
     * {@link Snapshot} so a quick restart resumes the EXACT prior state
     * (evidence log, shown-image markers, counters, headlineCount), not a
     * re-seeded approximation. {@code addUpdate} and the ticker-extraction
     * side effects are intentionally bypassed.
     */
    InvestigationCluster(Snapshot s) {
        this.id = s.id();
        this.initialTitle = s.initialTitle();
        this.firstSeen = Instant.ofEpochSecond(s.firstSeenEpoch());
        this.lastActivity = Instant.ofEpochSecond(s.lastActivityEpoch());
        this.threadCount = s.threadCount();
        this.totalScore = s.totalScore();
        this.totalComments = s.totalComments();
        this.currentSignificance = s.currentSignificance();
        this.headlineCount = s.headlineCount();
        this.latestThreadId = s.latestThreadId();
        this.bestThreadId = s.bestThreadId();
        this.bestThreadScore = s.bestThreadScore();
        this.threadCreatedUTC = s.threadCreatedUTC();
        if (s.activeThreadIds() != null) this.activeThreadIds.addAll(s.activeThreadIds());
        if (s.evidenceLog() != null) this.evidenceLog.addAll(s.evidenceLog());
        if (s.tickers() != null) this.tickers.addAll(s.tickers());
        if (s.shownImageUrls() != null) this.shownImageUrls.addAll(s.shownImageUrls());
    }

    public void addUpdate(RedditThread t, int deltaScore, int deltaComments) {
        if (deltaComments > 0)
            this.lastActivity = Instant.now();
        this.totalScore += deltaScore;
        this.totalComments += deltaComments;
        this.latestThreadId = t.id();
        this.activeThreadIds.add(t.id());

        if (t.score() > this.bestThreadScore) {
            this.bestThreadScore = t.score();
            this.bestThreadId = t.id();
        }

        this.tickers.addAll(TickerExtractor.extract(t.title()));
        this.tickers.addAll(TickerExtractor.extract(t.textContent()));

        boolean isNew = evidenceLog.stream().noneMatch(s -> s.contains(t.title()));
        if (isNew) {
            this.threadCount++;
            this.evidenceLog.add("[" + LocalTime.now() + "] Related Thread: " + t.title());
        } else {
            this.evidenceLog.add("[" + LocalTime.now() + "] Activity: +"
                    + deltaScore + " score, +" + deltaComments + " comments on '" + t.title() + "'");
        }
    }

    /** Captures the full cluster state for lossless persistence. */
    public Snapshot toSnapshot() {
        return new Snapshot(
                id, initialTitle, firstSeen.getEpochSecond(), lastActivity.getEpochSecond(),
                threadCount, totalScore, totalComments,
                currentSignificance, headlineCount, latestThreadId, bestThreadId,
                bestThreadScore, threadCreatedUTC,
                new ArrayList<>(activeThreadIds), new ArrayList<>(evidenceLog),
                new ArrayList<>(tickers), new ArrayList<>(shownImageUrls));
    }

    /**
     * Serialisable, lossless capture of a cluster's state — everything the
     * agent built up (evidence, shown-image markers, counters). Restored via
     * the {@link #InvestigationCluster(Snapshot)} ctor. (An old snapshot's
     * legacy {@code centroid} field is simply ignored on load.)
     */
    public record Snapshot(
            String id,
            String initialTitle,
            long firstSeenEpoch,
            long lastActivityEpoch,
            int threadCount,
            int totalScore,
            int totalComments,
            double currentSignificance,
            int headlineCount,
            String latestThreadId,
            String bestThreadId,
            int bestThreadScore,
            long threadCreatedUTC,
            List<String> activeThreadIds,
            List<String> evidenceLog,
            List<String> tickers,
            List<String> shownImageUrls) {
    }
}
