package de.bsommerfeld.wsbg.terminal.reddit;

/**
 * Abstraction over the mechanism that actually fetches a Reddit URL.
 *
 * <p>
 * The default {@link JdkRedditTransport} uses a plain JDK {@code HttpClient}.
 * That path is blocked by Reddit's bot detection on the {@code .json} API
 * endpoint (HTTP 403 with a Cloudflare block page) for anything that isn't a
 * real browser, so the production wiring swaps in a transport backed by the
 * embedded JCEF/Chromium runtime which carries the {@code cf_clearance} cookie
 * and a browser TLS fingerprint. Keeping this as an interface lets the
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
