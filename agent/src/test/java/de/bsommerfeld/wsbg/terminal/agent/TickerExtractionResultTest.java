package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.agent.TickerExtractionResult.TickerMention;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the TickerExtractionResult record and its nested types.
 */
class TickerExtractionResultTest {

    @Test
    void emptyResult_shouldHaveNoMentions() {
        assertTrue(TickerExtractionResult.EMPTY.mentions().isEmpty());
    }

    @Test
    void mentions_shouldPreserveOrder() {
        var result = new TickerExtractionResult(List.of(
                new TickerMention("AAPL", "STOCK", "Apple Inc."),
                new TickerMention("DAX", "INDEX", "DAX 40"),
                new TickerMention("GOLD", "COMMODITY", null)));

        assertEquals(3, result.mentions().size());
        assertEquals("AAPL", result.mentions().get(0).symbol());
        assertEquals("DAX", result.mentions().get(1).symbol());
        assertEquals("GOLD", result.mentions().get(2).symbol());
    }

    @Test
    void tickerMention_shouldAllowNullName() {
        var mention = new TickerMention("BTC", "CRYPTO", null);
        assertNull(mention.name());
        assertEquals("BTC", mention.symbol());
        assertEquals("CRYPTO", mention.type());
    }

    @Test
    void tickerMention_shouldSupportAllInstrumentTypes() {
        List<String> types = List.of(
                "STOCK", "ETF", "ETC", "INDEX", "COMMODITY", "CRYPTO", "DERIVATIVE", "UNKNOWN");

        for (String type : types) {
            var mention = new TickerMention("TEST", type, null);
            assertEquals(type, mention.type());
        }
    }
}
