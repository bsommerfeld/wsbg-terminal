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

    @Key("active-millis")
    @Comment("Accurate cumulative time the app has been open (milliseconds), "
            + "measured by TimeTracker from start/interval/stop timestamp deltas "
            + "(not tick-counted, so it survives crashes and ignores machine sleep). "
            + "Drives when the footer donation banner first appears.")
    private long activeMillis = 0;

    @Key("donation-unlock-hours")
    @Comment("Hours of cumulative active time before the footer donation banner "
            + "is shown (default: 12). New users aren't asked to donate until the "
            + "terminal has plausibly paid off. Set to 0 to show it immediately. "
            + "(The persistent heart icon is always visible regardless; this only "
            + "gates the active nudge layer — the rotating footer banner.)")
    private double donationUnlockHours = 12.0;

    @Key("donation-snooze-until")
    @Comment("Epoch millis until which the active donation nudge layer (the "
            + "rotating footer banner) stays suppressed. Set when the user clicks "
            + "a banner link — the nudge was answered, so it rests for ~2 days. "
            + "Clicking the heart icon does NOT snooze. 0 = not snoozed. The "
            + "heart icon stays visible throughout.")
    private long donationSnoozeUntil = 0;

    @Key("donation-clicked")
    @Comment("Whether the user has ever clicked through to the donate page "
            + "(the heart or a banner link). Honor system — no accounts, no "
            + "verification: the click gilds the heart icon permanently. Does "
            + "not change any banner behaviour.")
    private boolean donationClicked = false;

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

    public long getActiveMillis() {
        return activeMillis;
    }

    public void setActiveMillis(long activeMillis) {
        this.activeMillis = activeMillis;
    }

    public double getDonationUnlockHours() {
        return donationUnlockHours;
    }

    public void setDonationUnlockHours(double donationUnlockHours) {
        this.donationUnlockHours = donationUnlockHours;
    }

    public long getDonationSnoozeUntil() {
        return donationSnoozeUntil;
    }

    public void setDonationSnoozeUntil(long donationSnoozeUntil) {
        this.donationSnoozeUntil = donationSnoozeUntil;
    }

    public boolean isDonationClicked() {
        return donationClicked;
    }

    public void setDonationClicked(boolean donationClicked) {
        this.donationClicked = donationClicked;
    }
}
