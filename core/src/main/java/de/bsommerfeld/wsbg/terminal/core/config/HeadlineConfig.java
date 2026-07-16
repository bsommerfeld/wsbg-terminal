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
    @Comment("DEAD KEY (2026-07-14): the vision-model toggle this once gated is gone - images "
            + "are read mechanically (OCR) whenever a Tesseract install is found, regardless of "
            + "this setting (VisionPrefetcher ignores the value; the UI toggle was removed). The "
            + "key survives only so old config.toml files keep loading.")
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
