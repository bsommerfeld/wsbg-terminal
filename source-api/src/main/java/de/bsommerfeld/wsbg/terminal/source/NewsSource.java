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
     * {@code true} when this source belongs to the DOSSIER only — the deep
     * dive fans it, the live wire does not. The fishing net stays wide (every
     * viable source is wired); the sorting happens at the SHELF: a research
     * source that answers with venue notes, machine-written price ticks,
     * gated agency copy or the same dpa-AFX piece under a fourth roof is
     * depth for a dossier and noise for a headline. Each source declares its
     * own reach here — self-description on the contract, not a curated list
     * anywhere else (2026-08-03).
     */
    default boolean dossierOnly() {
        return false;
    }

    /**
     * Where this source's items come FROM - language and press sphere. The
     * fan stamps every item a source hands back with this, so the deep dive
     * can count SPHERES instead of domains when it asks whether a fact is
     * confirmed or merely echoed (2026-08-11). Each source declares its own
     * origin here - self-description on the contract, not a curated map
     * anywhere else. An aggregator wired once per edition declares the
     * EDITION's origin; a source that cannot say keeps this default.
     */
    default SourceOrigin origin() {
        return SourceOrigin.UNKNOWN;
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
