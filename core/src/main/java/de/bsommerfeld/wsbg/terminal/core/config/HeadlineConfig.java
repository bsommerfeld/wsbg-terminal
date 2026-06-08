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

    @Key("news-coverage-enabled")
    @Comment("When true, a news item already cited by one headline of a subject is "
            + "hidden from that subject's next compose (no two headlines on the same "
            + "news). Default false: news is enrichment and may legitimately back "
            + "several headlines on the same topic; reuse is free since news is cached.")
    private boolean newsCoverageEnabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isNewsCoverageEnabled() {
        return newsCoverageEnabled;
    }

    public void setNewsCoverageEnabled(boolean newsCoverageEnabled) {
        this.newsCoverageEnabled = newsCoverageEnabled;
    }
}
