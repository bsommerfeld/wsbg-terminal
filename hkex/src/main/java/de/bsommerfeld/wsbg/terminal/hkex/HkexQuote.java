package de.bsommerfeld.wsbg.terminal.hkex;

/**
 * One Hong Kong listing's quote off the HKEX website's own widget service -
 * the exchange's numbers, not an aggregator's. {@code symbol} is the bare
 * board number as HKEX writes it ({@code 700}), {@code currency} the
 * counter's trading currency ({@code HKD}, {@code CNY} for the RMB
 * counters).
 *
 * <p>The service quotes volume, turnover and market cap as compact
 * unit-suffixed strings ({@code "25.66"} + {@code "M"}); this record carries
 * them fully scaled - volume in shares, turnover and market cap in the
 * counter's currency. Missing numbers are {@link Double#NaN} (volume
 * {@code -1}), never zero.
 */
public record HkexQuote(String symbol, String name, String currency,
        double last, double changePercent,
        double dayHigh, double dayLow,
        long volume, double turnover, double marketCap) {}
