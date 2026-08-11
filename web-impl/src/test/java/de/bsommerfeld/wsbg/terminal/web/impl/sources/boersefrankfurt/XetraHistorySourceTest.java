package de.bsommerfeld.wsbg.terminal.web.impl.sources.boersefrankfurt;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parser contract against a trimmed real reply (SAP on Xetra, probed 2026-08-05). */
class XetraHistorySourceTest {

    private static String fixture() throws IOException {
        try (var in = XetraHistorySourceTest.class
                .getResourceAsStream("/bf-xetra-sap.json")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void parsesColumnarBarsOldestFirst() throws IOException {
        var h = XetraHistorySource.parse("DE0007164600", fixture()).orElseThrow();
        assertEquals("DE0007164600", h.isin());
        assertEquals(6, h.bars().size());
        var first = h.bars().get(0);
        var last = h.bars().get(h.bars().size() - 1);
        assertEquals(LocalDate.of(2016, 8, 8), first.date());
        assertEquals(77.43, first.close(), 1e-9);
        assertEquals(LocalDate.of(2026, 8, 5), last.date());
        assertEquals(169.26, last.close(), 1e-9);
        assertTrue(first.date().isBefore(last.date()), "oldest first");
    }

    @Test
    void theVolumeColumnIsEurTurnover() throws IOException {
        var h = XetraHistorySource.parse("DE0007164600", fixture()).orElseThrow();
        var last = h.bars().get(h.bars().size() - 1);
        // 550M against a 169 EUR close is money, not shares - the record's
        // field name says so, and this pin keeps it honest.
        assertEquals(550_256_571.56, last.turnoverEur(), 1e-2);
        assertTrue(last.turnoverEur() / last.close() < 10_000_000,
                "turnover/price lands at a plausible share count");
    }

    @Test
    void garbageAndNonIsinShapesStayEmpty() {
        assertTrue(XetraHistorySource.parse("X", null).isEmpty());
        assertTrue(XetraHistorySource.parse("X", "").isEmpty());
        assertTrue(XetraHistorySource.parse("X", "{\"s\":\"no_data\"}").isEmpty());
        assertTrue(XetraHistorySource.parse("X", "not json").isEmpty());
        var noNetwork = de.bsommerfeld.wsbg.terminal.web.impl.sources.FakeFetchers.noNetwork();
        assertTrue(new XetraHistorySource(noNetwork)
                .history("AAPL", LocalDate.of(2020, 1, 1)).isEmpty());
        assertTrue(new XetraHistorySource(noNetwork).history(null, null).isEmpty());
    }
}
