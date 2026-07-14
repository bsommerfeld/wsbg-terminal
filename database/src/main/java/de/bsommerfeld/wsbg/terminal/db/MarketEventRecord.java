package de.bsommerfeld.wsbg.terminal.db;

/**
 * One dated, classified market event with its measured reaction — a line of
 * the market memory's event register. The reaction fields start null and are
 * filled by the deterministic enrichment sweep once the event window has
 * settled (CARs need t+5 trading days); the regime stamp is the Fear&amp;Greed
 * band of the day BEFORE the event (t−1, the event-study convention).
 *
 * <p>Plain record, no Jackson annotations — round-trips through the vanilla
 * {@code ObjectMapper} by component name, exactly like {@link HeadlineRecord}.
 * Nullable wrappers stay null on JSONL lines written before a field existed.
 *
 * @param date        ISO local date of the event (calendar date; the study
 *                    maps it to the effective trading day)
 * @param symbol      Yahoo-resolvable symbol; null for ISIN-only German events
 *                    until resolution succeeds
 * @param isin        the issuer's ISIN when the source carried one
 * @param eventClass  the house class (8-K mapping / ad-hoc classification /
 *                    analyst action / earnings surprise sign)
 * @param source      which leg delivered it ("EDGAR", "MarketBeat", "NASDAQ",
 *                    "EQS", …)
 * @param detail      short free detail (raw 8-K items, the action text, the
 *                    surprise percent) — evidence, never parsed
 * @param regimeBand  Fear&amp;Greed band word at t−1; null until stamped
 * @param regimeScore the composite score at t−1; null until stamped
 * @param carEvent    market-adjusted CAR(−1,+1) in percent; null until enriched
 * @param carShort    market-adjusted CAR(0,+5) in percent; null until enriched
 * @param benchmark   the benchmark symbol the CARs were computed against
 * @param confounded  true when another event of the same instrument sits
 *                    within ±2 calendar days (the class measures a cocktail)
 */
public record MarketEventRecord(
        String date,
        String symbol,
        String isin,
        String eventClass,
        String source,
        String detail,
        String regimeBand,
        Double regimeScore,
        Double carEvent,
        Double carShort,
        String benchmark,
        Boolean confounded) {

    /** Register identity — the same event from the same leg is stored once. */
    public String identity() {
        String instrument = symbol != null && !symbol.isBlank()
                ? symbol.toUpperCase() : (isin == null ? "" : isin.toUpperCase());
        return date + "|" + instrument + "|" + eventClass + "|" + source;
    }

    /** Un-enriched base shape (harvest time: reaction + regime still unknown). */
    public static MarketEventRecord bare(String date, String symbol, String isin,
            String eventClass, String source, String detail) {
        return new MarketEventRecord(date, symbol, isin, eventClass, source, detail,
                null, null, null, null, null, null);
    }
}
