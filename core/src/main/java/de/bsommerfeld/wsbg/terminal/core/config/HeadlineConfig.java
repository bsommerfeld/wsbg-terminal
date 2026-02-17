package de.bsommerfeld.wsbg.terminal.core.config;

import de.bsommerfeld.jshepherd.annotation.Comment;
import de.bsommerfeld.jshepherd.annotation.Key;

import java.util.ArrayList;
import java.util.List;

/**
 * User-facing headline filter configuration. Controls whether the passive
 * monitor delivers headlines and which topics the user is interested in.
 */
public class HeadlineConfig {

    @Key("enabled")
    @Comment("Enable or disable headline generation entirely (default: true)")
    private boolean enabled = true;

    @Key("show-all")
    @Comment("If true, deliver all headlines regardless of topic filter (default: true)")
    private boolean showAll = true;

    @Key("topics")
    @Comment("List of topics the user wants headlines for (e.g., Gold, Zinsen, Trump). Ignored when show-all is true.")
    private List<String> topics = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isShowAll() {
        return showAll;
    }

    public void setShowAll(boolean showAll) {
        this.showAll = showAll;
    }

    public List<String> getTopics() {
        return topics;
    }

    public void setTopics(List<String> topics) {
        this.topics = topics;
    }
}
