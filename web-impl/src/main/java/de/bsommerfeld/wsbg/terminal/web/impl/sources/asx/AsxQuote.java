package de.bsommerfeld.wsbg.terminal.web.impl.sources.asx;

/**
 * One listing's reading off the ASX website's own Markit backend - the
 * exchange's numbers in AUD, not an aggregator's. Merged from TWO cheap
 * keyless calls: {@code /companies/<code>/header} (price, book, volume,
 * market cap and the internal {@code xid}) and
 * {@code /companies/<code>/key-statistics} (ISIN, average volume, EPS,
 * dividend).
 *
 * <p>{@code xid} is Markit's internal instrument id ({@code BHP} →
 * {@code 60947}) - the chart backend answers ONLY to it, never to the
 * ticker, so it travels with the quote.
 *
 * <p>Missing numbers are {@link Double#NaN} (counts {@code -1}), never zero -
 * a zero bid is a market statement, an absent one is not.
 */
public record AsxQuote(String symbol, String name, String xid,
        double priceLast, double bid, double ask,
        long volume, long marketCap,
        String isin, double averageVolume, double eps, double dividend) {}
