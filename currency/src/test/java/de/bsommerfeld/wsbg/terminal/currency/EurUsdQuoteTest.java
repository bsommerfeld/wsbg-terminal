package de.bsommerfeld.wsbg.terminal.currency;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EurUsdQuoteTest {

    private static final Instant NOW = Instant.parse("2026-05-28T12:00:00Z");

    @Test
    void firstObservationIsNeutral() {
        EurUsdQuote q = EurUsdQuote.of(1.0876, null, EurUsdQuote.Source.YAHOO, NOW);
        assertEquals(EurUsdQuote.Direction.NEUTRAL, q.direction());
    }

    @Test
    void unchangedRateIsNeutral() {
        EurUsdQuote q = EurUsdQuote.of(1.0876, 1.0876, EurUsdQuote.Source.YAHOO, NOW);
        assertEquals(EurUsdQuote.Direction.NEUTRAL, q.direction());
    }

    @Test
    void higherThanPreviousIsUp() {
        EurUsdQuote q = EurUsdQuote.of(1.0900, 1.0876, EurUsdQuote.Source.YAHOO, NOW);
        assertEquals(EurUsdQuote.Direction.UP, q.direction());
    }

    @Test
    void lowerThanPreviousIsDown() {
        EurUsdQuote q = EurUsdQuote.of(1.0850, 1.0876, EurUsdQuote.Source.YAHOO, NOW);
        assertEquals(EurUsdQuote.Direction.DOWN, q.direction());
    }

    @Test
    void preservesSourceAndTimestamp() {
        EurUsdQuote q = EurUsdQuote.of(1.0832, 1.0830, EurUsdQuote.Source.FRANKFURTER, NOW);
        assertEquals(EurUsdQuote.Source.FRANKFURTER, q.source());
        assertEquals(NOW, q.fetchedAt());
        assertEquals(1.0830, q.previousRate(), 1e-9);
    }
}
