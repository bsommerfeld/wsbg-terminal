package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.web.impl.sources.yahoofinance.YahooQuote;

import java.util.Locale;
import java.util.Set;

/**
 * "What kind of quote is this, and how confident must the match be" — the
 * crypto/theme predicates and the Yahoo-relevance thresholds that gate the
 * strong-token matcher. Extracted from {@code TickerResolver} so the matcher's
 * control flow reads clean and the empirically-tuned score bars live in one place.
 */
public final class QuoteClassifier {

    private QuoteClassifier() {}

    /** Jaccard token-overlap above which a name is a "strong" match. */
    public static final double STRONG_MATCH_THRESHOLD = 0.34;

    /**
     * Yahoo search-relevance score above which a single-token query is trusted to
     * match a name that carries <em>extra</em> non-stop tokens (e.g. "Amazon" vs
     * "Amazon.com, Inc.", "Meta" vs "Meta Platforms, Inc."). Instead of growing a
     * stop-word list forever, we lean on Yahoo's own confidence: a well-known
     * match scores very high (megacaps reach 6–7 figures), an obscure fuzzy hit
     * ("Rheiner" → some micro-cap) scores low. The score is popularity-weighted,
     * which is exactly why it works here — the megacaps we keep missing are the
     * high-scoring ones; legitimate low-score names fall through to the judge
     * fallback (tier 2) rather than being matched on a thin token overlap.
     * Deliberately conservative; tunable against live data.
     */
    public static final double MIN_CONFIDENT_SCORE = 100_000.0;

    /**
     * Minimum Yahoo relevance for a FUZZY crypto match (non-cashtag). Cryptos score on a
     * different, lower scale than equities (a megacap clears 6–7 figures; Bitcoin tops out
     * ~37&nbsp;000, Ethereum ~32&nbsp;000), while the obscure same-named memecoins that trap a
     * product/person name ("Starlink", "Elon Musk") sit at Yahoo's base ~20&nbsp;000. 25k cleanly
     * separates the coins the room genuinely means from the namesake junk. {@code exactSymbol}
     * (a cashtag the user wrote) bypasses this.
     */
    public static final double CRYPTO_MIN_SCORE = 25_000.0;

    /**
     * Generic theme/macro/slang acronyms the room uses as topics, not tickers —
     * even though each happens to BE a listed symbol. Gated from the exact-symbol
     * fast-path so "AI" stays the AI theme (not C3.ai), "IT" stays IT (not Gartner).
     */
    public static final Set<String> THEME_WORDS = Set.of(
            "AI", "KI", "EV", "IT", "FED", "ECB", "EZB", "USA", "USD", "EUR", "GBP",
            "CPI", "GDP", "BIP", "PCE", "ETF", "IPO", "ATH", "FOMO", "DD", "YOLO",
            "CEO", "CFO", "KGV", "QE", "BTC");

    /** A crypto quote: a {@code …-USD}/{@code …-EUR} pair or Yahoo {@code CRYPTOCURRENCY} type. */
    public static boolean isCryptoQuote(YahooQuote q) {
        if (q == null) return false;
        String sym = q.symbol() == null ? "" : q.symbol().toUpperCase(Locale.ROOT);
        String type = q.quoteType() == null ? "" : q.quoteType().trim().toUpperCase(Locale.ROOT);
        return sym.endsWith("-USD") || sym.endsWith("-EUR") || type.equals("CRYPTOCURRENCY");
    }

    /**
     * A crypto quote that is admissible as a SUBJECT: on the declared
     * {@link de.bsommerfeld.wsbg.terminal.instruments.SymbolShapes#MAJOR_CRYPTO}
     * positive list. Every other coin is a naming parasite by measurement
     * (~57% wrong referents in the live ledger) and is struck from the
     * candidate space before any stage — including the judge — ever sees it.
     */
    public static boolean isAdmissibleCrypto(YahooQuote q) {
        return isCryptoQuote(q)
                && de.bsommerfeld.wsbg.terminal.instruments.SymbolShapes
                        .isMajorCryptoPair(q.symbol());
    }

    /**
     * Candidate-space hygiene over ONE Yahoo search response — the cheapest place
     * to stop a naming parasite is before it becomes a candidate at all (the
     * 2026-08 live-run failure classes, each rule-based):
     * <ul>
     *   <li><b>random-number coins</b> ({@code GEMINI34655-USD}) — minted namesake
     *       tokens, list-free form rule;</li>
     *   <li><b>non-major coins</b> — the declared crypto positive list (see
     *       {@link #isAdmissibleCrypto});</li>
     *   <li><b>Morningstar fund ids</b> ({@code 0P0001L7U0.SA} — the "Rockstar"
     *       Brazilian fund) — never an equity room's subject;</li>
     *   <li><b>numeric-prefixed western secondaries</b> ({@code 1MUV2.MI}) —
     *       dropped only while at least one OTHER candidate survives: a thin
     *       secondary line is the right referent at the wrong venue, and as the
     *       lone handle it is better than nothing.</li>
     * </ul>
     * Never returns null; an all-struck list returns empty (the subject stays
     * news-only rather than mis-stamped).
     */
    public static java.util.List<YahooQuote> admissibleQuotes(java.util.List<YahooQuote> quotes) {
        if (quotes == null || quotes.isEmpty()) return java.util.List.of();
        java.util.List<YahooQuote> kept = new java.util.ArrayList<>(quotes.size());
        java.util.List<YahooQuote> numericSecondaries = new java.util.ArrayList<>(2);
        for (YahooQuote q : quotes) {
            if (q == null) continue;
            String sym = q.symbol() == null ? "" : q.symbol();
            if (de.bsommerfeld.wsbg.terminal.instruments.SymbolShapes.isJunkNumberedCrypto(sym)) continue;
            if (isCryptoQuote(q) && !isAdmissibleCrypto(q)) continue;
            if (de.bsommerfeld.wsbg.terminal.instruments.SymbolShapes.isMorningstarFundId(sym)) continue;
            if (de.bsommerfeld.wsbg.terminal.instruments.SymbolShapes
                    .isNumericPrefixedWesternSecondary(sym)) {
                numericSecondaries.add(q);
                continue;
            }
            kept.add(q);
        }
        // Lone-handle exception: a numeric-prefixed secondary comes back only when
        // NOTHING else survived — the right referent at a thin venue beats no
        // referent at all; with any peer present the peer is the better listing.
        return kept.isEmpty() ? java.util.List.copyOf(numericSecondaries) : kept;
    }

    /** True when the query is a generic theme/macro acronym that must not exact-match its ticker. */
    public static boolean isThemeWord(String query) {
        return query != null && THEME_WORDS.contains(query.trim().toUpperCase(Locale.ROOT));
    }
}
