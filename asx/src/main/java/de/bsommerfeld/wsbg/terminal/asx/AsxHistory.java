package de.bsommerfeld.wsbg.terminal.asx;

import java.time.LocalDate;
import java.util.List;

/**
 * One ASX listing's daily history off Markit's chartworks backend - up to
 * roughly TWENTY YEARS of venue-native OHLCV in AUD, the deepest keyless
 * series any listing has in this house.
 *
 * @param bars daily bars, OLDEST first
 */
public record AsxHistory(String symbol, String xid, String currency,
        List<AsxBar> bars) {

    /** One trading day as the exchange wrote it. */
    public record AsxBar(LocalDate date, double open, double high, double low,
            double close, double volume) {}
}
