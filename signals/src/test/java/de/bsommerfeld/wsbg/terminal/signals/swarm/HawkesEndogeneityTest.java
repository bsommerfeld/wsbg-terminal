package de.bsommerfeld.wsbg.terminal.signals.swarm;

import de.bsommerfeld.wsbg.terminal.signals.SignalReading;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HawkesEndogeneityTest {

    private static final Instant BASE = Instant.parse("2026-07-16T10:00:00Z");

    @Test
    void evenlySpacedEventsAreExogenous() {
        List<Instant> events = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            events.add(BASE.plusSeconds(i * 60L));
        }
        SignalReading reading = HawkesEndogeneity.measure(events).orElseThrow();
        assertTrue(reading.value() < 0.3, "value=" + reading.value());
        assertTrue(reading.interpretation().contains("EXOGENOUS"), reading.interpretation());
        assertFalse(reading.interpretation().contains("Caution"), reading.interpretation());
    }

    @Test
    void seedsWithTwoQuickRepliesAreMixed() {
        // 20 root events at 10-minute intervals, each with two quick follow-up events.
        List<Instant> events = new ArrayList<>();
        for (int s = 0; s < 20; s++) {
            long seed = s * 600L;
            events.add(BASE.plusSeconds(seed));
            events.add(BASE.plusSeconds(seed + 10));
            events.add(BASE.plusSeconds(seed + 25));
        }
        SignalReading reading = HawkesEndogeneity.measure(events).orElseThrow();
        assertTrue(reading.value() > 0.3 && reading.value() < 0.7, "value=" + reading.value());
        assertTrue(reading.interpretation().contains("MIXED"), reading.interpretation());
    }

    @Test
    void denseCascadesAreEndogenous() {
        // 5 root events, each dragging a dense, decaying cascade behind it.
        int[] offsets = {3, 6, 10, 15, 21, 28, 36, 45, 55, 66};
        List<Instant> events = new ArrayList<>();
        for (int s = 0; s < 5; s++) {
            long seed = s * 600L;
            events.add(BASE.plusSeconds(seed));
            for (int offset : offsets) {
                events.add(BASE.plusSeconds(seed + offset));
            }
        }
        SignalReading reading = HawkesEndogeneity.measure(events).orElseThrow();
        assertTrue(reading.value() > 0.7, "value=" + reading.value());
        assertTrue(reading.interpretation().contains("ENDOGENOUS"), reading.interpretation());
    }

    @Test
    void thinButSufficientDataCarriesCaution() {
        List<Instant> events = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            events.add(BASE.plusSeconds(i * 60L));
        }
        SignalReading reading = HawkesEndogeneity.measure(events).orElseThrow();
        assertTrue(reading.interpretation().contains("Caution"), reading.interpretation());
    }

    @Test
    void tooFewEventsYieldEmpty() {
        List<Instant> events = new ArrayList<>();
        for (int i = 0; i < 19; i++) {
            events.add(BASE.plusSeconds(i * 30L));
        }
        Optional<SignalReading> reading = HawkesEndogeneity.measure(events);
        assertTrue(reading.isEmpty());
        assertTrue(HawkesEndogeneity.measure(null).isEmpty());
    }
}
