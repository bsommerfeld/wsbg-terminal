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

    @Key("experimental-updates")
    @Comment("Which releases this install accepts: 'yes' takes everything the "
            + "moment it is published (pre-releases included), 'no' only finished "
            + "versions. Empty = never asked; the launcher then puts the question "
            + "once, on the next start, and writes the answer here. Leaving the "
            + "experimental channel re-syncs onto the newest stable release, "
            + "which is a downgrade by design.")
    private String experimentalUpdates = "";

    @Key("last-seen-changelog-version")
    @Comment("Version tag whose release notes were already shown in the "
            + "'Was hat sich geändert' overlay. Differs from the installed version "
            + "after an update, which opens the overlay once; closing it stores the "
            + "installed version here. Empty = fresh install (set silently, no overlay).")
    private String lastSeenChangelogVersion = "";

    @Key("last-data-clear-epoch")
    @Comment("Epoch seconds of the last 'Daten löschen' (full terminal wipe). The "
            + "button is gated to once per 10 minutes so a mis-click can't wipe and "
            + "re-wipe before the wire has refilled. 0 = never cleared.")
    private long lastDataClearEpoch = 0;

    @Key("scroll-speed")
    @Comment("Mouse/trackpad scroll speed inside the terminal: pixels per OS "
            + "scroll-line (default: 12.0). The browser renders off-screen, so the "
            + "OS wheel delta is re-scaled here; the OS speed/acceleration setting "
            + "still rides along. Higher = faster. Try 9-10 for slower, ~16 for faster.")
    private double scrollSpeed = 12.0;

    @Key("scroll-invert")
    @Comment("Invert scroll direction (default: true). The default corrects the "
            + "fixed AWT-to-Chromium wheel-sign convention; the OS 'natural "
            + "scrolling' setting is already followed automatically. Flip this only "
            + "if a third-party reverse-scroll tool makes the direction wrong.")
    private boolean scrollInvert = true;

    @Key("frame-rate")
    @Comment("How fast the window may redraw: 'display' follows the monitor's refresh "
            + "rate (default), '60' caps page updates at 60 per second, which is "
            + "lighter on the machine. Only the native shell honours it (macOS).")
    private String frameRate = FRAME_RATE_DISPLAY;

    public static final String FRAME_RATE_DISPLAY = "display";
    public static final String FRAME_RATE_60 = "60";

    public String getFrameRate() {
        return FRAME_RATE_60.equals(frameRate) ? FRAME_RATE_60 : FRAME_RATE_DISPLAY;
    }

    public void setFrameRate(String frameRate) {
        this.frameRate = FRAME_RATE_60.equals(frameRate) ? FRAME_RATE_60 : FRAME_RATE_DISPLAY;
    }

    public double getScrollSpeed() {
        return scrollSpeed;
    }

    public void setScrollSpeed(double scrollSpeed) {
        this.scrollSpeed = scrollSpeed;
    }

    public boolean isScrollInvert() {
        return scrollInvert;
    }

    public void setScrollInvert(boolean scrollInvert) {
        this.scrollInvert = scrollInvert;
    }

    public String getLanguage() {
        return language;
    }

    /** Returns a resolved {@link UserLanguage} with locale and display name. */
    public UserLanguage getUserLanguage() {
        return UserLanguage.of(language);
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

    /** Raw tri-state: {@code "yes"}, {@code "no"}, or empty for "never asked". */
    public String getExperimentalUpdates() {
        return experimentalUpdates;
    }

    public void setExperimentalUpdates(String experimentalUpdates) {
        this.experimentalUpdates = experimentalUpdates;
    }

    /** Whether this install accepts pre-releases. An unanswered question means no. */
    public boolean isExperimentalUpdates() {
        return "yes".equalsIgnoreCase(experimentalUpdates);
    }

    public String getLastSeenChangelogVersion() {
        return lastSeenChangelogVersion;
    }

    public void setLastSeenChangelogVersion(String lastSeenChangelogVersion) {
        this.lastSeenChangelogVersion = lastSeenChangelogVersion;
    }

    public long getLastDataClearEpoch() {
        return lastDataClearEpoch;
    }

    public void setLastDataClearEpoch(long lastDataClearEpoch) {
        this.lastDataClearEpoch = lastDataClearEpoch;
    }
}
