package de.bsommerfeld.wsbg.terminal.deutscheboerse;

import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing of the Deutsche Börse quote_box JSON into an EUR snapshot. No network. */
class DeutscheBoerseClientTest {

    private final DeutscheBoerseClient client = new DeutscheBoerseClient();

    @Test
    void parseBuildsEurSnapshotWithDayMoveAndPrevClose() {
        String body = """
            {"isin":"US67066G1040","lastPrice":169.64,"changeToPrevDayAbsolute":-2.34,
             "changeToPrevDayInPercent":-1.36,"open":169.02,
             "timestampLastPrice":"2026-06-26T21:57:06+02:00"}
            """;
        Optional<MarketSnapshot> snap = client.parse(body, "US67066G1040", "Xetra");
        assertTrue(snap.isPresent());
        MarketSnapshot s = snap.get();
        assertEquals(169.64, s.price(), 1e-9);
        assertEquals(171.98, s.previousClose(), 1e-9, "price fell 2.34 → prev close was higher");
        assertEquals(-1.36, s.dayChangePercent(), 1e-9);
        assertEquals("EUR", s.currency(), "Xetra/Frankfurt trade in EUR — no FX");
        assertEquals("Xetra", s.exchangeName());
        assertTrue(s.marketTimeEpochSeconds() > 0, "honest last-trade timestamp parsed");
        assertFalse(s.hasSpark());
    }

    @Test
    void parseRejectsMissingOrZeroPrice() {
        assertFalse(client.parse("{\"lastPrice\":0}", "X", "Xetra").isPresent());
        assertFalse(client.parse("{}", "X", "Xetra").isPresent());
        assertFalse(client.parse("not json", "X", "Xetra").isPresent());
    }
}
