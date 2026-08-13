package de.bsommerfeld.wsbg.terminal.core.debug;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The Reddit timeline's memory: every poll lane (subreddit scan, comment
 * stream, gap-fill) with time/duration/yield/failure, the throttle truth
 * (token-bucket wait, Reddit's own {@code x-ratelimit-*} budget, preemptive
 * backoff sleeps), and the source-chain events (demotions, fall-throughs,
 * degraded/healthy transitions).
 *
 * <p>Fed from {@code PassiveMonitorService} (agent), {@code
 * TokenBucketRateLimiter} / {@code RateLimitGuard} / {@code
 * FallbackRedditSource} (reddit) — ONLY behind {@code if (Debug.ENABLED)}.
 */
public final class RedditPollDebug {

    private static final RedditPollDebug INSTANCE = new RedditPollDebug();

    public static RedditPollDebug get() {
        return INSTANCE;
    }

    static final int LANE_CAPACITY = 600;
    static final int SOURCE_EVENT_CAPACITY = 200;

    /**
     * One completed poll lane. {@code lane} ∈ {@code scan | comments | gap-fill};
     * {@code scope} is the subreddit (or batch size for gap-fill).
     */
    public record Lane(long atMs, String lane, String scope, long durationMs,
            int newThreads, int newUpvotes, int newComments, int scanned, boolean failed) {
    }

    /** A source-chain event: {@code kind} ∈ {@code active | switch | demote | degraded | healthy}. */
    public record SourceEvent(long atMs, String kind, String detail) {
    }

    /** The throttle truth as one snapshot. Header values are Reddit's own. */
    public record Throttle(long bucketWaitMsTotal, long bucketAcquires,
            long backoffSleepMsTotal, long backoffCount,
            double lastRatelimitRemaining, long lastRatelimitSeenAtMs) {
    }

    private final DebugRing<Lane> lanes = new DebugRing<>(LANE_CAPACITY);
    private final DebugRing<SourceEvent> sourceEvents = new DebugRing<>(SOURCE_EVENT_CAPACITY);

    private final AtomicLong bucketWaitMsTotal = new AtomicLong();
    private final AtomicLong bucketAcquires = new AtomicLong();
    private final AtomicLong backoffSleepMsTotal = new AtomicLong();
    private final AtomicLong backoffCount = new AtomicLong();
    private volatile double lastRatelimitRemaining = -1;
    private volatile long lastRatelimitSeenAtMs;

    public void recordLane(String lane, String scope, long durationMs,
            int newThreads, int newUpvotes, int newComments, int scanned, boolean failed) {
        lanes.add(new Lane(System.currentTimeMillis(), lane, scope, durationMs,
                newThreads, newUpvotes, newComments, scanned, failed));
    }

    public void recordSourceEvent(String kind, String detail) {
        sourceEvents.add(new SourceEvent(System.currentTimeMillis(), kind, detail));
    }

    /** Milliseconds a caller slept inside the token bucket. */
    public void addBucketWait(long ms) {
        bucketWaitMsTotal.addAndGet(ms);
    }

    /** One token successfully acquired (with or without waiting). */
    public void countAcquire() {
        bucketAcquires.incrementAndGet();
    }

    /** A preemptive x-ratelimit backoff sleep of {@code ms}. */
    public void recordBackoff(long ms) {
        backoffSleepMsTotal.addAndGet(ms);
        backoffCount.incrementAndGet();
    }

    /** Reddit's own {@code x-ratelimit-remaining}, as parsed from a response. */
    public void recordBudgetRemaining(double remaining) {
        lastRatelimitRemaining = remaining;
        lastRatelimitSeenAtMs = System.currentTimeMillis();
    }

    public List<Lane> recentLanes(int limit) {
        return lanes.recent(limit);
    }

    public List<SourceEvent> recentSourceEvents(int limit) {
        return sourceEvents.recent(limit);
    }

    public Throttle throttle() {
        return new Throttle(bucketWaitMsTotal.get(), bucketAcquires.get(),
                backoffSleepMsTotal.get(), backoffCount.get(),
                lastRatelimitRemaining, lastRatelimitSeenAtMs);
    }

    /** Test seam. */
    public void reset() {
        lanes.clear();
        sourceEvents.clear();
        bucketWaitMsTotal.set(0);
        bucketAcquires.set(0);
        backoffSleepMsTotal.set(0);
        backoffCount.set(0);
        lastRatelimitRemaining = -1;
        lastRatelimitSeenAtMs = 0;
    }

    private RedditPollDebug() {
    }
}
