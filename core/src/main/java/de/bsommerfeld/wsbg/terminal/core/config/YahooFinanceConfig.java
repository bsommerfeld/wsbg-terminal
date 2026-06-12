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
    @Comment("How long responses are cached in memory before re-fetching. 120s so "
            + "the dedupe survives a slow streaming editorial pass (one focused "
            + "compose call per subject can stretch a cluster over minutes), while "
            + "keeping published prices near-live.")
    private long cacheTtlSeconds = 120;

    @Key("yahoo.request-timeout-seconds")
    @Comment("Per-request HTTP timeout when talking to Yahoo Finance endpoints.")
    private int requestTimeoutSeconds = 10;

    @Key("yahoo.browser-fetch-enabled")
    @Comment("Route Yahoo requests through the embedded browser first (carries a "
            + "real browser fingerprint + cookies, clearing the IP/429 block that "
            + "hits the plain HTTP client), falling back to direct HTTP. Turn off "
            + "to use direct HTTP only.")
    private boolean browserFetchEnabled = true;

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

    public boolean isBrowserFetchEnabled() {
        return browserFetchEnabled;
    }

    public void setBrowserFetchEnabled(boolean browserFetchEnabled) {
        this.browserFetchEnabled = browserFetchEnabled;
    }
}
