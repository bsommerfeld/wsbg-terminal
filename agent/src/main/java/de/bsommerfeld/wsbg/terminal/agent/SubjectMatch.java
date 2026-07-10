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
 * <p>The <b>stamp</b> ({@code isin} + {@code venueId} + {@code category}) is the
 * identity desk's exact venue instrument. A stamped match is priced by direct
 * lookup — the price chain executes the verdict instead of re-resolving the name.
 * Unstamped matches (catalogue, token, judge, corpus) carry {@code null}/{@code 0}.
 *
 * @param symbol        the validated symbol, or {@code null} for a news-only claim
 * @param canonicalName the clean display name (the query itself for a news-only claim)
 * @param quote         the backing Yahoo quote, or {@code null} for curated/news-only/ledger
 * @param isin          the stamped ISIN (or venue pseudo-ISIN), or {@code null}
 * @param venueId       the stamped venue instrument id, or {@code 0}
 * @param category      the stamped venue category ({@code STK}/{@code ETF}/{@code CUR}/{@code RES}/…), or {@code null}
 * @param venueRuledOut the desk SAW venue candidates and struck every one — a considered
 *                      "this paper does not trade there" that the price chain's fuzzy
 *                      name search must not second-guess (exact-ISIN paths stay open)
 */
record SubjectMatch(String symbol, String canonicalName, YahooQuote quote,
        String isin, long venueId, String category, boolean venueRuledOut) {

    /** Pre-desk shape: no stamp, no considered venue verdict. */
    SubjectMatch(String symbol, String canonicalName, YahooQuote quote,
            String isin, long venueId, String category) {
        this(symbol, canonicalName, quote, isin, venueId, category, false);
    }

    /** A quote match (strong/judge/corpus): symbol + display name come from the quote. No stamp. */
    static SubjectMatch of(YahooQuote q) {
        return new SubjectMatch(q.symbol(), q.displayName(), q, null, 0, null);
    }

    /** A curated catalogue match (index/commodity): a fixed symbol + display name, no quote, no stamp. */
    static SubjectMatch curated(String symbol, String displayName) {
        return new SubjectMatch(symbol, displayName, null, null, 0, null);
    }

    /** A claimed-but-tickerless subject (desk/veto struck every candidate): news-only. */
    static SubjectMatch newsOnly(String query) {
        return new SubjectMatch(null, query, null, null, 0, null);
    }

    /** True when the identity desk stamped an exact venue instrument onto this match. */
    boolean isStamped() {
        return venueId > 0 || (isin != null && !isin.isBlank());
    }
}
