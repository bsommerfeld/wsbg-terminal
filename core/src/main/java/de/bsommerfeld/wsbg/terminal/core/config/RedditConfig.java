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
    @Comment("Interval in seconds between Reddit scans (default: 60)")
    private long updateIntervalSeconds = 60;

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
    @Comment("Vector similarity threshold for clustering (default: 0.55)")
    private double similarityThreshold = 0.55;

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
}
