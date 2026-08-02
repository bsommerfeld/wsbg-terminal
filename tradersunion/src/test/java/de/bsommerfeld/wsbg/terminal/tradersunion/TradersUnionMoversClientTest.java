package de.bsommerfeld.wsbg.terminal.tradersunion;

import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parser tests against live-captured Traders Union fixtures (2026-08-02). */
class TradersUnionMoversClientTest {

    static String fixture(String name) throws IOException {
        try (InputStream in = TradersUnionMoversClientTest.class.getResourceAsStream("/" + name)) {
            assertNotNull(in, "missing fixture " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ---- movers API ----

    @Test
    void parsesStockGainersWithNumericTickerIdAsJoinKey() throws Exception {
        List<TradersUnionMoversClient.Mover> movers =
                TradersUnionMoversClient.parse(fixture("tu-gainers-stocks.json"), "stocks", true);

        assertFalse(movers.isEmpty());
        TradersUnionMoversClient.Mover first = movers.get(0);
        assertEquals(6551, first.tickerId(), "ticker_id is the stable join key");
        assertEquals("REPL/USD", first.ticker());
        assertEquals("REPL", first.symbol());
        assertEquals(107.02d, first.percentChange(), 1e-9);
        assertEquals(107.02d, first.signedPercentChange(), 1e-9);
        assertEquals("stocks", first.category());
        assertTrue(first.gainer());
        assertTrue(first.link().startsWith("https://tradersunion.com/de/currencies/forecast/"),
                "the API's relative link is absolutised, never assembled");
    }

    @Test
    void parsesEveryProbedCategoryShape() throws Exception {
        // crypto, bonds and etfs each answer a different link shape - one parser serves all.
        List<TradersUnionMoversClient.Mover> crypto =
                TradersUnionMoversClient.parse(fixture("tu-gainers-crypto.json"), "crypto", true);
        List<TradersUnionMoversClient.Mover> bonds =
                TradersUnionMoversClient.parse(fixture("tu-gainers-bonds.json"), "bonds", true);
        List<TradersUnionMoversClient.Mover> etfs =
                TradersUnionMoversClient.parse(fixture("tu-losers-etfs.json"), "etfs", false);

        assertFalse(crypto.isEmpty());
        assertEquals(8526, bonds.get(0).tickerId());
        assertEquals("US10Y", bonds.get(0).symbol());
        assertTrue(bonds.get(0).link().contains("/de/bonds/us10y/yield/"));
        assertTrue(etfs.get(0).link().contains("/de/etfs/koru/"));
        for (List<TradersUnionMoversClient.Mover> board : List.of(crypto, bonds, etfs)) {
            board.forEach(m -> {
                assertTrue(m.tickerId() > 0, "every row carries a numeric id");
                assertFalse(m.symbol().isBlank());
            });
        }
    }

    @Test
    void loserPercentIsAMagnitudeAndOnlyTheSignedViewIsNegative() throws Exception {
        List<TradersUnionMoversClient.Mover> losers =
                TradersUnionMoversClient.parse(fixture("tu-losers-etfs.json"), "etfs", false);

        TradersUnionMoversClient.Mover first = losers.get(0);
        assertEquals(7.31d, first.percentChange(), 1e-9, "the API prints losers unsigned");
        assertEquals(-7.31d, first.signedPercentChange(), 1e-9);
        assertFalse(first.gainer());
    }

    @Test
    void percentParsingSurvivesBothDecimalStyles() {
        assertEquals(107.02d, TradersUnionMoversClient.parsePercent("107.02%"), 1e-9);
        assertEquals(7.31d, TradersUnionMoversClient.parsePercent("7,31 %"), 1e-9);
        assertEquals(0d, TradersUnionMoversClient.parsePercent("n/a"), 1e-9);
        assertEquals(0d, TradersUnionMoversClient.parsePercent(null), 1e-9);
    }

    @Test
    void moversGarbageYieldsEmptyListNotException() {
        assertTrue(TradersUnionMoversClient.parse("{\"data\":\"nope\"}", "stocks", true).isEmpty());
        assertTrue(TradersUnionMoversClient.parse("<html>cloudflare</html>", "stocks", true).isEmpty());
        assertTrue(TradersUnionMoversClient.parse("", "stocks", true).isEmpty());
        assertTrue(TradersUnionMoversClient.parse(null, "stocks", true).isEmpty());
    }

    @Test
    void everyCategoryAndModeIsReachableThroughOneSweep() throws Exception {
        FakeFetcher fetcher = new FakeFetcher(Map.of(
                "informer", fixture("tu-gainers-stocks.json")));
        TradersUnionMoversClient client = new TradersUnionMoversClient(fetcher);

        assertFalse(client.allMovers(TradersUnionMoversClient.Mode.SHORT_TERM).isEmpty());

        assertEquals(14, fetcher.count("informer"), "7 categories x gainers/losers");
        for (TradersUnionMoversClient.Category c : TradersUnionMoversClient.Category.values()) {
            assertEquals(2, fetcher.count("category=" + c.slug()), "both directions per class");
        }
        assertEquals(14, fetcher.count("mode=short_term"));

        fetcher.reset();
        client.allMovers();
        assertEquals(28, fetcher.count("informer"), "both horizons");
        assertEquals(14, fetcher.count("mode=long_term"));
    }

    // ---- instrument pages ----

    @Test
    void readsInstrumentIdentityFromForecastAndEtfPages() throws Exception {
        TradersUnionMoversClient.InstrumentPage forecast =
                TradersUnionMoversClient.parseInstrumentPage(
                        "https://tradersunion.com/de/currencies/forecast/cbk-usd/daily-and-weekly/",
                        fixture("tu-forecast-cbk.html"));
        assertNotNull(forecast);
        assertEquals("CBK/USD", forecast.symbol());
        assertEquals(1142, forecast.tickerId());
        assertTrue(forecast.title().contains("Cobak Token"));

        TradersUnionMoversClient.InstrumentPage etf =
                TradersUnionMoversClient.parseInstrumentPage(
                        "https://tradersunion.com/de/etfs/koru/short-term/",
                        fixture("tu-etf-koru.html"));
        assertNotNull(etf);
        assertEquals("KORU", etf.symbol());
        assertEquals(8560, etf.tickerId());
    }

    @Test
    void instrumentPageToleratesA500AndAnswersNull() {
        // /de/currencies/forecast/eur-usd/daily-and-weekly/ answered 500 on the probe day
        // while its neighbours answered 200 - that must never propagate.
        FakeFetcher fetcher = new FakeFetcher(Map.of());
        fetcher.status = 500;
        TradersUnionMoversClient client = new TradersUnionMoversClient(fetcher);

        assertNull(client.instrumentPage(
                "https://tradersunion.com/de/currencies/forecast/eur-usd/daily-and-weekly/"));
        assertNull(TradersUnionMoversClient.parseInstrumentPage("u", "<html>nothing here</html>"));
        assertNull(TradersUnionMoversClient.parseInstrumentPage("u", ""));
    }

    @Test
    void aFailingBoardNeverKillsTheSweep() throws Exception {
        FakeFetcher fetcher = new FakeFetcher(Map.of(
                "category=stocks", fixture("tu-gainers-stocks.json")));
        fetcher.status = 500; // everything else 500s
        TradersUnionMoversClient client = new TradersUnionMoversClient(fetcher);

        List<TradersUnionMoversClient.Mover> all =
                client.allMovers(TradersUnionMoversClient.Mode.SHORT_TERM);

        assertFalse(all.isEmpty(), "the healthy board still answers");
        assertTrue(all.stream().allMatch(m -> "stocks".equals(m.category())));
    }

    /** Serves fixtures by URL fragment and counts calls. */
    static final class FakeFetcher implements WebFetcher {
        private final Map<String, String> byFragment;
        private final List<String> calls = new ArrayList<>();
        /** Status for urls no fixture matches. */
        int status = 404;

        FakeFetcher(Map<String, String> byFragment) {
            this.byFragment = byFragment;
        }

        int count(String fragment) {
            return (int) calls.stream().filter(u -> u.contains(fragment)).count();
        }

        void reset() {
            calls.clear();
        }

        @Override
        public String name() {
            return "fake";
        }

        @Override
        public WebResponse fetch(String url, Map<String, String> headers, Duration timeout) {
            calls.add(url);
            for (Map.Entry<String, String> e : byFragment.entrySet()) {
                if (url.contains(e.getKey())) return new WebResponse(200, e.getValue(), Map.of());
            }
            return new WebResponse(status, "", Map.of());
        }
    }
}
