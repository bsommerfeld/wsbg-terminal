package de.bsommerfeld.wsbg.terminal.core.config;

import de.bsommerfeld.jshepherd.annotation.Comment;
import de.bsommerfeld.jshepherd.annotation.Key;
import de.bsommerfeld.jshepherd.core.ConfigurablePojo; // Assuming this exists or similar base
import java.util.List;

public class RedditConfig {

    @Key("subreddits")
    @Comment("List of subreddits to scan")
    private List<String> subreddits = List.of("wallstreetbetsGER");

    @Key("update-interval-seconds")
    @Comment("Interval in seconds between Reddit scans (default: 60)")
    private long updateIntervalSeconds = 60;

    @Key("max-threads-per-scan")
    @Comment("Maximum number of threads to fetch per scan (default: 25)")
    private int maxThreadsPerScan = 25;

    @Key("cleanup-interval-hours")
    @Comment("Interval in hours for database cleanup (default: 1)")
    private long cleanupIntervalHours = 1;

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

    public void setSubreddits(List<String> subreddits) {
        this.subreddits = subreddits;
    }

    public long getUpdateIntervalSeconds() {
        return updateIntervalSeconds;
    }

    public void setUpdateIntervalSeconds(long updateIntervalSeconds) {
        this.updateIntervalSeconds = updateIntervalSeconds;
    }

    public int getMaxThreadsPerScan() {
        return maxThreadsPerScan;
    }

    public void setMaxThreadsPerScan(int maxThreadsPerScan) {
        this.maxThreadsPerScan = maxThreadsPerScan;
    }

    public long getCleanupIntervalHours() {
        return cleanupIntervalHours;
    }

    public void setCleanupIntervalHours(long cleanupIntervalHours) {
        this.cleanupIntervalHours = cleanupIntervalHours;
    }

    public long getDataRetentionHours() {
        return dataRetentionHours;
    }

    public void setDataRetentionHours(long dataRetentionHours) {
        this.dataRetentionHours = dataRetentionHours;
    }

    public double getSignificanceThreshold() {
        return significanceThreshold;
    }

    public void setSignificanceThreshold(double significanceThreshold) {
        this.significanceThreshold = significanceThreshold;
    }

    public long getInvestigationTtlMinutes() {
        return investigationTtlMinutes;
    }

    public void setInvestigationTtlMinutes(long investigationTtlMinutes) {
        this.investigationTtlMinutes = investigationTtlMinutes;
    }

    public double getSimilarityThreshold() {
        return similarityThreshold;
    }

    public void setSimilarityThreshold(double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
    }
}
