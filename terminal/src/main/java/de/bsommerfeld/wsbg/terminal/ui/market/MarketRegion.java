package de.bsommerfeld.wsbg.terminal.ui.market;

import java.time.LocalTime;
import java.time.ZoneId;

/**
 * Static definition of one exchange region: display symbol, local
 * time zone, country code for holiday lookup, and the pre / main / post
 * trading windows in local exchange time.
 *
 * <p>
 * Times are local to {@link #zone()} so the windows are correct
 * year-round (no manual DST handling).
 *
 * <p>
 * An optional midday recess ({@link #breakStart()} / {@link #breakEnd()})
 * splits the main window into two trading segments — the exchange is
 * closed in between. Used for the Tokyo lunch break.
 */
public record MarketRegion(
        String symbol,
        String countryCode,
        ZoneId zone,
        LocalTime preStart,
        LocalTime preEnd,
        LocalTime mainStart,
        LocalTime mainEnd,
        LocalTime breakStart,
        LocalTime breakEnd,
        LocalTime postStart,
        LocalTime postEnd) {

    public static final MarketRegion DE = new MarketRegion(
            "DE", "DE", ZoneId.of("Europe/Berlin"),
            LocalTime.of(7, 30), LocalTime.of(9, 0),    // L&S morning
            LocalTime.of(9, 0),  LocalTime.of(17, 30),  // XETRA
            null, null,
            LocalTime.of(17, 30), LocalTime.of(23, 0)); // L&S evening

    public static final MarketRegion US = new MarketRegion(
            "US", "US", ZoneId.of("America/New_York"),
            LocalTime.of(4, 0),  LocalTime.of(9, 30),   // pre-market
            LocalTime.of(9, 30), LocalTime.of(16, 0),   // NYSE / NASDAQ
            null, null,
            LocalTime.of(16, 0), LocalTime.of(20, 0));  // post-market

    public static final MarketRegion ASIA = new MarketRegion(
            "ASIEN", "JP", ZoneId.of("Asia/Tokyo"),
            null, null,
            LocalTime.of(9, 0), LocalTime.of(15, 30),    // TSE (extended Nov 2024)
            LocalTime.of(11, 30), LocalTime.of(12, 30),  // lunch recess
            null, null);

    public static final MarketRegion AUSTRALIA = new MarketRegion(
            "AUSTRALIEN", "AU", ZoneId.of("Australia/Sydney"),
            LocalTime.of(7, 0),  LocalTime.of(10, 0),   // ASX pre-open auction
            LocalTime.of(10, 0), LocalTime.of(16, 0),   // ASX continuous
            null, null,
            LocalTime.of(16, 0), LocalTime.of(16, 12)); // ASX closing single-price auction

    public boolean hasPre() { return preStart != null && preEnd != null; }
    public boolean hasPost() { return postStart != null && postEnd != null; }
    public boolean hasBreak() { return breakStart != null && breakEnd != null; }
}
