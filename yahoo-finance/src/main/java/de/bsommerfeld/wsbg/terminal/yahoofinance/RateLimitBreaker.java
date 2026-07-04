package de.bsommerfeld.wsbg.terminal.yahoofinance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

/**
 * Rate-limit circuit breaker for the Yahoo endpoints. A comment-heavy cluster
 * fans out into dozens of per-subject searches; once Yahoo answers one with 429
 * it will 429 the rest, so the first 429 OPENS the breaker and every subsequent
 * Yahoo call short-circuits (no HTTP) until the cooldown passes. The caller is
 * told (via {@code SearchResult.rateLimited()}) so it can SKIP the subject
 * (retry on next evidence) rather than cement a wrong tickerless unit — and we
 * never cascade dozens more 429s. Closed when {@code rateLimitedUntil == 0}.
 */
final class RateLimitBreaker {

    private static final Logger LOG = LoggerFactory.getLogger(RateLimitBreaker.class);

    private static final long RATE_LIMIT_COOLDOWN_SECONDS = 90;

    private volatile long rateLimitedUntil = 0L;

    boolean isOpen() {
        return Instant.now().getEpochSecond() < rateLimitedUntil;
    }

    void trip(String what, int code) {
        rateLimitedUntil = Instant.now().getEpochSecond() + RATE_LIMIT_COOLDOWN_SECONDS;
        LOG.warn("Yahoo {} → HTTP {}; opening rate-limit breaker for {}s (skipping further Yahoo calls)",
                what, code, RATE_LIMIT_COOLDOWN_SECONDS);
    }

    /** Yahoo status codes that mean "back off", not "not found". */
    static boolean isRateLimitStatus(int code) {
        return code == 429 || code == 503 || code == 999;
    }
}
