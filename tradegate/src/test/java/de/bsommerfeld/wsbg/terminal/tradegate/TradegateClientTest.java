package de.bsommerfeld.wsbg.terminal.tradegate;

import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Parsing of the Tradegate refresh.php quote into an EUR snapshot. No network. */
class TradegateClientTest {

    private final TradegateClient client = new TradegateClient();

    @Test
    void parsesNumericQuote() {
        String body = """
            {"bid":177.50,"ask":177.80,"last":177.65,"high":179.00,"low":176.20,
             "umsatz":12345,"Name":"NVIDIA","Isin":"US67066G1040"}
            """;
        Optional<MarketSnapshot> s = client.parse(body, "US67066G1040");
        assertTrue(s.isPresent());
        assertEquals(177.65, s.get().price(), 1e-9);
        assertEquals(179.00, s.get().dayHigh(), 1e-9);
        assertEquals("EUR", s.get().currency());
        assertEquals("Tradegate", s.get().exchangeName());
    }

    @Test
    void parsesGermanFormattedStrings() {
        String body = """
            {"last":"1.234,56","high":"1.240,00","low":"1.230,10","umsatz":"900"}
            """;
        Optional<MarketSnapshot> s = client.parse(body, "DE0001234567");
        assertTrue(s.isPresent());
        assertEquals(1234.56, s.get().price(), 1e-9);
        assertEquals(1240.00, s.get().dayHigh(), 1e-9);
    }

    @Test
    void missingLastYieldsNothing() {
        assertFalse(client.parse("{\"bid\":1.0}", "X").isPresent());
        assertFalse(client.parse("nonsense", "X").isPresent());
    }
}
