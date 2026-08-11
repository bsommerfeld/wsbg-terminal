package de.bsommerfeld.wsbg.terminal.web.impl.sources.asx;

import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parser contract against trimmed real replies (probed 2026-08-05). */
class AsxMarketClientTest {

    private static String fixture(String name) throws IOException {
        try (var in = AsxMarketClientTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parsesTheQuoteWithXidAndFundamentals() throws IOException {
        AsxQuote q = AsxMarketClient.parseQuote(
                fixture("asx-header-bhp.json"), fixture("asx-keystats-bhp.json")).orElseThrow();
        assertEquals("BHP", q.symbol());
        assertEquals("BHP GROUP LIMITED", q.name());
        assertEquals("60947", q.xid(), "the chart backend answers only to the xid");
        assertEquals(62.54, q.priceLast(), 1e-9);
        assertEquals(62.52, q.bid(), 1e-9);
        assertEquals(62.54, q.ask(), 1e-9);
        assertEquals(7_168_173L, q.volume());
        assertEquals(307_525_826_047L, q.marketCap());
        assertEquals("AU000000BHP4", q.isin());
        assertEquals(8_414_006.633333, q.averageVolume(), 1e-6);
        assertEquals(2.87774, q.eps(), 1e-9);
        assertEquals(1.0385, q.dividend(), 1e-9);
    }

    @Test
    void quoteStandsWithoutKeyStatistics() throws IOException {
        AsxQuote q = AsxMarketClient.parseQuote(
                fixture("asx-header-bhp.json"), null).orElseThrow();
        assertEquals("BHP", q.symbol());
        assertEquals(62.54, q.priceLast(), 1e-9);
        assertNull(q.isin(), "fundamentals are a bonus, never a requirement");
        assertTrue(Double.isNaN(q.eps()));
    }

    @Test
    void historyIsOldestFirstWithVolumesInAud() throws IOException {
        AsxHistory h = AsxMarketClient.parseHistory(
                "BHP", fixture("asx-chart-bhp.json")).orElseThrow();
        assertEquals("BHP", h.symbol());
        assertEquals("60947", h.xid());
        assertEquals("AUD", h.currency());
        assertEquals(6, h.bars().size());
        var first = h.bars().get(0);
        var last = h.bars().get(h.bars().size() - 1);
        assertEquals(LocalDate.of(2006, 8, 11), first.date());
        assertEquals(LocalDate.of(2026, 8, 5), last.date());
        assertTrue(first.date().isBefore(last.date()), "oldest first");
        assertEquals(21.76483, first.open(), 1e-9);
        assertEquals(21.74063, first.close(), 1e-9);
        assertEquals(12_637_233.0, first.volume(), 1e-9);
        assertEquals(62.54, last.close(), 1e-9);
        assertEquals(7_168_173.0, last.volume(), 1e-9);
    }

    @Test
    void tokenComesOutOfTheBundleSource() {
        assertEquals("83ff96335c2d45a094df02a206a39ff4", AsxMarketClient.extractToken(
                "q;F2.API_TOKEN=\"83ff96335c2d45a094df02a206a39ff4\";(function"));
        assertNull(AsxMarketClient.extractToken("API_TOKEN=\"not-hex\""));
        assertNull(AsxMarketClient.extractToken(""));
        assertNull(AsxMarketClient.extractToken(null));
    }

    @Test
    void garbageAndForeignShapesStayEmpty() {
        assertTrue(AsxMarketClient.parseQuote(null, null).isEmpty());
        assertTrue(AsxMarketClient.parseQuote("", null).isEmpty());
        assertTrue(AsxMarketClient.parseQuote("not json", null).isEmpty());
        assertTrue(AsxMarketClient.parseQuote("{\"data\":{}}", null).isEmpty());
        assertTrue(AsxMarketClient.parseHistory("BHP", null).isEmpty());
        assertTrue(AsxMarketClient.parseHistory("BHP", "not json").isEmpty());
        assertTrue(AsxMarketClient.parseHistory("BHP", "{\"Dates\":[]}").isEmpty());
        // The symbol gate is network-free: a suffixed name never leaves the JVM.
        WebFetcher noNetwork = new WebFetcher() {
            @Override
            public WebResponse fetch(String url, Map<String, String> headers,
                    Duration timeout, FetchUtil... modes) {
                throw new AssertionError("no network expected for " + url);
            }

            @Override
            public WebResponse fetchBinary(String url, Map<String, String> headers,
                    Duration timeout, FetchUtil... modes) {
                throw new AssertionError("no network expected for " + url);
            }

            @Override
            public WebResponse post(String url, Map<String, String> headers, String body,
                    String contentType, Duration timeout, FetchUtil... modes) {
                throw new AssertionError("no network expected for " + url);
            }

            @Override
            public boolean hostCoolingDown(String url) {
                return false;
            }
        };
        var client = new AsxMarketClient(noNetwork);
        assertTrue(client.quote("BHP.AX").isEmpty(), "the caller hands over the naked code");
        assertTrue(client.quote("EURUSD=X").isEmpty());
        assertTrue(client.quote("AB").isEmpty(), "ASX codes start at three characters");
        assertTrue(client.quote(null).isEmpty());
        assertTrue(client.history("BHP.AX", 7300).isEmpty());
        assertTrue(client.history("BHP", 0).isEmpty());
    }
}
