package de.bsommerfeld.wsbg.terminal.web.impl.net;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * The proactive half of the house's politeness: a per-host budget that holds
 * BEFORE a socket is opened, where {@link HouseFetcher}'s cooldowns only react
 * after a host has already said 429.
 *
 * <p>Two limits ride together, because hosts punish two different things:
 * <ul>
 *   <li><b>Concurrency</b> — how many requests may be in flight at once. This
 *       is what a WAF sees: the fan opens 28 sources on unbounded virtual
 *       threads, and forty sockets inside a hundred milliseconds reads as an
 *       attack no matter how modest the hourly average is.</li>
 *   <li><b>Sustained rate</b> — a token bucket, refilled continuously. The
 *       capacity lets a short burst through (a collector pass over a host's
 *       twelve feeds) while the refill caps the long run.</li>
 * </ul>
 *
 * <p>A caller that cannot get through within its wait budget is turned away
 * rather than queued forever — the fan would otherwise convert a throttled
 * host into a stalled compose.
 */
final class HostGate {

    private final Semaphore slots;
    private final double capacity;
    private final double refillPerSecond;
    private double tokens;
    private long lastRefillNanos;

    HostGate(int concurrency, double refillPerSecond, double burst) {
        this.slots = new Semaphore(concurrency, true);
        this.capacity = burst;
        this.refillPerSecond = refillPerSecond;
        this.tokens = burst;
        this.lastRefillNanos = System.nanoTime();
    }

    /**
     * Takes a concurrency slot AND a rate token, or gives up.
     *
     * @return {@code true} when the caller may proceed — it MUST then call
     *         {@link #release()}; {@code false} when the budget was not free
     *         within {@code waitMs}.
     */
    boolean acquire(long waitMs) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + waitMs * 1_000_000L;
        if (!slots.tryAcquire(waitMs, TimeUnit.MILLISECONDS)) return false;
        try {
            if (takeToken(deadlineNanos)) return true;
        } catch (InterruptedException e) {
            slots.release();
            throw e;
        }
        slots.release();
        return false;
    }

    void release() {
        slots.release();
    }

    /**
     * The monitor is held across the sleep so waiting callers queue up behind
     * the current one — that IS the pacing, not a bug (same economy as the
     * reddit-side bucket).
     */
    private synchronized boolean takeToken(long deadlineNanos) throws InterruptedException {
        while (true) {
            long now = System.nanoTime();
            double elapsedSec = (now - lastRefillNanos) / 1_000_000_000.0;
            if (elapsedSec > 0) {
                tokens = Math.min(capacity, tokens + elapsedSec * refillPerSecond);
                lastRefillNanos = now;
            }
            if (tokens >= 1.0) {
                tokens -= 1.0;
                return true;
            }
            double need = 1.0 - tokens;
            long sleepMs = Math.max(5, (long) Math.ceil(need / refillPerSecond * 1000.0));
            if (now + sleepMs * 1_000_000L > deadlineNanos) return false;
            Thread.sleep(sleepMs);
        }
    }
}
