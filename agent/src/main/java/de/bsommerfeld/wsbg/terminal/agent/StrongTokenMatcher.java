package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooQuote;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Guard stage 3 (tier-1 identity) — token/score matching over the Yahoo search
 * candidates, picking the venue-preferred listing. Same match test lifted verbatim
 * from {@code TickerResolver.strongMatch}: token overlap + single-token strict mode
 * + exact-symbol, the memecoin-trap crypto gate, the theme-word exact-symbol gate,
 * and the {@link VenuePreference} pick among all matches (so a foreign secondary
 * line {@code 1MUV2.MI} no longer beats the home listing {@code MUV2.DE} just by
 * appearing first).
 */
final class StrongTokenMatcher implements SubjectMatcher {

    @Override
    public Optional<SubjectMatch> match(MatchContext ctx) {
        YahooQuote q = strongMatch(ctx.query(), ctx.quotes());
        return q == null ? Optional.empty() : Optional.of(SubjectMatch.of(q));
    }

    /**
     * Returns the <em>preferred</em> quote among those that confidently match
     * the query, or null. Package-visible static for unit testing.
     */
    static YahooQuote strongMatch(String query, List<YahooQuote> quotes) {
        if (quotes == null || quotes.isEmpty()) return null;
        Set<String> queryTokens = NameMatching.tokenize(query);
        boolean strictSingleToken = queryTokens.size() == 1;

        YahooQuote best = null;
        int bestRank = Integer.MAX_VALUE;
        for (int i = 0; i < quotes.size(); i++) {
            YahooQuote q = quotes.get(i);
            // A generic theme/macro acronym (AI, KI, EV, FED, …) is almost always the
            // theme, not the same-letter ticker (e.g. "AI" the topic, not C3.ai). Don't
            // let it exact-match a ticker — leave it unresolved (a clear ticker-less line).
            boolean exactSymbol = q.symbol() != null && query.equalsIgnoreCase(q.symbol())
                    && !QuoteClassifier.isThemeWord(query);
            boolean strong = NameMatching.bestSimilarity(queryTokens, q) >= QuoteClassifier.STRONG_MATCH_THRESHOLD;
            if (strong && strictSingleToken && !NameMatching.hasOnlyQueryTokens(queryTokens, q)) {
                // The name carries extra, non-stop tokens beyond the single query
                // token (e.g. "Amazon" vs "Amazon.com, Inc."). Rather than police
                // this with an ever-growing stop-word list, defer to Yahoo's own
                // relevance score: a confident megacap match clears the bar, an
                // obscure fuzzy hit does not. (Tier 2, the judge, is the fallback
                // for the legitimate low-score names this still rejects.)
                strong = q.score() >= QuoteClassifier.MIN_CONFIDENT_SCORE;
            }
            // The MEMECOIN TRAP: a fuzzy (non-cashtag) name match to a CRYPTO is almost always
            // a wrong same-named coin namesake — "Starlink" (the SpaceX product) → the STARL
            // coin, "Elon Musk" (the person) → a Musk-themed coin. These sit at Yahoo's base
            // relevance (~20000), while a coin the room genuinely means (Bitcoin, Ethereum)
            // scores far higher. So a fuzzy crypto hit must clear the higher crypto bar; below
            // it, drop to tickerless → the line stays news-only, never a guessed memecoin. The
            // room writing the symbol itself (exactSymbol, a cashtag) always passes — that IS
            // faithful, the user named the coin.
            if (strong && !exactSymbol && QuoteClassifier.isCryptoQuote(q)
                    && q.score() < QuoteClassifier.CRYPTO_MIN_SCORE) {
                strong = false;
            }
            if (exactSymbol) strong = true;
            if (!strong) continue;

            int rank = VenuePreference.preferenceRank(q, i, exactSymbol);
            if (rank < bestRank) {
                bestRank = rank;
                best = q;
            }
        }
        return best;
    }
}
