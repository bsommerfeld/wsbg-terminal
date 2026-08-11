package de.bsommerfeld.wsbg.terminal.six;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parser contract against trimmed real replies (probed 2026-08-05). */
class SixMarketClientTest {

    private static String fixture(String name) throws IOException {
        try (var in = SixMarketClientTest.class.getResourceAsStream(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parsesTheColumnOrientedQuote() throws IOException {
        SixQuote q = SixMarketClient.parseQuote(fixture("/six-quote-nesn.json")).orElseThrow();
        assertEquals("NESTLE N", q.name());
        assertEquals("CH0038863350", q.isin());
        assertEquals("NESN", q.symbol());
        assertEquals(81.35, q.last(), 1e-9);
        assertEquals(2_445_945L, q.volume(), "shares traded, not turnover");
        assertEquals(82.04, q.dayHigh(), 1e-9);
        assertEquals(80.7, q.dayLow(), 1e-9);
        assertEquals(80.66, q.previousClose(), 1e-9);
    }

    @Test
    void historyIsOldestFirstWithVolumes() throws IOException {
        SixHistory h = SixMarketClient.parseHistory(fixture("/six-charts-nesn.json")).orElseThrow();
        assertEquals("CH0038863350", h.isin());
        assertEquals(5, h.bars().size());
        var first = h.bars().get(0);
        var last = h.bars().get(h.bars().size() - 1);
        assertEquals(LocalDate.of(2016, 1, 4), first.date());
        assertEquals(LocalDate.of(2026, 8, 5), last.date());
        assertTrue(first.date().isBefore(last.date()), "oldest first");
        assertEquals(73.9, first.open(), 1e-9);
        assertEquals(73.95, first.high(), 1e-9);
        assertEquals(72.9, first.low(), 1e-9);
        assertEquals(73.55, first.close(), 1e-9);
        assertEquals(6_538_486d, first.volume(), 1e-9);
        assertEquals(81.35, last.close(), 1e-9);
        assertEquals(2_445_945d, last.volume(), 1e-9);
    }

    @Test
    void garbagePayloadsStayEmpty() {
        assertTrue(SixMarketClient.parseQuote(null).isEmpty());
        assertTrue(SixMarketClient.parseQuote("").isEmpty());
        assertTrue(SixMarketClient.parseQuote("not json").isEmpty());
        assertTrue(SixMarketClient.parseQuote("{\"colNames\":[],\"rowData\":[]}").isEmpty());
        assertTrue(SixMarketClient.parseHistory(null).isEmpty());
        assertTrue(SixMarketClient.parseHistory("").isEmpty());
        assertTrue(SixMarketClient.parseHistory("not json").isEmpty());
        assertTrue(SixMarketClient.parseHistory("{\"valors\":[]}").isEmpty());
    }

    @Test
    void nonSwissShapesNeverTouchTheNetwork() {
        // The ISIN gate is network-free: a foreign shape never leaves the JVM.
        var noNetwork = new de.bsommerfeld.wsbg.terminal.source.net.WebFetcher() {
            @Override
            public String name() {
                return "none";
            }

            @Override
            public de.bsommerfeld.wsbg.terminal.source.net.WebResponse fetch(
                    String url, java.util.Map<String, String> headers,
                    java.time.Duration timeout) {
                throw new AssertionError("no network expected for " + url);
            }
        };
        var client = new SixMarketClient(noNetwork);
        assertTrue(client.quote("US0378331005").isEmpty());
        assertTrue(client.quote("NESN").isEmpty());
        assertTrue(client.quote(null).isEmpty());
        assertTrue(client.history("DE0007037129", LocalDate.of(2016, 1, 1)).isEmpty());
        assertTrue(client.history("CH0038863350", null).isEmpty());
        assertTrue(client.history(null, LocalDate.of(2016, 1, 1)).isEmpty());
    }
}
