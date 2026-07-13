package de.bsommerfeld.wsbg.terminal.tradegate;

import de.bsommerfeld.wsbg.terminal.core.price.VenueStats;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Network-free parse tests against captured {@code refresh.php} replies
 * (probed live 2026-07-10). The two capture quirks that matter: number fields
 * arrive mixed as raw JSON numbers and German-formatted strings within the SAME
 * object, and an unlisted ISIN answers HTTP 200 with an empty body.
 */
class TradegateQuoteClientTest {

    private final TradegateQuoteClient client = new TradegateQuoteClient();

    /** Live capture, Rheinmetall (DE0007030009): prices as German strings. */
    private static final String GERMAN_STRINGS = """
            {
                "bid": "992,10",
                "ask": "992,60",
                "bidsize": 65,
                "asksize": 47,
                "delta": -1.99,
                "stueck": 38509,
                "umsatz": 38332799,
                "avg": 995.4244,
                "executions": 5379,
                "last": "992,10",
                "high": "1 019,80",
                "low": "977,20",
                "close": 1012.2
            }""";

    /** Live capture, NVIDIA (US67066G1040): prices as raw numbers + extra field. */
    private static final String RAW_NUMBERS = """
            {
                "bid": 183.42,
                "ask": 183.48,
                "bidsize": 800,
                "asksize": 800,
                "delta": 3.39,
                "stueck": 119913,
                "umsatz": 21595187,
                "avg": 180.0905,
                "executions": 1986,
                "last": 183.46,
                "high": "184,00",
                "low": 175.84,
                "close": 177.44,
                "terms_and_conditions": "https://www.tradegatebsx.com/nutzungsbedingung.php"
            }""";

    @Test
    void parsesGermanFormattedStrings() {
        VenueStats s = client.parse(GERMAN_STRINGS).orElseThrow();
        assertEquals(992.10, s.bid(), 1e-9);
        assertEquals(992.60, s.ask(), 1e-9);
        assertEquals(65, s.bidSize());
        assertEquals(47, s.askSize());
        assertEquals(992.10, s.last(), 1e-9);
        assertEquals(-1.99, s.dayChangePercent(), 1e-9);
        assertEquals(1019.80, s.dayHigh(), 1e-9); // "1 019,80" — space thousands separator
        assertEquals(977.20, s.dayLow(), 1e-9);
        assertEquals(1012.2, s.previousClose(), 1e-9);
        assertEquals(38509, s.volumeShares());
        assertEquals(38332799, s.turnoverEur());
        assertEquals(5379, s.executions());
        assertEquals("Tradegate", s.venue());
        assertTrue(s.hasQuote());
        assertTrue(s.spreadPercent() > 0);
    }

    @Test
    void parsesRawNumbers() {
        VenueStats s = client.parse(RAW_NUMBERS).orElseThrow();
        assertEquals(183.42, s.bid(), 1e-9);
        assertEquals(183.48, s.ask(), 1e-9);
        assertEquals(800, s.bidSize());
        assertEquals(184.00, s.dayHigh(), 1e-9); // string field inside a numeric object
        assertEquals(119913, s.volumeShares());
        assertEquals(1986, s.executions());
    }

    @Test
    void emptyBodyIsAMissNotAnError() {
        assertEquals(Optional.empty(), client.parse(""));
        assertEquals(Optional.empty(), client.parse("   "));
        assertEquals(Optional.empty(), client.parse(null));
        assertEquals(Optional.empty(), client.parse("{}"));
    }

    @Test
    void replyWithoutAnyPriceIsRejected() {
        assertFalse(client.parse("{\"stueck\": 12}").isPresent());
    }

    @Test
    void spreadPercentNaNWithoutQuote() {
        VenueStats s = new VenueStats("Tradegate", Double.NaN, Double.NaN, -1, -1,
                10, 0, Double.NaN, Double.NaN, Double.NaN, -1, -1, -1, 0);
        assertFalse(Double.isFinite(s.spreadPercent()));
        assertFalse(s.hasQuote());
    }
}
