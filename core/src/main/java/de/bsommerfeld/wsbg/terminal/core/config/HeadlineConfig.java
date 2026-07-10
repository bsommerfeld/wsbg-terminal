package de.bsommerfeld.wsbg.terminal.core.config;

import de.bsommerfeld.jshepherd.annotation.Comment;
import de.bsommerfeld.jshepherd.annotation.Key;

/**
 * User-facing headline configuration. Controls whether the passive
 * monitor delivers AI-generated headlines.
 */
public class HeadlineConfig {

    @Key("enabled")
    @Comment("Enable or disable headline generation entirely (default: true)")
    private boolean enabled = true;

    @Key("analyze-images")
    @Comment("When true, the editorial pipeline runs image/vision analysis on Reddit threads and "
            + "comment images, feeding the descriptions into clustering and headlines. Default "
            + "FALSE (2026-06-30): vision is the HEAVIEST prep load on the shared 2-slot gemma4 "
            + "budget, so skipping it frees slots for extraction + compose → noticeably faster "
            + "headlines. Turn it on in Settings for richer image-aware coverage at the cost of "
            + "throughput (no image cache-warming, no gallery transcription when off).")
    private boolean analyzeImages = false;

    @Key("news-coverage-enabled")
    @Comment("When true, a news item already cited by one headline of a subject is "
            + "hidden from that subject's next compose (no two headlines on the same "
            + "news). Default false: news is enrichment and may legitimately back "
            + "several headlines on the same topic; reuse is free since news is cached.")
    private boolean newsCoverageEnabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isAnalyzeImages() {
        return analyzeImages;
    }

    public void setAnalyzeImages(boolean analyzeImages) {
        this.analyzeImages = analyzeImages;
    }

    @Key("read-articles")
    @Comment("When true, the editorial pipeline fetches each news item's FULL article in the "
            + "background and distills it into a few key-fact sentences (one extra model call "
            + "per article, cached per link), so headlines can lean on the article's substance "
            + "instead of its bare title. Default true; turn off to save the per-article fetch "
            + "and model call.")
    private boolean readArticles = true;

    public boolean isNewsCoverageEnabled() {
        return newsCoverageEnabled;
    }

    public void setNewsCoverageEnabled(boolean newsCoverageEnabled) {
        this.newsCoverageEnabled = newsCoverageEnabled;
    }

    public boolean isReadArticles() {
        return readArticles;
    }

    public void setReadArticles(boolean readArticles) {
        this.readArticles = readArticles;
    }

}
