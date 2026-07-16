package de.bsommerfeld.wsbg.terminal.signals.archive;

import de.bsommerfeld.wsbg.terminal.signals.SignalReading;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BurstinessTest {

    private static List<Instant> fromGaps(long startEpochSecond, long[] gapsSeconds) {
        List<Instant> events = new ArrayList<>();
        long t = startEpochSecond;
        events.add(Instant.ofEpochSecond(t));
        for (long g : gapsSeconds) {
            t += g;
            events.add(Instant.ofEpochSecond(t));
        }
        return events;
    }

    @Test
    void perfectlyRegularFlowIsBlueChipProfile() {
        List<Instant> events = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            events.add(Instant.ofEpochSecond(1000L + i * 3600L));
        }
        Optional<SignalReading> reading = Burstiness.measure(events);

        assertTrue(reading.isPresent());
        assertTrue(reading.get().value() <= 0.1, "erwartet B <= 0.1, war " + reading.get().value());
        assertTrue(reading.get().interpretation().contains("TAKT"));
        assertEquals("burstiness", reading.get().id());
    }

    @Test
    void spikyFlowIsExplosiveProfile() {
        long[] gaps = new long[19];
        java.util.Arrays.fill(gaps, 60);
        gaps[9] = 5_000_000;
        Optional<SignalReading> reading = Burstiness.measure(fromGaps(0, gaps));

        assertTrue(reading.isPresent());
        assertTrue(reading.get().value() >= 0.5, "erwartet B >= 0.5, war " + reading.get().value());
        assertTrue(reading.get().interpretation().contains("EXPLOSIVES PROFIL"));
    }

    @Test
    void unevenButCarriedFlowIsMixedProfile() {
        long[] gaps = {60, 60, 60, 800_000, 60, 60, 900_000, 60, 60};
        Optional<SignalReading> reading = Burstiness.measure(fromGaps(0, gaps));

        assertTrue(reading.isPresent());
        assertTrue(reading.get().value() > 0.1 && reading.get().value() < 0.5,
                "erwartet Mittelband, war " + reading.get().value());
        assertTrue(reading.get().interpretation().contains("Gemischtes Profil"));
    }

    @Test
    void thinHistoryCarriesCautionNote() {
        long[] gaps = {60, 60, 60, 800_000, 60, 60, 900_000, 60, 60};
        Optional<SignalReading> reading = Burstiness.measure(fromGaps(0, gaps));

        assertTrue(reading.isPresent());
        assertTrue(reading.get().interpretation().contains("Vorsicht"));
    }

    @Test
    void tooFewEventsYieldEmpty() {
        List<Instant> events = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            events.add(Instant.ofEpochSecond(1000L + i * 3600L));
        }
        assertTrue(Burstiness.measure(events).isEmpty());
        assertTrue(Burstiness.measure(List.of()).isEmpty());
    }
}
