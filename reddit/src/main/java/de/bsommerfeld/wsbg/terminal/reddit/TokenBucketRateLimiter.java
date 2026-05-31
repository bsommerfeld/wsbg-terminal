package de.bsommerfeld.wsbg.terminal.reddit;

/**
 * Simple token-bucket rate limiter.
 *
 * <p>
 * Tokens refill continuously at {@code refillPerSecond} up to {@code capacity}.
 * Each {@link #acquire()} call consumes one token, blocking when the bucket
 * is empty until enough refill has elapsed. The capacity allows a short burst
 * (e.g. a startup batch) without serializing every request, while the refill
 * rate caps sustained throughput.
 *
 * <p>
 * Callers from multiple threads serialize on the instance monitor, so the
 * sustained rate applies globally across all consumers of a single limiter.
 */
public final class TokenBucketRateLimiter {

    private final double capacity;
    private final double refillPerSecond;
    private double tokens;
    private long lastRefillNanos;

    public TokenBucketRateLimiter(double capacity, double refillPerSecond) {
        if (capacity <= 0 || refillPerSecond <= 0) {
            throw new IllegalArgumentException("capacity and refillPerSecond must be positive");
        }
        this.capacity = capacity;
        this.refillPerSecond = refillPerSecond;
        this.tokens = capacity;
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Block until one token is available, then consume it. The monitor is
     * held during the sleep so other callers queue up behind the current one
     * — that is the rate-limiting behavior, not a bug.
     */
    public synchronized void acquire() throws InterruptedException {
        while (true) {
            refill();
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return;
            }
            double need = 1.0 - tokens;
            long sleepMs = Math.max(5, (long) Math.ceil(need / refillPerSecond * 1000.0));
            Thread.sleep(sleepMs);
        }
    }

    private void refill() {
        long now = System.nanoTime();
        double elapsedSec = (now - lastRefillNanos) / 1_000_000_000.0;
        if (elapsedSec > 0) {
            tokens = Math.min(capacity, tokens + elapsedSec * refillPerSecond);
            lastRefillNanos = now;
        }
    }
}
