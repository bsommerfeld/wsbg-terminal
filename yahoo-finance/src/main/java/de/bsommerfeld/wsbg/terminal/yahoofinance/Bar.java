package de.bsommerfeld.wsbg.terminal.yahoofinance;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * One OHLCV candle from the {@code v8/chart} endpoint — the market memory's
 * price axis (daily bars for the event-study CAR arithmetic, hourly bars for
 * the volume profile). Values are Yahoo's split-adjusted series; comparisons
 * across sources must therefore run on RETURNS, never on levels (adjustment
 * bases differ in old decades — probed 2026-07-14).
 *
 * @param epochSeconds bar start, Unix seconds (Yahoo's own timestamps)
 * @param open         open, NaN when the exchange shipped none
 * @param high         high, NaN when absent
 * @param low          low, NaN when absent
 * @param close        close — always finite (bars without a close are skipped)
 * @param volume       traded units, 0 when absent
 */
public record Bar(long epochSeconds, double open, double high, double low,
        double close, long volume) {

    /** The bar's UTC trading date. */
    public LocalDate date() {
        return Instant.ofEpochSecond(epochSeconds).atZone(ZoneOffset.UTC).toLocalDate();
    }
}
