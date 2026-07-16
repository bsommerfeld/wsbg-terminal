package de.bsommerfeld.wsbg.terminal.signals.estimates;

import de.bsommerfeld.wsbg.terminal.signals.SignalReading;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BaseRateConfidenceTest {

    @Test
    void largeSampleWithTightIntervalIsRobust() {
        Optional<SignalReading> reading = BaseRateConfidence.measure("Zins-Event", 100, 200);
        assertTrue(reading.isPresent());
        assertEquals(0.5, reading.get().value(), 1e-12);
        String interpretation = reading.get().interpretation();
        assertTrue(interpretation.contains("ROBUST"));
        assertTrue(interpretation.contains("citable as a prior"));
        assertTrue(interpretation.contains("Zins-Event"));
        assertTrue(interpretation.contains("n=200"));
        assertTrue(interpretation.contains("90% CI"));
    }

    @Test
    void mediumSampleIsIndicative() {
        Optional<SignalReading> reading = BaseRateConfidence.measure("Zins-Event", 10, 20);
        assertTrue(reading.isPresent());
        assertEquals(0.5, reading.get().value(), 1e-12);
        assertTrue(reading.get().interpretation().contains("INDICATIVE"));
        assertTrue(reading.get().interpretation().contains("tendency"));
    }

    @Test
    void tinySampleIsAnecdotalAndExplicitlyWorthless() {
        Optional<SignalReading> reading = BaseRateConfidence.measure("Zins-Event", 2, 5);
        assertTrue(reading.isPresent());
        assertEquals(0.4, reading.get().value(), 1e-12);
        String interpretation = reading.get().interpretation();
        assertTrue(interpretation.contains("ANECDOTAL"));
        assertTrue(interpretation.contains("practically worthless"));
        assertTrue(interpretation.contains("do NOT cite as evidence"));
        assertTrue(interpretation.contains("Caution"));
    }

    @Test
    void emptyOnInvalidCounts() {
        assertTrue(BaseRateConfidence.measure("Zins-Event", 0, 0).isEmpty());
        assertTrue(BaseRateConfidence.measure("Zins-Event", 6, 5).isEmpty());
        assertTrue(BaseRateConfidence.measure("Zins-Event", -1, 5).isEmpty());
        assertTrue(BaseRateConfidence.measure(null, 2, 5).isEmpty());
    }
}
