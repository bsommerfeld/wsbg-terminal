package de.bsommerfeld.wsbg.terminal.tmx;

/**
 * One TMX listing's quote off the exchange website's own GraphQL service -
 * Toronto's numbers, not an aggregator's. {@code currency} is what the venue
 * says it trades in ({@code CAD} for TSX listings); {@code exchange} is the
 * venue's full display name ({@code Toronto Stock Exchange}, ...).
 *
 * <p>Missing numbers are {@link Double#NaN} (volume {@code -1}), never zero -
 * a zero price is a market statement, an absent one is not.
 */
public record TmxQuote(String symbol, String name, String exchange, String currency,
        double price, double change, double changePercent,
        double open, double dayHigh, double dayLow, double previousClose,
        long volume,
        double yearHigh, double yearLow,
        double marketCap) {}
