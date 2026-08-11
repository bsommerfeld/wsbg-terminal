package de.bsommerfeld.wsbg.terminal.web.impl.sources.euronext;

import java.time.LocalDate;
import java.util.List;

/**
 * One listing's price history off Euronext's own website chart service - the
 * exchange's numbers, not an aggregator's, and for a Dutch/French/Belgian
 * listing they reach back to roughly 2001 ({@code max} period).
 *
 * <p>The decrypted wire carries exactly THREE fields per bar ({@code time},
 * {@code price}, {@code volume}) - one close and one volume per trading day,
 * no OHLC. {@code open}/{@code high}/{@code low} are therefore always
 * {@link Double#NaN} and stay in the record only so a Euronext bar reads like
 * every other bar in this house. Volume arrives fractional on old bars
 * ({@code 5246823.9}) - the service averages somewhere - so it stays a
 * double, {@code NaN} when absent.
 *
 * @param bars daily bars, OLDEST first - the only keyless venue-native price
 *        series a Euronext listing has in this house
 */
public record EuronextHistory(String isin, String mic, String period,
        List<EuronextBar> bars) {

    /**
     * One trading day as the exchange wrote it. Only {@code close} and
     * {@code volume} are real wire values - OHL are {@link Double#NaN} by
     * contract (see {@link EuronextHistory}).
     */
    public record EuronextBar(LocalDate date, double open, double high,
            double low, double close, double volume) {}
}
