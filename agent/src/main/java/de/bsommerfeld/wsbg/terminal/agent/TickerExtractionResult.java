package de.bsommerfeld.wsbg.terminal.agent;

import java.util.List;

/**
 * Structured output from AI ticker extraction.
 * Each entry represents a unique financial instrument
 * mentioned in the analyzed thread context.
 *
 * @param mentions list of extracted ticker mentions
 */
record TickerExtractionResult(List<TickerMention> mentions) {

    /**
     * Single financial instrument mention.
     *
     * @param symbol ticker symbol or common name (e.g., "AAPL", "DAX", "Gold")
     * @param type   instrument type: STOCK, ETF, ETC, INDEX, COMMODITY, CRYPTO,
     *               DERIVATIVE, UNKNOWN
     * @param name   human-readable name if available (e.g., "Apple Inc.")
     */
    record TickerMention(String symbol, String type, String name) {
    }

    static final TickerExtractionResult EMPTY = new TickerExtractionResult(List.of());
}
