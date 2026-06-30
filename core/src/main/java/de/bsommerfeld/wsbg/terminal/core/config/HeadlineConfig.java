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

    @Key("cluster-theme-enabled")
    @Comment("When true, each dirty cluster (= one Reddit thread) also gets a "
            + "'theme' headline capturing the whole thread's narrative, on top of the "
            + "per-subject lines. Default false: every thread would otherwise produce a "
            + "line (incl. chatty/meme threads with no instrument), which floods the wire "
            + "with generic lines and overlaps the per-subject headlines. Opt in for the "
            + "richer thread-narrative coverage.")
    private boolean clusterThemeEnabled = false;

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
    private boolean newsCoverageEnabled = false;

    @Key("suppress-redundant")
    @Comment("When true (default), a re-composed line that just repeats a subject's own recent "
            + "headline is suppressed — both the model's redundant-UPDATE empty AND the "
            + "near-duplicate guard. When false, the wire is a strict 1:1 mirror: every dirty "
            + "signal writes a line, even a duplicate, with no redundancy filtering. (A first "
            + "line of a subject is ALWAYS written regardless of this setting.)")
    private boolean suppressRedundant = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isClusterThemeEnabled() {
        return clusterThemeEnabled;
    }

    public void setClusterThemeEnabled(boolean clusterThemeEnabled) {
        this.clusterThemeEnabled = clusterThemeEnabled;
    }

    public boolean isAnalyzeImages() {
        return analyzeImages;
    }

    public void setAnalyzeImages(boolean analyzeImages) {
        this.analyzeImages = analyzeImages;
    }

    public boolean isNewsCoverageEnabled() {
        return newsCoverageEnabled;
    }

    public void setNewsCoverageEnabled(boolean newsCoverageEnabled) {
        this.newsCoverageEnabled = newsCoverageEnabled;
    }

    public boolean isSuppressRedundant() {
        return suppressRedundant;
    }

    public void setSuppressRedundant(boolean suppressRedundant) {
        this.suppressRedundant = suppressRedundant;
    }
}
