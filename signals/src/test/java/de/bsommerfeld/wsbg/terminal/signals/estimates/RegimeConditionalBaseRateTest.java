package de.bsommerfeld.wsbg.terminal.signals.estimates;

import de.bsommerfeld.wsbg.terminal.signals.SignalReading;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegimeConditionalBaseRateTest {

    private static final Map<String, int[]> BY_REGIME = Map.of(
            "BULLE", new int[]{100, 200},
            "BÄR", new int[]{5, 20},
            "SEITWÄRTS", new int[]{1, 4});

    @Test
    void robustCurrentBucketContrastsOtherRegimes() {
        Optional<SignalReading> reading = RegimeConditionalBaseRate.measure(
                "Zins-Event", BY_REGIME, "BULLE");
        assertTrue(reading.isPresent());
        assertEquals(0.5, reading.get().value(), 1e-12);
        String interpretation = reading.get().interpretation();
        assertTrue(interpretation.contains("ROBUST"));
        assertTrue(interpretation.contains("by contrast"));
        assertTrue(interpretation.contains("BÄR"));
        assertTrue(interpretation.contains("SEITWÄRTS"));
        assertTrue(interpretation.contains("across regimes"));
        assertTrue(interpretation.contains("n=200"));
        assertTrue(interpretation.contains("90% CI"));
    }

    @Test
    void mediumCurrentBucketIsIndicative() {
        Optional<SignalReading> reading = RegimeConditionalBaseRate.measure(
                "Zins-Event", BY_REGIME, "BÄR");
        assertTrue(reading.isPresent());
        assertEquals(0.25, reading.get().value(), 1e-12);
        assertTrue(reading.get().interpretation().contains("INDICATIVE"));
        assertTrue(reading.get().interpretation().contains("tendency"));
    }

    @Test
    void tinyCurrentBucketIsAnecdotalWithCaution() {
        Optional<SignalReading> reading = RegimeConditionalBaseRate.measure(
                "Zins-Event", BY_REGIME, "SEITWÄRTS");
        assertTrue(reading.isPresent());
        assertEquals(0.25, reading.get().value(), 1e-12);
        String interpretation = reading.get().interpretation();
        assertTrue(interpretation.contains("ANECDOTAL"));
        assertTrue(interpretation.contains("practically worthless"));
        assertTrue(interpretation.contains("Caution"));
    }

    @Test
    void emptyWhenCurrentRegimeIsMissingOrEmptyBucket() {
        assertTrue(RegimeConditionalBaseRate.measure(
                "Zins-Event", BY_REGIME, "CRASH").isEmpty());
        assertTrue(RegimeConditionalBaseRate.measure(
                "Zins-Event", Map.of("BULLE", new int[]{0, 0}), "BULLE").isEmpty());
        assertTrue(RegimeConditionalBaseRate.measure(
                "Zins-Event", Map.of("BULLE", new int[]{5, 3}), "BULLE").isEmpty());
        assertTrue(RegimeConditionalBaseRate.measure("Zins-Event", null, "BULLE").isEmpty());
    }
}
