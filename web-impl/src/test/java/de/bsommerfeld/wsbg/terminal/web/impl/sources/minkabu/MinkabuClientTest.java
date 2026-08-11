package de.bsommerfeld.wsbg.terminal.web.impl.sources.minkabu;

import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parser contract against trimmed real replies (probed 2026-08-05). */
class MinkabuClientTest {

    private static String fixture(String name) throws IOException {
        try (var in = MinkabuClientTest.class.getResourceAsStream("/" + name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parsesTheBarsOldestFirstWithTokyoDates() throws IOException {
        MinkabuHistory h = MinkabuClient
                .parseHistory(fixture("minkabu-daily-sony.json"), "ソニーグループ")
                .orElseThrow();
        assertEquals("6758.T", h.ric());
        assertEquals("ソニーグループ", h.name());
        assertEquals(6, h.bars().size());
        var first = h.bars().get(0);
        var last = h.bars().get(h.bars().size() - 1);
        assertTrue(first.date().isBefore(last.date()), "oldest first");
        // The oldest bar: midnight-UTC epoch, read as the Tokyo session's date.
        assertEquals(LocalDate.of(2005, 3, 9), first.date());
        assertEquals(850.0, first.open(), 1e-9);
        assertEquals(862.0, first.high(), 1e-9);
        assertEquals(844.0, first.low(), 1e-9);
        assertEquals(852.0, first.close(), 1e-9);
        assertEquals(18_996_800.0, first.volume(), 1e-9);
        // The youngest bar is the running session - the current price lives here.
        assertEquals(LocalDate.of(2026, 8, 5), last.date());
        assertEquals(3565.0, last.close(), 1e-9);
        assertEquals(16_765_700.0, last.volume(), 1e-9);
        assertEquals(3565.0, h.latestClose(), 1e-9);
        assertEquals(LocalDate.of(2026, 8, 5), h.latestDate());
    }

    @Test
    void readsTheNativeNameOffTheAssetsCatalogue() throws IOException {
        assertEquals("ソニーグループ",
                MinkabuClient.parseName(fixture("minkabu-assets-sony.json"), "6758.T"));
        assertNull(MinkabuClient.parseName(fixture("minkabu-assets-sony.json"), "7203.T"),
                "a foreign row never labels this RIC");
    }

    @Test
    void garbageAndNonTokyoShapesStayEmpty() {
        assertTrue(MinkabuClient.parseHistory(null, null).isEmpty());
        assertTrue(MinkabuClient.parseHistory("", null).isEmpty());
        assertTrue(MinkabuClient.parseHistory("not json", null).isEmpty());
        // Unknown RICs answer 200 with an EMPTY array - emptiness is not-found.
        assertTrue(MinkabuClient.parseHistory(
                "{\"ric\":\"9999.T\",\"data\":[]}", null).isEmpty());
        assertNull(MinkabuClient.parseName(null, "6758.T"));
        assertNull(MinkabuClient.parseName("not json", "6758.T"));
        // The RIC gate is network-free: a US ticker never leaves the JVM.
        var noNetwork = new WebFetcher() {
            @Override
            public WebResponse fetch(String url, java.util.Map<String, String> headers,
                    java.time.Duration timeout, FetchUtil... modes) {
                throw new AssertionError("no network expected for " + url);
            }

            @Override
            public WebResponse fetchBinary(String url, java.util.Map<String, String> headers,
                    java.time.Duration timeout, FetchUtil... modes) {
                throw new UnsupportedOperationException();
            }

            @Override
            public WebResponse post(String url, java.util.Map<String, String> headers,
                    String body, String contentType, java.time.Duration timeout,
                    FetchUtil... modes) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean hostCoolingDown(String url) {
                return false;
            }
        };
        assertTrue(new MinkabuClient(noNetwork).history("AAPL", 100).isEmpty());
        assertTrue(new MinkabuClient(noNetwork).history("RHM.DE", 100).isEmpty());
        assertTrue(new MinkabuClient(noNetwork).history("6758", 100).isEmpty());
        assertTrue(new MinkabuClient(noNetwork).history(null, 100).isEmpty());
        assertTrue(new MinkabuClient(noNetwork).history("6758.T", 0).isEmpty());
    }
}
