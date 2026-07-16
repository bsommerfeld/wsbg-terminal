package de.bsommerfeld.wsbg.terminal.signals.estimates;

import de.bsommerfeld.wsbg.terminal.signals.SignalReading;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EstimateFanDivergenceTest {

    private static final Map<Integer, Double> NARROW = Map.of(2026, 100.0, 2027, 101.0, 2028, 102.0);
    private static final Map<Integer, Double> WIDE = Map.of(2026, 100.0, 2027, 120.0, 2028, 150.0);

    @Test
    void wideningFanIsDetected() {
        Optional<SignalReading> reading = EstimateFanDivergence.measure(WIDE, NARROW);
        assertTrue(reading.isPresent());
        assertTrue(reading.get().value() >= 0.2);
        assertTrue(reading.get().interpretation().contains("FÄCHER ÖFFNET SICH"));
    }

    @Test
    void collapsingFanIsDetected() {
        Optional<SignalReading> reading = EstimateFanDivergence.measure(NARROW, WIDE);
        assertTrue(reading.isPresent());
        assertTrue(reading.get().value() <= -0.2);
        assertTrue(reading.get().interpretation().contains("FÄCHER KOLLABIERT"));
    }

    @Test
    void unchangedFanIsStable() {
        Optional<SignalReading> reading = EstimateFanDivergence.measure(WIDE, WIDE);
        assertTrue(reading.isPresent());
        assertEquals(0.0, reading.get().value(), 1e-12);
        assertTrue(reading.get().interpretation().contains("stabil"));
    }

    @Test
    void zeroPreviousCvOpeningCountsAsPlusOne() {
        Optional<SignalReading> reading = EstimateFanDivergence.measure(
                WIDE, Map.of(2026, 100.0, 2027, 100.0, 2028, 100.0));
        assertTrue(reading.isPresent());
        assertEquals(1.0, reading.get().value(), 1e-12);
        assertTrue(reading.get().interpretation().contains("FÄCHER ÖFFNET SICH"));
    }

    @Test
    void threeYearSnapshotsCarryCaution() {
        Optional<SignalReading> reading = EstimateFanDivergence.measure(WIDE, NARROW);
        assertTrue(reading.isPresent());
        assertTrue(reading.get().interpretation().contains("Vorsicht"));
    }

    @Test
    void emptyOnTooFewYears() {
        assertTrue(EstimateFanDivergence.measure(
                Map.of(2026, 100.0, 2027, 110.0), NARROW).isEmpty());
        assertTrue(EstimateFanDivergence.measure(
                NARROW, Map.of(2026, 100.0, 2027, 110.0)).isEmpty());
    }

    @Test
    void emptyOnZeroMean() {
        assertTrue(EstimateFanDivergence.measure(
                Map.of(2026, -1.0, 2027, 0.0, 2028, 1.0), NARROW).isEmpty());
    }
}
