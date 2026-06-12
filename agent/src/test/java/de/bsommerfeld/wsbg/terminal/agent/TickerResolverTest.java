package de.bsommerfeld.wsbg.terminal.agent;
import de.bsommerfeld.wsbg.terminal.embedding.EmbeddingService;

import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooQuote;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Exchange-preference (#8) of {@link TickerResolver#strongMatch}: among the
 * quotes that match the subject name, the primary/home listing must win over
 * foreign secondary lines, OTC ADRs and same-name ETFs — without a brittle
 * exchange whitelist. Load-neutral: this only re-ranks quotes already in one
 * search response.
 */
class TickerResolverTest {

    /** A quote with just the fields the matcher/ranker reads (no relevance score). */
    private static YahooQuote q(String symbol, String shortName, String exchange, String quoteType) {
        return q(symbol, shortName, exchange, quoteType, 0.0);
    }

    /** A quote carrying a Yahoo relevance score (tier-1 confidence signal). */
    private static YahooQuote q(String symbol, String shortName, String exchange, String quoteType, double score) {
        return new YahooQuote(symbol, shortName, null, quoteType, exchange, exchange, null, null, 0.0, 0.0, score);
    }

    @Test
    void prefersHomeListingOverNumericPrefixedSecondary() {
        // 1MUV2.MI is the Borsa Italiana secondary line of Munich Re; MUV2.DE is home.
        List<YahooQuote> quotes = List.of(
                q("1MUV2.MI", "MUNICH RE", "MIL", "EQUITY"),
                q("MUV2.DE", "MUNICH RE", "GER", "EQUITY"));
        assertEquals("MUV2.DE", TickerResolver.strongMatch("Munich", quotes).symbol());

        // Order must not matter — the numeric-prefixed line loses either way.
        List<YahooQuote> reversed = List.of(
                q("MUV2.DE", "MUNICH RE", "GER", "EQUITY"),
                q("1MUV2.MI", "MUNICH RE", "MIL", "EQUITY"));
        assertEquals("MUV2.DE", TickerResolver.strongMatch("Munich", reversed).symbol());
    }

    @Test
    void exactSymbolWinsOutright() {
        List<YahooQuote> quotes = List.of(
                q("1MUV2.MI", "MUNICH RE", "MIL", "EQUITY"),
                q("MUV2.DE", "MUNICH RE", "GER", "EQUITY"));
        assertEquals("MUV2.DE", TickerResolver.strongMatch("MUV2.DE", quotes).symbol());
    }

    @Test
    void demotesOtcAdr() {
        List<YahooQuote> quotes = List.of(
                q("ALIZY", "ALLIANZ", "PNK", "EQUITY"),   // US pink-sheet ADR
                q("ALV.DE", "ALLIANZ", "GER", "EQUITY"));
        assertEquals("ALV.DE", TickerResolver.strongMatch("Allianz", quotes).symbol());
    }

    @Test
    void prefersEquityOverSameNameEtf() {
        List<YahooQuote> quotes = List.of(
                q("XALV", "ALLIANZ", "GER", "ETF"),
                q("ALV.DE", "ALLIANZ", "GER", "EQUITY"));
        assertEquals("ALV.DE", TickerResolver.strongMatch("Allianz", quotes).symbol());
    }

    @Test
    void soleMatchIsReturnedEvenIfPenalised() {
        List<YahooQuote> quotes = List.of(q("1MUV2.MI", "MUNICH RE", "MIL", "EQUITY"));
        assertEquals("1MUV2.MI", TickerResolver.strongMatch("Munich", quotes).symbol());
    }

    @Test
    void noNameMatchReturnsNull() {
        List<YahooQuote> quotes = List.of(q("ALV.DE", "ALLIANZ", "GER", "EQUITY"));
        assertNull(TickerResolver.strongMatch("Tesla", quotes));
    }

    @Test
    void singleTokenQueryMatchesDotComSuffixName() {
        // "Amazon" → "Amazon.com, Inc.": the glued ".com" must not break the strict
        // single-token match — "com" is a generic suffix like inc/corp. Regression:
        // Amazon used to fall through to a name-only unit with no ticker.
        List<YahooQuote> quotes = List.of(q("AMZN", "Amazon.com, Inc.", "NMS", "EQUITY"));
        assertEquals("AMZN", TickerResolver.strongMatch("Amazon", quotes).symbol());
    }

    // ---- Tier 2: embedding fallback when token/score matching can't decide ----

    @Test
    void tier2PicksTheSemanticCandidateWhenTokensDontMatch() {
        // "Google" shares NO token with "Alphabet Inc." — strongMatch can't link them,
        // but the embedder knows they're the same (pinned). Tier 2 rescues it.
        FakeEmbeddingService fake = new FakeEmbeddingService().pin("Google", "Alphabet Inc.", 0.8);
        TickerResolver r = new TickerResolver(null, fake);
        List<YahooQuote> quotes = List.of(
                q("AAPL", "Apple Inc.", "NMS", "EQUITY"),
                q("GOOGL", "Alphabet Inc.", "NMS", "EQUITY"));
        assertEquals("GOOGL", r.embedMatch("Google", quotes).symbol());
    }

    @Test
    void tier2RejectsWhenNoCandidateIsCloseEnough() {
        // The guard: no semantically-close candidate → stays unresolved, never a guess.
        TickerResolver r = new TickerResolver(null, new FakeEmbeddingService());
        assertNull(r.embedMatch("Rheinmetall", List.of(q("AAPL", "Apple Inc.", "NMS", "EQUITY"))));
    }

    @Test
    void tier2IsNoOpWithoutAnEmbedder() {
        TickerResolver r = new TickerResolver(null); // no embedder → Tier 2 disabled
        assertNull(r.embedMatch("Google", List.of(q("GOOGL", "Alphabet Inc.", "NMS", "EQUITY"))));
    }

    @Test
    void strictSingleTokenStillRejectsFuzzyExtraWord() {
        // The guard the strict mode exists for must survive the "com" relaxation:
        // a single-token query must NOT match a firm whose name merely contains the
        // token among real, distinguishing words (no score signal → stays rejected).
        List<YahooQuote> quotes = List.of(q("RMO", "Rheiner Management AG", "GER", "EQUITY"));
        assertNull(TickerResolver.strongMatch("Rheiner", quotes));
    }

    @Test
    void highYahooScoreRescuesSingleTokenWithExtraWord() {
        // "Meta" vs "Meta Platforms, Inc." — "platforms" is NOT (and shouldn't need
        // to be) a stop-word. A high Yahoo relevance score confirms the megacap, so
        // no stop-list growth is needed.
        List<YahooQuote> quotes = List.of(q("META", "Meta Platforms, Inc.", "NMS", "EQUITY", 800_000.0));
        assertEquals("META", TickerResolver.strongMatch("Meta", quotes).symbol());
    }

    @Test
    void lowYahooScoreDoesNotRescueFuzzyExtraWord() {
        // Same structural shape, but an obscure low-score hit must stay rejected —
        // the score, not a token list, is what separates Amazon-legit from Rheiner-fuzzy.
        List<YahooQuote> quotes = List.of(q("RMO", "Rheiner Management AG", "GER", "EQUITY", 1_200.0));
        assertNull(TickerResolver.strongMatch("Rheiner", quotes));
    }
}
