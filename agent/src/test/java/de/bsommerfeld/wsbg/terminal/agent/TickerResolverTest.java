package de.bsommerfeld.wsbg.terminal.agent;

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

    /** A quote with just the fields the matcher/ranker reads. */
    private static YahooQuote q(String symbol, String shortName, String exchange, String quoteType) {
        return new YahooQuote(symbol, shortName, null, quoteType, exchange, exchange, null, null, 0.0, 0.0);
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

    @Test
    void strictSingleTokenStillRejectsFuzzyExtraWord() {
        // The guard the strict mode exists for must survive the "com" relaxation:
        // a single-token query must NOT match a firm whose name merely contains the
        // token among real, distinguishing words.
        List<YahooQuote> quotes = List.of(q("RMO", "Rheiner Management AG", "GER", "EQUITY"));
        assertNull(TickerResolver.strongMatch("Rheiner", quotes));
    }
}
