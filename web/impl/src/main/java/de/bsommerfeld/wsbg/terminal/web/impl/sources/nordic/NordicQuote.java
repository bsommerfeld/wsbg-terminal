package de.bsommerfeld.wsbg.terminal.web.impl.sources.nordic;

/**
 * One Nordic listing's live reading off Nasdaq's own Nordic instrument
 * service - the exchange's numbers for Stockholm, Helsinki, Copenhagen and
 * Iceland, not an aggregator's. {@code exchange} is the venue as the service
 * names it ({@code Nasdaq Stockholm}, ...), {@code currency} the listing's
 * trading currency as the service prefixes it ({@code SEK}, {@code DKK},
 * {@code EUR}, {@code ISK}) - prices are ALWAYS in that currency, never
 * converted.
 *
 * <p>Missing numbers are {@link Double#NaN} (sizes/volumes {@code -1}), never
 * zero - a zero bid is a market statement, an absent one is not.
 */
public record NordicQuote(String symbol, String isin, String name,
        String exchange, String segment, String currency,
        double last, double netChange, double changePercent,
        double bid, double ask, long bidSize, long askSize,
        long volume,
        double dayHigh, double dayLow,
        double yearHigh, double yearLow) {}
