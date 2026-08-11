package de.bsommerfeld.wsbg.terminal.nyse;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parser contract against a trimmed real reply (probed 2026-08-05). */
class NyseQuoteClientTest {

    private static String fixture() throws IOException {
        try (var in = NyseQuoteClientTest.class.getResourceAsStream("/nyse-quote-aapl.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parsesTheQuoteWithBookSizesAndAverages() throws IOException {
        NyseTape t = NyseQuoteClient.parse(fixture()).orElseThrow();
        assertEquals("AAPL", t.symbol());
        assertEquals("APPLE INC", t.name());
        assertEquals("NASD", t.exchange(), "the service answers for Nasdaq listings too");
        assertEquals(309.14, t.last(), 1e-9);
        assertEquals(309.12, t.bid(), 1e-9);
        assertEquals(309.16, t.ask(), 1e-9);
        assertEquals(400, t.bidSize());
        assertEquals(360, t.askSize());
        assertEquals(21_767_315L, t.volume());
        assertEquals(56_789_149L, t.averageVolume());
        assertEquals(344.5699, t.yearHigh(), 1e-9);
        assertEquals(205.59, t.yearLow(), 1e-9);
        assertTrue(t.beta() > 0 && t.eps() > 0);
    }

    @Test
    void historyIsOldestFirstWithVolumes() throws IOException {
        NyseTape t = NyseQuoteClient.parse(fixture()).orElseThrow();
        assertEquals(6, t.history().size());
        var first = t.history().get(0);
        var last = t.history().get(t.history().size() - 1);
        assertEquals(LocalDate.of(2021, 8, 5), first.date());
        assertEquals(LocalDate.of(2026, 8, 4), last.date());
        assertTrue(first.date().isBefore(last.date()), "oldest first");
        assertEquals(147.06, first.close(), 1e-9);
        assertEquals(46_397_674L, first.volume());
        assertEquals(309.38, last.close(), 1e-9);
    }

    @Test
    void garbageAndNonUsShapesStayEmpty() {
        assertTrue(NyseQuoteClient.parse(null).isEmpty());
        assertTrue(NyseQuoteClient.parse("").isEmpty());
        assertTrue(NyseQuoteClient.parse("{\"quote\":{}}").isEmpty());
        assertTrue(NyseQuoteClient.parse("not json").isEmpty());
        // The symbol gate is network-free: a suffixed name never leaves the JVM.
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
        assertTrue(new NyseQuoteClient(noNetwork).tape("RHM.DE").isEmpty());
        assertTrue(new NyseQuoteClient(noNetwork).tape("EURUSD=X").isEmpty());
        assertTrue(new NyseQuoteClient(noNetwork).tape(null).isEmpty());
    }
}
