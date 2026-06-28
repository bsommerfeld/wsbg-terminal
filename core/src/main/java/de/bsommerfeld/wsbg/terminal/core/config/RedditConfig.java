package de.bsommerfeld.wsbg.terminal.core.config;

import de.bsommerfeld.jshepherd.annotation.Comment;
import de.bsommerfeld.jshepherd.annotation.Key;
import java.util.List;

/**
 * Reddit monitoring parameters. Values are persisted in config.toml
 * and loaded at startup — setters only exist for the config framework.
 */
public class RedditConfig {

    @Key("subreddits")
    @Comment("List of subreddits to scan")
    private List<String> subreddits = List.of("wallstreetbetsGER");

    @Key("update-interval-seconds")
    @Comment("Interval in seconds between Reddit scans (default: 180). "
            + "Bumped from 60s to keep the per-IP request budget under "
            + "the unauthenticated Reddit JSON throttle (~100 req / 10 min). "
            + "TODO(oauth-login): when the scraper switches to OAuth "
            + "(oauth.reddit.com, 600 req/min budget), the safe default "
            + "drops back to 30-60s without rate-limit risk.")
    private long updateIntervalSeconds = 180;

    @Key("data-retention-hours")
    @Comment("Hours to keep Reddit data in database (default: 6)")
    private long dataRetentionHours = 6;

    @Key("significance-threshold")
    @Comment("Score threshold for AI reporting (default: 10.0)")
    private double significanceThreshold = 10.0;

    @Key("investigation-ttl-minutes")
    @Comment("Time to live for an investigation in minutes (default: 60)")
    private long investigationTtlMinutes = 60;

    @Key("similarity-threshold")
    @Comment("Vector similarity threshold for cluster assignment (default: 0.55). "
            + "Lower = more aggressive grouping (risks an over-merged hype blob); "
            + "higher = stricter, more single-thread clusters. Ticker overlap forces "
            + "a match regardless. Threads that match nothing become their own cluster.")
    private double similarityThreshold = 0.55;

    @Key("oauth-client-id")
    @Comment("Reddit OAuth client ID of an \"installed app\" registered at "
            + "https://www.reddit.com/prefs/apps (no secret needed). The app "
            + "authenticates application-only (\"userless\") against "
            + "oauth.reddit.com — end users never log in. Required: without it "
            + "the scraper cannot reach Reddit, since the public .json endpoint "
            + "rejects bare headless clients with a 403.")
    private String oauthClientId = "";

    @Key("rate-limit-requests-per-second")
    @Comment("Sustained ANONYMOUS Reddit HTTP request rate (default: 0.15), "
            + "used by the RSS and JSON sources. Reddit's anonymous endpoints "
            + "allow ~100 req / 10 min per IP+UA pair; 0.15 req/s = ~90 / 10 min "
            + "keeps us just under it. OAuth uses its own faster limit below.")
    private double rateLimitRequestsPerSecond = 0.15;

    @Key("rate-limit-burst")
    @Comment("Burst capacity for the anonymous (RSS/JSON) rate limiter "
            + "(default: 5). Smooths the startup scan; sustained throughput is "
            + "still capped by rate-limit-requests-per-second once it drains.")
    private double rateLimitBurst = 5.0;

    @Key("oauth-rate-limit-requests-per-second")
    @Comment("Sustained request rate for the OAUTH source (default: 8.0). "
            + "Application-only OAuth gets a 600 req/min budget (=10 req/s); "
            + "8.0 req/s = 480/min leaves headroom. Because OAuth is a separate "
            + "transport from the anonymous sources, it can run much faster — "
            + "the RSS/JSON sources stay throttled, OAuth does not.")
    private double oauthRateLimitRequestsPerSecond = 8.0;

    @Key("oauth-rate-limit-burst")
    @Comment("Burst capacity for the OAUTH rate limiter (default: 20).")
    private double oauthRateLimitBurst = 20.0;

    @Key("browser-rate-limit-requests-per-second")
    @Comment("Sustained request rate for the BROWSER source (default: 6.0). The "
            + "embedded browser carries a real session (cookies + an established "
            + "browser context), so it is NOT held to the conservative anonymous "
            + ".json rate (0.15/s) that was tuned for the headless path. A real "
            + "browser session sustains far more; 6.0/s lets "
            + "the full-fidelity deep fetches (?limit=500&depth=20) on cold start "
            + "complete in seconds instead of minutes, with NO loss of data. A 429 "
            + "self-heals (re-anchor); dial down if the IP is throttled persistently.")
    private double browserRateLimitRequestsPerSecond = 6.0;

    @Key("browser-rate-limit-burst")
    @Comment("Burst capacity for the BROWSER rate limiter (default: 20).")
    private double browserRateLimitBurst = 20.0;

    @Key("snapshot-ttl-minutes")
    @Comment("How long an on-disk Reddit snapshot stays valid across restarts "
            + "(default: 60, set 0 to disable). On startup, if a snapshot newer "
            + "than this exists, threads+comments are restored from it instead "
            + "of being re-fetched — so a quick restart (e.g. during testing) "
            + "skips the expensive cold-start scan. Older snapshots are discarded "
            + "to avoid ghost clusters from posts that have since disappeared.")
    private long snapshotTtlMinutes = 60;

    public List<String> getSubreddits() {
        return subreddits;
    }

    public long getUpdateIntervalSeconds() {
        return updateIntervalSeconds;
    }

    public long getDataRetentionHours() {
        return dataRetentionHours;
    }

    public double getSignificanceThreshold() {
        return significanceThreshold;
    }

    public long getInvestigationTtlMinutes() {
        return investigationTtlMinutes;
    }

    public double getSimilarityThreshold() {
        return similarityThreshold;
    }

    public String getOauthClientId() {
        return oauthClientId;
    }

    public double getRateLimitRequestsPerSecond() {
        return rateLimitRequestsPerSecond;
    }

    public double getRateLimitBurst() {
        return rateLimitBurst;
    }

    public double getOauthRateLimitRequestsPerSecond() {
        return oauthRateLimitRequestsPerSecond;
    }

    public double getOauthRateLimitBurst() {
        return oauthRateLimitBurst;
    }

    public double getBrowserRateLimitRequestsPerSecond() {
        return browserRateLimitRequestsPerSecond;
    }

    public double getBrowserRateLimitBurst() {
        return browserRateLimitBurst;
    }

    public long getSnapshotTtlMinutes() {
        return snapshotTtlMinutes;
    }
}
