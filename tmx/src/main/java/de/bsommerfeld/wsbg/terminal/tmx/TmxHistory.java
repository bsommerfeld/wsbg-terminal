package de.bsommerfeld.wsbg.terminal.tmx;

import java.time.LocalDate;
import java.util.List;

/**
 * One TMX listing's daily OHLCV series off the exchange website's own GraphQL
 * service - a decade of venue-native bars for a Canadian listing, keyless.
 *
 * @param bars daily bars, OLDEST first (the service answers newest first;
 *        this record has already turned the tape around)
 */
public record TmxHistory(String symbol, List<TmxBar> bars) {

    /** One trading day as the exchange wrote it. */
    public record TmxBar(LocalDate date, double open, double high, double low,
            double close, double volume) {}
}
