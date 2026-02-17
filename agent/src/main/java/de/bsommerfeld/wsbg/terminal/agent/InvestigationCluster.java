package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import dev.langchain4j.data.embedding.Embedding;

import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Tracks a semantically clustered group of Reddit threads that share
 * a common topic. Accumulates score/comment deltas, maintains an
 * evidence log for AI headline generation, and continuously refines
 * its centroid via exponential moving average.
 */
class InvestigationCluster {

    /**
     * EMA weight for centroid updates. 0.3 lets recent threads shift the
     * centroid noticeably without losing the original topic anchor.
     * Lower values = more stable, higher = more responsive to drift.
     */
    private static final float CENTROID_EMA_ALPHA = 0.3f;

    final String id;
    final String initialTitle;
    final Instant firstSeen;
    final Set<String> activeThreadIds = new HashSet<>();
    final List<String> evidenceLog = new ArrayList<>();
    final List<String> reportHistory = new ArrayList<>();

    Instant lastActivity;
    String cachedContext;
    int threadCount;
    int totalScore;
    int totalComments;
    double currentSignificance;
    boolean reported;

    String latestThreadId;
    String bestThreadId;
    int bestThreadScore = -1;
    long threadCreatedUTC;

    /**
     * Living centroid — updated via EMA on every new thread.
     * Not final because merging replaces it with a weighted average.
     */
    private float[] centroidVector;

    InvestigationCluster(RedditThread initial, Embedding embedding) {
        this.id = UUID.randomUUID().toString().substring(0, 8);
        this.initialTitle = initial.title();
        this.firstSeen = Instant.now();
        this.lastActivity = Instant.now();
        this.centroidVector = copy(embedding.vector());

        this.threadCount = 1;
        this.totalScore = initial.score();
        this.totalComments = initial.numComments();
        this.latestThreadId = initial.id();
        this.bestThreadId = initial.id();
        this.bestThreadScore = initial.score();
        this.threadCreatedUTC = initial.createdUtc();

        this.evidenceLog.add("[" + LocalTime.now() + "] New Thread: " + initial.title());
        this.activeThreadIds.add(initial.id());
    }

    void addUpdate(RedditThread t, int deltaScore, int deltaComments, Embedding embedding) {
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

        // Shift centroid toward the new thread's embedding
        updateCentroid(embedding.vector());

        boolean isNew = evidenceLog.stream().noneMatch(s -> s.contains(t.title()));
        if (isNew) {
            this.threadCount++;
            this.evidenceLog.add("[" + LocalTime.now() + "] Related Thread: " + t.title());
        } else {
            this.evidenceLog.add("[" + LocalTime.now() + "] Activity: +"
                    + deltaScore + " score, +" + deltaComments + " comments on '" + t.title() + "'");
        }
    }

    /**
     * Returns the current centroid as an {@link Embedding} for similarity queries.
     */
    Embedding centroid() {
        return Embedding.from(centroidVector);
    }

    /**
     * Absorbs another cluster into this one. Used for cluster merging
     * when two investigations converge on the same topic over time.
     * The centroid becomes a thread-count-weighted average.
     */
    void absorb(InvestigationCluster other) {
        // Weighted centroid: proportional to thread count
        float totalWeight = this.threadCount + other.threadCount;
        float thisWeight = this.threadCount / totalWeight;
        float otherWeight = other.threadCount / totalWeight;

        for (int i = 0; i < centroidVector.length; i++) {
            centroidVector[i] = thisWeight * centroidVector[i] + otherWeight * other.centroidVector[i];
        }

        this.threadCount += other.threadCount;
        this.totalScore += other.totalScore;
        this.totalComments += other.totalComments;
        this.activeThreadIds.addAll(other.activeThreadIds);
        this.evidenceLog.addAll(other.evidenceLog);

        if (other.bestThreadScore > this.bestThreadScore) {
            this.bestThreadScore = other.bestThreadScore;
            this.bestThreadId = other.bestThreadId;
        }
        if (other.lastActivity.isAfter(this.lastActivity)) {
            this.lastActivity = other.lastActivity;
            this.latestThreadId = other.latestThreadId;
        }
    }

    void addToHistory(String headline) {
        LocalTime now = LocalTime.now();
        reportHistory.add(String.format("[%02d:%02d] %s", now.getHour(), now.getMinute(), headline));
        if (reportHistory.size() > 5)
            reportHistory.remove(0);
    }

    /**
     * Exponential moving average: centroid = α·new + (1-α)·old.
     * Lets the cluster track topic drift without forgetting its origin.
     */
    private void updateCentroid(float[] newVector) {
        for (int i = 0; i < centroidVector.length; i++) {
            centroidVector[i] = CENTROID_EMA_ALPHA * newVector[i]
                    + (1.0f - CENTROID_EMA_ALPHA) * centroidVector[i];
        }
    }

    private static float[] copy(float[] src) {
        float[] dst = new float[src.length];
        System.arraycopy(src, 0, dst, 0, src.length);
        return dst;
    }
}
