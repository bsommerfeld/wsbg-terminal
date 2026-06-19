package de.bsommerfeld.wsbg.terminal.reddit;

/**
 * Abstraction over the mechanism that actually fetches a Reddit URL.
 *
 * <p>
 * The default {@link JdkRedditTransport} uses a plain JDK {@code HttpClient}.
 * Reddit often returns HTTP 403 (a Cloudflare interstitial) to that bare client
 * on the {@code .json} API endpoint while serving a real browser normally, so
 * the production wiring swaps in a transport backed by the embedded
 * JCEF/Chromium runtime, which carries the standard {@code cf_clearance} cookie
 * and a browser session. Keeping this as an interface lets the
 * {@link RedditScraper} — and the {@code reddit} module as a whole — stay free
 * of any browser dependency.
 *
 * <p>
 * Implementations must be thread-safe: the scraper may call {@link #get(String)}
 * from its scanner and analysis executors. Rate limiting lives in the scraper,
 * not here, so implementations should not throttle on their own.
 */
public interface RedditTransport {

    /**
     * Performs a GET against the given absolute Reddit URL and returns the
     * response. Implementations should not throw for ordinary HTTP error
     * statuses (e.g. 403, 429) — those belong in the returned
     * {@link RedditResponse#statusCode()}. Throwing is reserved for transport
     * failures (timeouts, interrupts, browser not ready).
     */
    RedditResponse get(String url) throws Exception;
}
