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
        List<Double> spark,
        List<Double> dailyCloses) {

    public MarketSnapshot {
        spark = spark == null ? List.of() : List.copyOf(spark);
        dailyCloses = dailyCloses == null ? List.of() : List.copyOf(dailyCloses);
    }

    /**
     * Convenience constructor without a daily-close history — every venue except
     * L&S (whose chart endpoint carries a full {@code series=history}) stays a
     * plain thirteen-argument construction.
     */
    public MarketSnapshot(String symbol, double price, double previousClose,
            double dayChangePercent, double dayHigh, double dayLow, long volume,
            double fiftyTwoWeekHigh, double fiftyTwoWeekLow, String currency,
            String exchangeName, long marketTimeEpochSeconds, List<Double> spark) {
        this(symbol, price, previousClose, dayChangePercent, dayHigh, dayLow, volume,
                fiftyTwoWeekHigh, fiftyTwoWeekLow, currency, exchangeName,
                marketTimeEpochSeconds, spark, List.of());
    }

    /**
     * Percent move of the newest daily close vs. {@code days} trading days earlier,
     * or {@code null} when the history is too short — the multi-day context that
     * separates "runs for days, corrects today" from a plain red day. The last
     * history point is today's price, so {@code days=5} reads "vs. five trading
     * days ago".
     */
    public Double changeOverTradingDays(int days) {
        if (days <= 0 || dailyCloses.size() < days + 1) return null;
        double then = dailyCloses.get(dailyCloses.size() - 1 - days);
        double now = dailyCloses.get(dailyCloses.size() - 1);
        if (!Double.isFinite(then) || !Double.isFinite(now) || then <= 0) return null;
        return (now - then) / then * 100.0;
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
