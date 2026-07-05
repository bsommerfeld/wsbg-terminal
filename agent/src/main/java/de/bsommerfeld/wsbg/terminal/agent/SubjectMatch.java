package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooQuote;

/**
 * A unified match outcome from the guard tower. Replaces the parallel
 * index/commodity/quote branches the resolver used to flatten by hand into an
 * {@code (ownTicker, canonical)} pair.
 *
 * <p>A <em>present</em> {@code SubjectMatch} means a stage CLAIMS the subject and
 * the tower stops — even a {@link #newsOnly} claim (symbol {@code null}), which is
 * exactly the strong-hit-then-veto-strike case: the subject is recognised but
 * carries no ticker, and the tower must NOT fall through to the later judge/corpus
 * stages (preserving the resolver's original {@code else}-branch short-circuit).
 * An <em>empty</em> tower result means no stage claimed it — a tickerless
 * theme/person keyed on the query.
 *
 * @param symbol        the validated symbol, or {@code null} for a news-only claim
 * @param canonicalName the clean display name (the query itself for a news-only claim)
 * @param quote         the backing Yahoo quote, or {@code null} for curated/news-only
 */
record SubjectMatch(String symbol, String canonicalName, YahooQuote quote) {

    /** A quote match (strong/judge/corpus): symbol + display name come from the quote. */
    static SubjectMatch of(YahooQuote q) {
        return new SubjectMatch(q.symbol(), q.displayName(), q);
    }

    /** A curated catalogue match (index/commodity): a fixed symbol + display name, no quote. */
    static SubjectMatch curated(String symbol, String displayName) {
        return new SubjectMatch(symbol, displayName, null);
    }

    /** A claimed-but-tickerless subject (veto struck the spelling match): news-only. */
    static SubjectMatch newsOnly(String query) {
        return new SubjectMatch(null, query, null);
    }
}
