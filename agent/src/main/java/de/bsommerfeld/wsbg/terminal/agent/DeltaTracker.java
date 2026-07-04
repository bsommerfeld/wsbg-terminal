package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks per-thread score/comment deltas between scan cycles (extracted from
 * {@link PassiveMonitorService}). {@link #seedBaseline} primes a thread so a
 * restored/seeded thread doesn't read as brand-new on the first scan (which would
 * re-trigger comment fan-out); {@link #computeDeltas} returns the change since the
 * last observation and advances the baseline.
 */
final class DeltaTracker {

    private final Map<String, Integer> lastSeenScore = new ConcurrentHashMap<>();
    private final Map<String, Integer> lastSeenComments = new ConcurrentHashMap<>();

    /** Primes the baseline for a thread so it doesn't read as "new" on the next scan. */
    void seedBaseline(RedditThread t) {
        lastSeenScore.put(t.id(), t.score());
        lastSeenComments.put(t.id(), t.numComments());
    }

    /**
     * Returns {@code [deltaScore, deltaComments]} since the last observation and
     * advances the baseline. An unseen thread yields {@code [0, 0]}.
     */
    int[] computeDeltas(RedditThread t) {
        int deltaScore = t.score() - lastSeenScore.getOrDefault(t.id(), t.score());
        lastSeenScore.put(t.id(), t.score());

        int deltaComments = t.numComments() - lastSeenComments.getOrDefault(t.id(), t.numComments());
        lastSeenComments.put(t.id(), t.numComments());

        return new int[] { deltaScore, deltaComments };
    }
}
