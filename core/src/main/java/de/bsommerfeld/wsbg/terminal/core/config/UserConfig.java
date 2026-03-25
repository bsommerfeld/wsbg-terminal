package de.bsommerfeld.wsbg.terminal.core.config;

import de.bsommerfeld.jshepherd.annotation.Comment;
import de.bsommerfeld.jshepherd.annotation.Key;

/**
 * User-specific preferences. Controls the application's display language
 * and any other user-facing settings.
 */
public class UserConfig {

    @Key("language")
    @Comment("Display language code (e.g., 'de' for German, 'en' for English). Default: 'de'")
    private String language = "de";

    @Key("auto-update")
    @Comment("Check for updates on startup (default: true)")
    private boolean autoUpdate = true;

    @Key("open-count")
    @Comment("How many times the software has been opened")
    private long openCount = 0;

    @Key("first-start-timestamp")
    @Comment("Timestamp of the first start")
    private long firstStartTimestamp = 0;

    @Key("open-minutes")
    @Comment("Total time spent in the software (in minutes)")
    private long openMinutes = 0;

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public boolean isAutoUpdate() {
        return autoUpdate;
    }

    public void setAutoUpdate(boolean autoUpdate) {
        this.autoUpdate = autoUpdate;
    }

    public long getOpenCount() {
        return openCount;
    }

    public void setOpenCount(long openCount) {
        this.openCount = openCount;
    }

    public long getFirstStartTimestamp() {
        return firstStartTimestamp;
    }

    public void setFirstStartTimestamp(long firstStartTimestamp) {
        this.firstStartTimestamp = firstStartTimestamp;
    }

    public long getOpenMinutes() {
        return openMinutes;
    }

    public void setOpenMinutes(long openMinutes) {
        this.openMinutes = openMinutes;
    }
}
