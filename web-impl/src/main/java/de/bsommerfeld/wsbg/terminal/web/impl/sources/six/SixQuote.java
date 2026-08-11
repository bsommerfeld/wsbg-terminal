package de.bsommerfeld.wsbg.terminal.web.impl.sources.six;

/**
 * One Swiss listing's snapshot off the SIX Swiss Exchange website's own quote
 * service - the home exchange's numbers for the CH tape, not an aggregator's.
 * {@code last} is the service's {@code ClosingPrice}, which intraday is the
 * latest (15-minute-delayed) paid price, after the close the settled close.
 *
 * <p>Missing numbers are {@link Double#NaN} (volume {@code -1}), never zero -
 * an absent price is not a price of zero.
 *
 * @param volume the day's {@code TotalVolume} in SHARES traded, not turnover
 *        (verified against price: Nestlé trades millions of shares, not
 *        millions of francs divided by the price)
 */
public record SixQuote(String name, String isin, String symbol,
        double last, long volume,
        double dayHigh, double dayLow, double previousClose) {}
