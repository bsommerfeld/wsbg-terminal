package de.bsommerfeld.wsbg.terminal.six;

import java.time.LocalDate;
import java.util.List;

/**
 * One Swiss listing's daily history off the SIX website's own charting
 * service - venue-native OHLCV, a decade deep, netted to daily bars
 * ({@code netting=1440}).
 *
 * @param bars daily bars, OLDEST first - the only keyless venue-native OHLCV
 *        series a Swiss listing has in this house
 */
public record SixHistory(String isin, List<SixBar> bars) {

    /**
     * One trading day as the exchange wrote it. {@code volume} is shares
     * traded ({@code NaN} when the service omitted it).
     */
    public record SixBar(LocalDate date, double open, double high, double low,
            double close, double volume) {}
}
