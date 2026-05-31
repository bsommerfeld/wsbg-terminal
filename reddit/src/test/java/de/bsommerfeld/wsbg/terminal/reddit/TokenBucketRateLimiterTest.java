package de.bsommerfeld.wsbg.terminal.reddit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TokenBucketRateLimiterTest {

    @Test
    void burstExhaustsCapacityInstantly() throws InterruptedException {
        // capacity 5 means the first 5 acquires should be near-instant
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(5.0, 1.0);

        long t0 = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            limiter.acquire();
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        assertTrue(elapsedMs < 100, "5 burst acquires should be near-instant, took " + elapsedMs + "ms");
    }

    @Test
    void postBurstAcquiresArePacedByRefillRate() throws InterruptedException {
        // 1 token capacity, 10/s refill -> each acquire after the first ~100ms apart
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1.0, 10.0);

        limiter.acquire(); // consume initial token
        long t0 = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            limiter.acquire();
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        // Expected ~500ms (5 acquires at 100ms each). Wide bounds for CI jitter.
        assertTrue(elapsedMs >= 400, "5 paced acquires should take >=400ms, took " + elapsedMs + "ms");
        assertTrue(elapsedMs < 1500, "5 paced acquires should take <1500ms, took " + elapsedMs + "ms");
    }

    @Test
    void sustainedRateMatchesConfig() throws InterruptedException {
        // 20/s sustained, small capacity to force pacing
        TokenBucketRateLimiter limiter = new TokenBucketRateLimiter(1.0, 20.0);

        limiter.acquire(); // drain burst
        long t0 = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            limiter.acquire();
        }
        long elapsedMs = (System.nanoTime() - t0) / 1_000_000;

        // 20 acquires at 50ms each = ~1000ms target. Loose bounds.
        assertTrue(elapsedMs >= 800, "sustained pacing should approach 1s, took " + elapsedMs + "ms");
        assertTrue(elapsedMs < 2000, "sustained pacing should stay under 2s, took " + elapsedMs + "ms");
    }

    @Test
    void rejectsNonPositiveCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(0.0, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(-1.0, 1.0));
    }

    @Test
    void rejectsNonPositiveRefillRate() {
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(1.0, 0.0));
        assertThrows(IllegalArgumentException.class, () -> new TokenBucketRateLimiter(1.0, -1.0));
    }
}
