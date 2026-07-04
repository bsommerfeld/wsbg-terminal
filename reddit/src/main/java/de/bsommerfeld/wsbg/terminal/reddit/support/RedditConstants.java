package de.bsommerfeld.wsbg.terminal.reddit.support;

import de.bsommerfeld.wsbg.terminal.reddit.RedditUserAgent;

import java.time.Duration;
import java.util.Map;

/**
 * Constants shared by both Reddit sources (the JSON-backed {@code RedditScraper}
 * and the Atom-backed {@code RssRedditScraper}). Previously duplicated verbatim
 * in each scraper.
 */
public final class RedditConstants {

    private RedditConstants() {}

    /**
     * Thread permanently excluded from scraping.
     * ID: {@code t3_nwvkto} — "Willkommen im r/wallstreetbetsGER LiveChat!"
     * It has a massive comment history with extremely low signal-to-noise
     * ratio, causing unnecessary load and noise during analysis.
     */
    public static final String BLACKLISTED_THREAD_ID = "t3_nwvkto";

    /**
     * Per-request ceiling. Deep comment fetches are the slow case (large body,
     * chunked back over the browser router); 30 s is generous headroom over a
     * healthy fetch while still failing fast enough for a fallback to move on.
     */
    public static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /**
     * Headers applied to every request. The direct transport sends these
     * verbatim; the browser/OAuth transports ignore them and set their own
     * {@code User-Agent}. The per-process {@code instance:} token keeps installs
     * off a shared rate budget.
     */
    public static final Map<String, String> REQUEST_HEADERS =
            Map.of("User-Agent", RedditUserAgent.VALUE);
}
