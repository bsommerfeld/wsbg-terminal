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
     * News referencing the given instrument, most-relevant first.
     *
     * @param symbol the ticker symbol to fetch news for
     * @param limit  upper bound on items returned
     * @return matching items, or an empty list when the source has none or is
     *         unavailable — never {@code null}
     */
    List<RawNewsItem> newsFor(String symbol, int limit);
}
