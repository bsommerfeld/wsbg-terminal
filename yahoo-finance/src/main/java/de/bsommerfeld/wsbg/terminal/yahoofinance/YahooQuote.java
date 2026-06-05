package de.bsommerfeld.wsbg.terminal.yahoofinance;

/**
 * One result row from a Yahoo Finance ticker search.
 *
 * <p>
 * Yahoo distinguishes a security's <em>symbol</em> (the exchange-qualified
 * identifier, e.g. {@code RHM.DE} for Rheinmetall on XETRA) from its
 * <em>shortName</em> and <em>longName</em> (the company name, possibly
 * padded with trailing whitespace by the API). All three matter to the
 * agent: the symbol is the canonical identifier we hand to
 * {@code publishHeadline}, the name is what gets written into the
 * headline text.
 *
 * <p>
 * {@code regularMarketPercentChange} is the day-change (today vs. previous
 * close) — useful as a sanity-check against the {@code priceMovePercent}
 * the agent reads off Reddit. {@link Double#NaN} when Yahoo did not return
 * a price (e.g. inactive listings, fresh IPOs).
 */
public record YahooQuote(
        String symbol,
        String shortName,
        String longName,
        String quoteType,
        String exchange,
        String exchangeDisplay,
        String sector,
        String industry,
        double regularMarketPrice,
        double regularMarketPercentChange,
        double score) {

    /**
     * Display-friendly name: prefers {@link #longName} when present, falls
     * back to {@link #shortName}. Trailing whitespace is trimmed because
     * Yahoo sometimes pads short names to a fixed column width.
     */
    public String displayName() {
        String n = (longName != null && !longName.isBlank()) ? longName : shortName;
        return n == null ? "" : n.trim();
    }
}
