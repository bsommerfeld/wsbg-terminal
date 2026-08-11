package de.bsommerfeld.wsbg.terminal.web.impl.sources.boersenmedien;

import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.SourceTestSupport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Resolver tests against the live-captured JSON of the shared backend
 * (2026-08-02). Ported 1:1 from the module world.
 */
class BoersenmedienResolverTest {

    private static String fixture(String name) {
        return SourceTestSupport.fixture("sources/boersenmedien/" + name);
    }

    private static final class RoutingFetcher implements WebFetcher {
        final List<String> calls = new ArrayList<>();
        private final Function<String, String> router;

        RoutingFetcher(Function<String, String> router) {
            this.router = router;
        }

        @Override
        public WebResponse fetch(String url, Map<String, String> headers, Duration timeout,
                FetchUtil... modes) {
            calls.add(url);
            String body = router.apply(url);
            return body == null ? WebResponse.text(404, "", Map.of())
                    : WebResponse.text(200, body, Map.of());
        }

        @Override
        public WebResponse fetchBinary(String url, Map<String, String> headers, Duration timeout,
                FetchUtil... modes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public WebResponse post(String url, Map<String, String> headers, String body,
                String contentType, Duration timeout, FetchUtil... modes) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean hostCoolingDown(String url) {
            return false;
        }
    }

    @Test
    void parsesStockSearchIntoInstruments() {
        List<BoersenmedienResolver.Instrument> hits =
                BoersenmedienResolver.parseSearch(fixture("da-searchstocks.json"));

        assertEquals(6, hits.size());
        assertEquals("DE000SHL1006", hits.get(0).isin());
        assertEquals("SHL100", hits.get(0).wkn());
        assertEquals("Siemens Healthineers", hits.get(0).name());
        assertTrue(hits.get(0).linkUrl().startsWith("/aktien/kurse/"));
        assertTrue(hits.stream().anyMatch(i -> "DE0007236101".equals(i.isin())),
                "the exact name still ranks inside the ten hits, not necessarily first");
    }

    @Test
    void parsesTheSharedQuoteEndpoint() {
        Optional<BoersenmedienResolver.Quote> quote =
                BoersenmedienResolver.parseQuote(fixture("symbols-remote.json"));

        assertTrue(quote.isPresent());
        BoersenmedienResolver.Quote q = quote.get();
        assertEquals("DE0007236101", q.isin());
        assertEquals("Siemens AG", q.name());
        assertEquals("EUR", q.currency(), "the venue is Tradegate, so the price is in EUR");
        assertEquals(282.9, q.last());
        assertEquals(0.5322, q.changePercent());
        assertEquals(282.9, q.previousClose());
        assertEquals("Tradegate", q.venue());
        assertEquals(Instant.parse("2026-07-31T20:02:55Z"), q.asOf());
    }

    @Test
    void parsesTheInstrumentIdentityFromTheQuoteEndpoint() {
        Optional<BoersenmedienResolver.Instrument> i =
                BoersenmedienResolver.parseInstrument(fixture("symbols-remote.json"));

        assertTrue(i.isPresent());
        assertEquals("723610", i.get().wkn());
        assertEquals("SIE", i.get().ticker());
        assertEquals("SMAWF", i.get().usTicker(), "the US OTC line comes for free");
    }

    @Test
    void quoteFallsBackToTheByteIdenticalBoerseOnlineHost() {
        String json = fixture("symbols-remote.json");
        RoutingFetcher fetcher = new RoutingFetcher(
                url -> url.startsWith(BoersenmedienNewsClient.HOST_DA) ? null : json);

        Optional<BoersenmedienResolver.Quote> quote =
                new BoersenmedienResolver(fetcher).quote("DE0007236101");

        assertTrue(quote.isPresent());
        assertEquals(2, fetcher.calls.size());
        assertTrue(fetcher.calls.get(0).contains("deraktionaer.de/api/remote/symbols/?s=DE0007236101"));
        assertTrue(fetcher.calls.get(1).contains("boerse-online.de/symbol/remote?s=DE0007236101"));
    }

    @Test
    void searchUrlEncodesTheQuery() {
        String json = fixture("da-searchstocks.json");
        RoutingFetcher fetcher = new RoutingFetcher(url -> json);

        assertFalse(new BoersenmedienResolver(fetcher).search("Siemens Energy").isEmpty());
        assertEquals("https://www.deraktionaer.de/api/remote/searchStocks?q=Siemens+Energy",
                fetcher.calls.get(0));
    }

    @Test
    void malformedIsinAndGarbageNeverThrow() {
        RoutingFetcher fetcher = new RoutingFetcher(u -> "");
        BoersenmedienResolver resolver = new BoersenmedienResolver(fetcher);

        assertTrue(resolver.quote("Siemens").isEmpty(), "the endpoint takes ISINs only");
        assertTrue(resolver.byIsin(null).isEmpty());
        assertTrue(resolver.search("  ").isEmpty());
        assertTrue(fetcher.calls.isEmpty(), "nothing malformed costs a fetch");

        assertTrue(BoersenmedienResolver.parseSearch("[]").isEmpty());
        assertTrue(BoersenmedienResolver.parseSearch("<html>error</html>").isEmpty());
        assertTrue(BoersenmedienResolver.parseSearch(null).isEmpty());
        assertTrue(BoersenmedienResolver.parseQuote("[]").isEmpty(),
                "an unknown ISIN answers an empty array, not an error");
        assertTrue(BoersenmedienResolver.parseQuote("nope").isEmpty());
        assertTrue(BoersenmedienResolver.parseInstrument(null).isEmpty());
    }
}
