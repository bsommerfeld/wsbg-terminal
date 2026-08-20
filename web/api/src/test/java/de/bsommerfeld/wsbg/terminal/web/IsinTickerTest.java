package de.bsommerfeld.wsbg.terminal.web;

import de.bsommerfeld.wsbg.terminal.web.instrument.Isin;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.instrument.Ticker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IsinTickerTest {

    @Test
    void validIsinsParse() {
        assertEquals("US0378331005", Isin.of("US0378331005").value()); // Apple
        assertEquals("DE0007164600", Isin.of("de0007164600").value()); // SAP, lenient case
        assertEquals("DE0008469008", Isin.of(" DE0008469008 ").value()); // DAX, lenient space
        assertEquals("US", Isin.of("US0378331005").country());
    }

    @Test
    void invalidIsinsAreRejected() {
        assertTrue(Isin.parse(null).isEmpty());
        assertTrue(Isin.parse("").isEmpty());
        assertTrue(Isin.parse("APPLE").isEmpty());
        assertTrue(Isin.parse("US037833100").isEmpty());   // too short
        assertTrue(Isin.parse("US0378331004").isEmpty());  // wrong check digit
        assertTrue(Isin.parse("120378331005").isEmpty());  // no country prefix
        assertThrows(IllegalArgumentException.class, () -> Isin.of("nope"));
    }

    @Test
    void looksLikeIsAShapeProbe() {
        assertTrue(Isin.looksLike("US0378331005"));
        assertFalse(Isin.looksLike("AAPL"));
    }

    @Test
    void tickersNormalizeAndSplitVenueSuffix() {
        assertEquals("AAPL", Ticker.of(" aapl ").value());
        assertEquals("RHM", Ticker.of("RHM.DE").baseSymbol());
        assertEquals("BRK.B", Ticker.of("BRK.B").value());
        assertTrue(Ticker.parse("").isEmpty());
        assertTrue(Ticker.parse(null).isEmpty());
    }

    @Test
    void resolvedInstrumentToleratesPartialResolution() {
        ResolvedInstrument nameOnly = ResolvedInstrument.ofName("Meta Wolf");
        assertFalse(nameOnly.hasHardKey());
        assertEquals("Meta Wolf", nameOnly.name());

        ResolvedInstrument withIsin = new ResolvedInstrument(
                Isin.parse("DE0007164600"), java.util.Optional.empty(), "SAP");
        assertTrue(withIsin.hasHardKey());
    }
}
