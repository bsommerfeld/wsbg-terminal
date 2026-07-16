package de.bsommerfeld.wsbg.terminal.source;

import java.util.List;

/**
 * A provider of news, addressed by instrument. Every fetcher (Yahoo, newswire
 * RSS, finanznachrichten, FinancialJuice) implements this one contract so the
 * aggregator can treat them uniformly — bound as a {@code Set<NewsSource>} via
 * Guice multibindings and swapped/added/dropped by wiring, not by code.
 *
 * <p>The role is defined by the interface, not by which module a source lives
 * in: belonging to "news" means implementing {@code NewsSource}.
 */
public interface NewsSource {

    /** Short, stable identifier for attribution/logging (e.g. {@code "yahoo"}). */
    String sourceName();

    /**
     * {@code true} when this source carries SOCIAL SENTIMENT (forum posts,
     * social-network chatter — opinion from a room) rather than reported news.
     * The aggregator keeps the two streams apart: sentiment sources never
     * answer the news fan (so a forum post can't masquerade as an article in
     * the press loom), and the news sources never answer the sentiment fan.
     * Each source declares its own nature here — self-description on the
     * contract, not a curated list anywhere else (2026-07-16).
     */
    default boolean socialSentiment() {
        return false;
    }

    /**
     * ARCHIVE window: news for the company NAME restricted to a date window
     * (ISO dates, {@code to} exclusive) - the multi-year press-history leg of
     * the long-term dossier (2026-07-16). Sources without a date-addressable
     * archive keep this default no-op.
     */
    default java.util.List<RawNewsItem> newsForNameWindow(
            String companyName, String isin, String fromIsoDate, String toIsoDateExclusive,
            int limit) {
        return java.util.List.of();
    }

    /**
     * News referencing the given instrument, most-relevant first.
     *
     * @param symbol the ticker symbol to fetch news for
     * @param limit  upper bound on items returned
     * @return matching items, or an empty list when the source has none or is
     *         unavailable — never {@code null}
     */
    List<RawNewsItem> newsFor(String symbol, int limit);

    /**
     * News referencing the given company NAME, most-relevant first. German
     * venues (wallstreet-online, Deutsche Börse) are name-addressed — a US
     * ticker symbol means nothing to them, but "Meta Wolf" finds the XETRA
     * catalyst Yahoo never carries. Sources that only understand symbols keep
     * this default no-op; the aggregator fans BOTH queries per source.
     *
     * @return matching items, or an empty list — never {@code null}
     */
    default List<RawNewsItem> newsForName(String companyName, int limit) {
        return List.of();
    }

    /**
     * News referencing the given ISIN, most-relevant first. The ISIN is the
     * one identity a listed instrument MUST have, so an ISIN-addressed query
     * never chases a wrong same-named twin — and regulatory disclosures (EQS
     * ad-hocs) carry it verbatim, so a full-text source finds the
     * disclosure-grade documents the name query drowns in daily price notes.
     * ALWAYS additive beside the symbol/name queries (an article that names
     * the company without printing the ISIN must never be lost to ISIN-only
     * querying); sources without an ISIN surface keep this default no-op.
     *
     * @return matching items, or an empty list — never {@code null}
     */
    default List<RawNewsItem> newsForIsin(String isin, int limit) {
        return List.of();
    }
}
