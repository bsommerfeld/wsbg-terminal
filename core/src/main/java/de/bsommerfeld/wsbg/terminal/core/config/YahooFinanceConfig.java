package de.bsommerfeld.wsbg.terminal.core.config;

import de.bsommerfeld.jshepherd.annotation.Comment;
import de.bsommerfeld.jshepherd.annotation.Key;

/**
 * Yahoo Finance client configuration. The Yahoo endpoints we hit are
 * unauthenticated and public, so the only knobs we expose are
 * timeouts, the news-list size, and the cache TTL.
 */
public class YahooFinanceConfig {

    @Key("yahoo.news-count")
    @Comment("Default number of news items returned by getTickerNews. Range 1–10.")
    private int newsCount = 5;

    @Key("yahoo.cache-ttl-seconds")
    @Comment("How long responses are cached in memory before re-fetching. Kept short "
            + "so published prices stay near-live; just long enough to dedupe the "
            + "repeated lookups within one editorial tick (search+chart, resolve+publish).")
    private long cacheTtlSeconds = 60;

    @Key("yahoo.request-timeout-seconds")
    @Comment("Per-request HTTP timeout when talking to Yahoo Finance endpoints.")
    private int requestTimeoutSeconds = 10;

    public int getNewsCount() {
        return newsCount;
    }

    public void setNewsCount(int newsCount) {
        this.newsCount = newsCount;
    }

    public long getCacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public void setCacheTtlSeconds(long cacheTtlSeconds) {
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }
}
