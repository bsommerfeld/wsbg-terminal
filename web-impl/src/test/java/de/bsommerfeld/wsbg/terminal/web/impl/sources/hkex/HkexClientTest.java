package de.bsommerfeld.wsbg.terminal.web.impl.sources.hkex;

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

/** Parser contract against trimmed real JSONP replies (probed 2026-08-05). */
class HkexClientTest {

    private static String fixture(String name) throws IOException {
        try (var in = HkexClientTest.class.getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parsesTheQuoteWithUnitSuffixedNumbersScaledOut() throws IOException {
        HkexQuote q = HkexClient.parseQuote(fixture("/hkex-quote-700.jsonp")).orElseThrow();
        assertEquals("700", q.symbol());
        assertEquals("Tencent Holdings Ltd.", q.name());
        assertEquals("HKD", q.currency());
        assertEquals(492.2, q.last(), 1e-9);
        assertEquals(0.94, q.changePercent(), 1e-9);
        assertEquals(497.8, q.dayHigh(), 1e-9);
        assertEquals(482.2, q.dayLow(), 1e-9);
        assertEquals(25_660_000L, q.volume(), "vo=25.66 + vo_u=M, scaled to shares");
        assertEquals(12.62e9, q.turnover(), 1e-3, "am=12.62 + am_u=B");
        assertEquals(4_475.33e9, q.marketCap(), 1e-3, "mkt_cap=4,475.33 + mkt_cap_u=B");
    }

    @Test
    void historyIsOldestFirstInHongKongDatesWithNullPaddingDropped() throws IOException {
        HkexHistory h = HkexClient
                .parseHistory("0700.HK", fixture("/hkex-chart-700.jsonp")).orElseThrow();
        assertEquals("0700.HK", h.ric());
        // Six rows in the fixture, but the all-null placeholder rows the
        // service pads the series with (one at each end) never make it in.
        assertEquals(4, h.bars().size());
        var first = h.bars().get(0);
        var last = h.bars().get(h.bars().size() - 1);
        assertEquals(LocalDate.of(2016, 8, 5), first.date(),
                "epoch midnight read in Asia/Hong_Kong, not UTC");
        assertEquals(LocalDate.of(2026, 8, 5), last.date());
        assertTrue(first.date().isBefore(last.date()), "oldest first");
        assertEquals(170.147, first.close(), 1e-9);
        assertEquals(10_032_639d, first.volume(), 1e-9);
        assertEquals(492.2, last.close(), 1e-9);
        assertEquals(25_662_478d, last.volume(), 1e-9);
    }

    @Test
    void pullsTheLiveTokenPastTheCommentedSample() {
        String page = """
                LabCI.getToken = function () {
                    // ----- THIS IS A SAMPLE IMPLEMENTATION -----
                    //return "Base64-AES-Encrypted-Token";
                    return "evLtsLsBNAUVTPxtGqVeG5x42mjrY5Y2T1QSpHyCuCRLtx55E9KljAL4DTOjl8Nb";
                };
                """;
        assertEquals("evLtsLsBNAUVTPxtGqVeG5x42mjrY5Y2T1QSpHyCuCRLtx55E9KljAL4DTOjl8Nb",
                HkexClient.extractToken(page));
        assertNull(HkexClient.extractToken(null));
        assertNull(HkexClient.extractToken("<html>no widget here</html>"));
    }

    @Test
    void normalizesEveryHkShapeAndRejectsTheRest() {
        assertEquals("700", HkexClient.bareNumber("700"));
        assertEquals("700", HkexClient.bareNumber("0700.hk"));
        assertEquals("700", HkexClient.bareNumber(" 00700 "));
        assertEquals("9988", HkexClient.bareNumber("9988.HK"));
        assertEquals("80700", HkexClient.bareNumber("80700"));
        assertNull(HkexClient.bareNumber("AAPL"));
        assertNull(HkexClient.bareNumber("RHM.DE"));
        assertNull(HkexClient.bareNumber("123456"));
        assertNull(HkexClient.bareNumber("0"));
        assertNull(HkexClient.bareNumber(null));
        assertEquals("0700.HK", HkexClient.paddedRic("700"));
        assertEquals("9988.HK", HkexClient.paddedRic("9988"));
        assertEquals("80700.HK", HkexClient.paddedRic("80700"));
    }

    @Test
    void garbageAndNonHkShapesStayEmpty() {
        assertTrue(HkexClient.parseQuote(null).isEmpty());
        assertTrue(HkexClient.parseQuote("").isEmpty());
        assertTrue(HkexClient.parseQuote("not jsonp").isEmpty());
        assertTrue(HkexClient.parseQuote("cb({\"data\":{\"quote\":{}}})").isEmpty());
        // A rejected token arrives as an HTML error page, never as JSON.
        assertTrue(HkexClient.parseQuote("<html><body>Error report (500)</body></html>").isEmpty());
        assertTrue(HkexClient.parseHistory("0700.HK", null).isEmpty());
        assertTrue(HkexClient.parseHistory("0700.HK", "cb({\"data\":{\"datalist\":[]}})").isEmpty());
        // Unpadded RICs get a polite reply with session hours and no bars.
        assertTrue(HkexClient.parseHistory("700.HK",
                "cb({\"data\":{\"responsecode\":\"000\",\"start_h\":18},\"qid\":\"1\"})").isEmpty());
        // The symbol gate is network-free: a non-HK name never leaves the JVM.
        var noNetwork = new WebFetcher() {
            @Override
            public WebResponse fetch(String url, Map<String, String> headers,
                    Duration timeout, FetchUtil... modes) {
                throw new AssertionError("no network expected for " + url);
            }

            @Override
            public WebResponse fetchBinary(String url, Map<String, String> headers,
                    Duration timeout, FetchUtil... modes) {
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
        };
        assertTrue(new HkexClient(noNetwork).quote("AAPL").isEmpty());
        assertTrue(new HkexClient(noNetwork).history("EURUSD=X").isEmpty());
        assertTrue(new HkexClient(noNetwork).quote(null).isEmpty());
    }
}
