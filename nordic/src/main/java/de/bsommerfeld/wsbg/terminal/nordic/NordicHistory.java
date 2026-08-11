package de.bsommerfeld.wsbg.terminal.nordic;

import java.time.LocalDate;
import java.util.List;

/**
 * One Nordic listing's daily tape off Nasdaq's own Nordic chart service -
 * venue-native OHLCV, roughly TEN YEARS deep, in the listing's trading
 * currency ({@code currency}), never converted.
 *
 * @param bars daily bars, OLDEST first - the deepest keyless venue-native
 *        series a Nordic listing has in this house
 */
public record NordicHistory(String symbol, String isin, String currency,
        List<NordicBar> bars) {

    /** One trading day as the exchange wrote it. */
    public record NordicBar(LocalDate date, double open, double high,
            double low, double close, double volume) {}
}
