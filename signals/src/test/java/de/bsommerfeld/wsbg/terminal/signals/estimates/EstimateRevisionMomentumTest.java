package de.bsommerfeld.wsbg.terminal.signals.estimates;

import de.bsommerfeld.wsbg.terminal.signals.SignalReading;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EstimateRevisionMomentumTest {

    @Test
    void upwardRevisionsYieldUpwardMomentum() {
        Optional<SignalReading> reading = EstimateRevisionMomentum.measure(
                Map.of(2026, 1.0, 2027, 2.0),
                Map.of(2026, 1.1, 2027, 2.2),
                30.0);
        assertTrue(reading.isPresent());
        assertTrue(reading.get().value() >= 1.0);
        assertTrue(reading.get().interpretation().contains("AUFWÄRTS-MOMENTUM"));
    }

    @Test
    void downwardRevisionsYieldDownwardMomentum() {
        Optional<SignalReading> reading = EstimateRevisionMomentum.measure(
                Map.of(2026, 1.0, 2027, 2.0),
                Map.of(2026, 0.9, 2027, 1.8),
                30.0);
        assertTrue(reading.isPresent());
        assertTrue(reading.get().value() <= -1.0);
        assertTrue(reading.get().interpretation().contains("ABWÄRTS-MOMENTUM"));
    }

    @Test
    void tinyRevisionsAreNeutral() {
        Optional<SignalReading> reading = EstimateRevisionMomentum.measure(
                Map.of(2026, 1.0, 2027, 2.0),
                Map.of(2026, 1.001, 2027, 2.002),
                30.0);
        assertTrue(reading.isPresent());
        assertTrue(Math.abs(reading.get().value()) < 1.0);
        assertTrue(reading.get().interpretation().contains("Keine nennenswerte Revision"));
    }

    @Test
    void singleCommonYearCarriesCaution() {
        Optional<SignalReading> reading = EstimateRevisionMomentum.measure(
                Map.of(2026, 1.0),
                Map.of(2026, 1.2),
                30.0);
        assertTrue(reading.isPresent());
        assertTrue(reading.get().interpretation().contains("Vorsicht"));
        assertEquals("estimate-revision-momentum", reading.get().id());
    }

    @Test
    void emptyOnTooFewDaysBetweenSnapshots() {
        assertTrue(EstimateRevisionMomentum.measure(
                Map.of(2026, 1.0), Map.of(2026, 1.2), 5.0).isEmpty());
    }

    @Test
    void emptyWithoutCommonYearWithNonZeroOldValue() {
        assertTrue(EstimateRevisionMomentum.measure(
                Map.of(2026, 0.0), Map.of(2026, 1.2), 30.0).isEmpty());
        assertTrue(EstimateRevisionMomentum.measure(
                Map.of(2026, 1.0), Map.of(2027, 1.2), 30.0).isEmpty());
    }
}
