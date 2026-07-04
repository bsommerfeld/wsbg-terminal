package de.bsommerfeld.wsbg.terminal.reddit.support;

import de.bsommerfeld.wsbg.terminal.reddit.TokenBucketRateLimiter;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;

/**
 * Single outbound chokepoint for a Reddit source: acquires a token from the
 * source's own {@link TokenBucketRateLimiter}, performs the fetch, then applies
 * Reddit's own {@code x-ratelimit-*} preemptive backoff.
 *
 * <p>The limiter is <b>instance-scoped per source</b> — each source constructs
 * its own guard around its own limiter. Reddit's volume cap is per-IP, so the
 * anonymous and RSS paths still effectively share one real budget; that sharing
 * is intentional and stays at the limiter level, never hoisted to a singleton
 * here.
 */
public final class RateLimitGuard {

    private static final Logger LOG = LoggerFactory.getLogger(RateLimitGuard.class);

    private final TokenBucketRateLimiter rateLimiter;

    public RateLimitGuard(TokenBucketRateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    /**
     * Executes a GET through the given {@link WebFetcher} with automatic
     * rate-limit handling: a token is acquired before the send, and the
     * response headers are inspected for a near-limit backoff afterwards.
     */
    public WebResponse execute(WebFetcher fetcher, String url,
            Map<String, String> headers, Duration timeout) throws Exception {
        rateLimiter.acquire();
        WebResponse response = fetcher.fetch(url, headers, timeout);
        applyBackoff(response);
        return response;
    }

    /**
     * Inspects Reddit's rate-limit response headers. If fewer than 2 requests
     * remain in the current window, the thread blocks for the
     * {@code x-ratelimit-reset} duration plus 1 second of safety margin.
     *
     * <p>This preemptive backoff prevents the next request from receiving a 429,
     * which would impose a longer ban. Parsing failures in the header values are
     * silently ignored — a malformed header should not crash the scraper.
     */
    private void applyBackoff(WebResponse response) {
        response.header("x-ratelimit-remaining").ifPresent(remaining -> {
            try {
                if (Double.parseDouble(remaining) < 2.0) {
                    response.header("x-ratelimit-reset").ifPresent(reset -> {
                        int waitSecs = (int) Double.parseDouble(reset) + 1;
                        LOG.warn("Reddit rate limit near. Sleeping for {}s", waitSecs);
                        try {
                            Thread.sleep(waitSecs * 1000L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                }
            } catch (NumberFormatException e) {
                // Malformed header — ignore
            }
        });
    }
}
