package de.bsommerfeld.wsbg.terminal.web.impl.sources.nordic;

import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parser contract against trimmed real replies (probed 2026-08-05). */
class NordicMarketClientTest {

    private static String fixture(String name) throws IOException {
        try (var in = NordicMarketClientTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void screenerBecomesTheResolverBookUnderIsinAndSymbol() throws IOException {
        Map<String, String> book =
                NordicMarketClient.parseScreener(fixture("nordic-screener-trimmed.json"));
        assertEquals("TX100", book.get("SE0000115446"), "Volvo B by ISIN");
        assertEquals("TX100", book.get("VOLV B"), "Volvo B by symbol");
        assertEquals("TX2195", book.get("DK0010244425"), "Maersk A by ISIN");
        assertEquals("TX50014", book.get("FI0009007884"), "Elisa by ISIN");
    }

    @Test
    void parsesTheQuoteWithCurrencyPrefixedMoneyAndBookSizes() throws IOException {
        NordicQuote q = NordicMarketClient
                .parseQuote(fixture("nordic-info-volvob.json")).orElseThrow();
        assertEquals("VOLV B", q.symbol());
        assertEquals("SE0000115446", q.isin());
        assertEquals("Volvo B", q.name());
        assertEquals("Nasdaq Stockholm", q.exchange());
        assertEquals("Large Cap", q.segment());
        assertEquals("SEK", q.currency());
        assertEquals(366.40, q.last(), 1e-9);
        assertEquals(-5.10, q.netChange(), 1e-9);
        assertEquals(-1.37, q.changePercent(), 1e-9);
        assertEquals(365.40, q.bid(), 1e-9);
        assertEquals(365.90, q.ask(), 1e-9);
        assertEquals(310, q.bidSize());
        assertEquals(3_957, q.askSize());
        assertEquals(3_058_399L, q.volume());
        assertEquals(362.50, q.dayLow(), 1e-9);
        assertEquals(374.50, q.dayHigh(), 1e-9);
        assertEquals(244.90, q.yearLow(), 1e-9);
        assertEquals(374.50, q.yearHigh(), 1e-9);
    }

    @Test
    void historyIsOldestFirstWithVolumes() throws IOException {
        NordicHistory h = NordicMarketClient
                .parseHistory(fixture("nordic-chart-volvob-trimmed.json")).orElseThrow();
        assertEquals("VOLV B", h.symbol());
        assertEquals("SE0000115446", h.isin());
        assertEquals("SEK", h.currency());
        assertEquals(6, h.bars().size());
        var first = h.bars().get(0);
        var last = h.bars().get(h.bars().size() - 1);
        assertEquals(LocalDate.of(2016, 8, 8), first.date());
        assertEquals(LocalDate.of(2026, 8, 5), last.date());
        assertTrue(first.date().isBefore(last.date()), "oldest first");
        assertEquals(88.25, first.close(), 1e-9);
        assertEquals(2_987_025, first.volume(), 1e-9);
        assertEquals(366.40, last.close(), 1e-9);
        assertEquals(372.50, last.open(), 1e-9);
        assertEquals(374.50, last.high(), 1e-9);
        assertEquals(362.50, last.low(), 1e-9);
    }

    @Test
    void theScreenerIsDrunkOnceThenServedFromTheBook() throws IOException {
        AtomicInteger screenerHits = new AtomicInteger();
        var fetcher = new WebFetcher() {
            @Override
            public WebResponse fetch(String url, Map<String, String> headers,
                    java.time.Duration timeout, FetchUtil... modes) throws IOException {
                assertTrue(headers.containsKey("Accept"),
                        "the JSON Accept header rides every call");
                String body;
                if (url.equals(NordicMarketClient.SCREENER_URL)) {
                    screenerHits.incrementAndGet();
                    body = fixture("nordic-screener-trimmed.json");
                } else if (url.contains("/TX100/info")) {
                    body = fixture("nordic-info-volvob.json");
                } else {
                    throw new AssertionError("unexpected URL " + url);
                }
                return WebResponse.text(200, body, Map.of());
            }

            @Override
            public WebResponse fetchBinary(String url, Map<String, String> headers,
                    java.time.Duration timeout, FetchUtil... modes) {
                throw new UnsupportedOperationException();
            }

            @Override
            public WebResponse post(String url, Map<String, String> headers, String body,
                    String contentType, java.time.Duration timeout, FetchUtil... modes) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean hostCoolingDown(String url) {
                return false;
            }
        };
        var client = new NordicMarketClient(fetcher);
        assertEquals(366.40,
                client.quoteByIsin("SE0000115446").orElseThrow().last(), 1e-9);
        assertEquals(366.40,
                client.quoteByIsin("se0000115446").orElseThrow().last(), 1e-9);
        assertEquals(1, screenerHits.get(), "the book lives for the JVM");
    }

    @Test
    void garbageAndNonNordicIsinsStayEmpty() {
        assertTrue(NordicMarketClient.parseQuote(null).isEmpty());
        assertTrue(NordicMarketClient.parseQuote("").isEmpty());
        assertTrue(NordicMarketClient.parseQuote("{\"data\":{}}").isEmpty());
        assertTrue(NordicMarketClient.parseQuote("not json").isEmpty());
        assertTrue(NordicMarketClient.parseHistory("{\"data\":{}}").isEmpty());
        assertTrue(NordicMarketClient.parseHistory("not json").isEmpty());
        assertTrue(NordicMarketClient.parseScreener("not json").isEmpty());
        // The ISIN gate is network-free: a non-Nordic ISIN never even
        // triggers the screener bootstrap.
        var noNetwork = new WebFetcher() {
            @Override
            public WebResponse fetch(String url, Map<String, String> headers,
                    java.time.Duration timeout, FetchUtil... modes) {
                throw new AssertionError("no network expected for " + url);
            }

            @Override
            public WebResponse fetchBinary(String url, Map<String, String> headers,
                    java.time.Duration timeout, FetchUtil... modes) {
                throw new UnsupportedOperationException();
            }

            @Override
            public WebResponse post(String url, Map<String, String> headers, String body,
                    String contentType, java.time.Duration timeout, FetchUtil... modes) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean hostCoolingDown(String url) {
                return false;
            }
        };
        var client = new NordicMarketClient(noNetwork);
        assertTrue(client.quoteByIsin("US0378331005").isEmpty());
        assertTrue(client.quoteByIsin("DE0007664039").isEmpty());
        assertTrue(client.quoteByIsin("VOLV B").isEmpty(), "symbols are not ISINs");
        assertTrue(client.quoteByIsin(null).isEmpty());
        assertTrue(client.historyByIsin("US0378331005",
                LocalDate.of(2016, 8, 1)).isEmpty());
        assertTrue(client.historyByIsin(null, null).isEmpty());
    }
}
