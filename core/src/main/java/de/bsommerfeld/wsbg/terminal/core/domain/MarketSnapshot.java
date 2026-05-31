package de.bsommerfeld.wsbg.terminal.core.domain;

import java.util.List;

/**
 * A point-in-time market snapshot for a single instrument, sourced from
 * Yahoo Finance's {@code v8/finance/chart} endpoint.
 *
 * <p>
 * One chart call yields everything here in a single round-trip: the
 * {@code meta} block gives the scalar quote fields (price, day range,
 * 52-week range, volume), and the intraday {@code close} series gives
 * {@link #spark()} — the points the UI draws as a micro-sparkline next to
 * the headline.
 *
 * <p>
 * Missing scalar fields are {@link Double#NaN} (Yahoo omits e.g. day
 * high/low for thinly traded names); {@link #volume()} is {@code -1} when
 * unknown. {@link #spark()} is never null but may be empty when the
 * intraday series was unavailable. {@link #dayChangePercent()} is
 * pre-computed from {@code price} vs {@code previousClose} so the agent
 * and the UI render the exact same number.
 */
public record MarketSnapshot(
        String symbol,
        double price,
        double previousClose,
        double dayChangePercent,
        double dayHigh,
        double dayLow,
        long volume,
        double fiftyTwoWeekHigh,
        double fiftyTwoWeekLow,
        String currency,
        String exchangeName,
        long marketTimeEpochSeconds,
        List<Double> spark) {

    public MarketSnapshot {
        spark = spark == null ? List.of() : List.copyOf(spark);
    }

    /** True when the snapshot carries a usable last price. */
    public boolean hasPrice() {
        return Double.isFinite(price);
    }

    /** True when there are at least two points to draw a sparkline from. */
    public boolean hasSpark() {
        return spark.size() >= 2;
    }
}
