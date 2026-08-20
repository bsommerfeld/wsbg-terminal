package de.bsommerfeld.wsbg.terminal.web.impl.sources.hkex;

import java.time.LocalDate;
import java.util.List;

/**
 * One Hong Kong listing's daily history off the HKEX website's own chart
 * service - roughly TEN YEARS of venue-native OHLCV bars, the deepest
 * keyless series an HK listing has in this house. {@code ric} is the
 * exchange's own zero-padded form ({@code 0700.HK}).
 *
 * <p>The service stamps each bar with midnight Asia/Hong_Kong as epoch
 * millis; the dates here are read in that zone, never the JVM's.
 *
 * @param bars daily bars, OLDEST first - the service pads the series with
 *        all-null placeholder rows at both ends, those never make it here
 */
public record HkexHistory(String ric, List<HkexBar> bars) {

    /** One trading day as the exchange wrote it. */
    public record HkexBar(LocalDate date, double open, double high, double low,
            double close, double volume) {}
}
