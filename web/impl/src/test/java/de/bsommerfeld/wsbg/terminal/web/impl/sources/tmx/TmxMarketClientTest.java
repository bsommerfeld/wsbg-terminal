package de.bsommerfeld.wsbg.terminal.web.impl.sources.tmx;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parser contract against trimmed real GraphQL replies (probed 2026-08-05). */
class TmxMarketClientTest {

    private static String fixture(String name) throws IOException {
        try (var in = TmxMarketClientTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parsesTheQuoteWithRangesCapAndCurrency() throws IOException {
        TmxQuote q = TmxMarketClient.parseQuote(fixture("tmx-quote-shop.json")).orElseThrow();
        assertEquals("SHOP", q.symbol());
        assertEquals("Shopify Inc. Class A Subordinate Voting Shares", q.name());
        assertEquals("Toronto Stock Exchange", q.exchange());
        assertEquals("CAD", q.currency(), "the venue names its own currency");
        assertEquals(205.7, q.price(), 1e-9);
        assertEquals(32.29, q.change(), 1e-9);
        assertEquals(18.620609, q.changePercent(), 1e-9);
        assertEquals(209.08, q.open(), 1e-9);
        assertEquals(216.0, q.dayHigh(), 1e-9);
        assertEquals(199.99, q.dayLow(), 1e-9);
        assertEquals(173.41, q.previousClose(), 1e-9);
        assertEquals(2_776_269L, q.volume());
        assertEquals(253.1, q.yearHigh(), 1e-9);
        assertEquals(129.01, q.yearLow(), 1e-9);
        assertEquals(248_631_396_664.0, q.marketCap(), 1e-3);
    }

    @Test
    void historyArrivesNewestFirstAndLeavesOldestFirst() throws IOException {
        TmxHistory h = TmxMarketClient
                .parseHistory("SHOP", fixture("tmx-history-shop.json")).orElseThrow();
        assertEquals("SHOP", h.symbol());
        assertEquals(6, h.bars().size());
        var first = h.bars().get(0);
        var last = h.bars().get(h.bars().size() - 1);
        assertEquals(LocalDate.of(2016, 8, 2), first.date());
        assertEquals(LocalDate.of(2026, 8, 5), last.date());
        assertTrue(first.date().isBefore(last.date()), "oldest first");
        assertEquals(4.474, first.open(), 1e-9);
        assertEquals(4.502, first.high(), 1e-9);
        assertEquals(4.294, first.low(), 1e-9);
        assertEquals(4.393, first.close(), 1e-9);
        assertEquals(69_337.0, first.volume(), 1e-9);
        assertEquals(205.91, last.close(), 1e-9);
        assertEquals(2_775_869.0, last.volume(), 1e-9);
    }

    @Test
    void garbageStaysEmpty() {
        assertTrue(TmxMarketClient.parseQuote(null).isEmpty());
        assertTrue(TmxMarketClient.parseQuote("").isEmpty());
        assertTrue(TmxMarketClient.parseQuote("not json").isEmpty());
        assertTrue(TmxMarketClient.parseQuote("{\"data\":{}}").isEmpty());
        // An unknown symbol answers 200 with errors and a null quote - quiet empty.
        assertTrue(TmxMarketClient.parseQuote(
                "{\"errors\":[{\"message\":\"Sorry\",\"code\":\"404\"}],"
                        + "\"data\":{\"getQuoteBySymbol\":null}}").isEmpty());
        assertTrue(TmxMarketClient.parseHistory("SHOP", null).isEmpty());
        assertTrue(TmxMarketClient.parseHistory("SHOP", "not json").isEmpty());
        assertTrue(TmxMarketClient.parseHistory("SHOP",
                "{\"data\":{\"getTimeSeriesData\":null}}").isEmpty());
        assertTrue(TmxMarketClient.parseHistory("SHOP",
                "{\"data\":{\"getTimeSeriesData\":[]}}").isEmpty());
    }

    @Test
    void theSymbolGateAdmitsTmxShapesOnly() {
        assertEquals("SHOP", TmxMarketClient.gate(" shop "));
        assertEquals("RY", TmxMarketClient.gate("RY"));
        assertEquals("CTC.A", TmxMarketClient.gate("CTC.A"));
        assertEquals("BAM.A", TmxMarketClient.gate("bam.a"));
        // Yahoo-suffixed, index and crypto shapes never leave the JVM.
        assertNull(TmxMarketClient.gate("SHOP.TO"), "Yahoo suffix is not a TMX symbol");
        assertNull(TmxMarketClient.gate("^GSPTSE"));
        assertNull(TmxMarketClient.gate("BTC-CAD"));
        assertNull(TmxMarketClient.gate("EURUSD=X"));
        assertNull(TmxMarketClient.gate("TOOLONGX"));
        assertNull(TmxMarketClient.gate(""));
        assertNull(TmxMarketClient.gate(null));
    }
}
