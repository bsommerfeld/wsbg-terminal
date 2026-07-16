package de.bsommerfeld.wsbg.terminal.signals.swarm;

import de.bsommerfeld.wsbg.terminal.signals.SignalReading;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthorCohortFreshnessTest {

    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");
    private static final Duration WINDOW = Duration.ofDays(7);

    @Test
    void unusuallyFreshCohortIsPumpSuspicion() {
        Set<String> active = new HashSet<>();
        Map<String, Instant> firstSeen = new HashMap<>();
        // 6 never-before-seen accounts, 2 old hands - small, very fresh cohort.
        for (int i = 0; i < 6; i++) {
            active.add("fresh" + i);
        }
        for (int i = 0; i < 2; i++) {
            active.add("veteran" + i);
            firstSeen.put("veteran" + i, NOW.minus(Duration.ofDays(400)));
        }
        SignalReading reading = AuthorCohortFreshness
                .measure(active, firstSeen, NOW, WINDOW, 0.20).orElseThrow();
        assertEquals(0.75, reading.value(), 1e-9);
        assertTrue(reading.interpretation().contains("PUMP SUSPICION"), reading.interpretation());
        assertTrue(reading.interpretation().contains("baseline 0.20"), reading.interpretation());
        assertTrue(reading.interpretation().contains("Caution"), reading.interpretation());
    }

    @Test
    void veteranCohortIsOrganic() {
        Set<String> active = new HashSet<>();
        Map<String, Instant> firstSeen = new HashMap<>();
        for (int i = 0; i < 12; i++) {
            active.add("veteran" + i);
            firstSeen.put("veteran" + i, NOW.minus(Duration.ofDays(100 + i)));
        }
        SignalReading reading = AuthorCohortFreshness
                .measure(active, firstSeen, NOW, WINDOW, 0.20).orElseThrow();
        assertEquals(0.0, reading.value(), 1e-9);
        assertTrue(reading.interpretation().contains("ORGANIC"), reading.interpretation());
        assertTrue(reading.interpretation().contains("baseline 0.20"), reading.interpretation());
    }

    @Test
    void moderatelyFreshCohortIsElevated() {
        Set<String> active = new HashSet<>();
        Map<String, Instant> firstSeen = new HashMap<>();
        // 4 of 10 young (seen within the window), baseline 0.20:
        // 0.4 > 1.5*0.2, but not > 0.5 -> elevated instead of pump.
        for (int i = 0; i < 4; i++) {
            active.add("young" + i);
            firstSeen.put("young" + i, NOW.minus(Duration.ofDays(2)));
        }
        for (int i = 0; i < 6; i++) {
            active.add("veteran" + i);
            firstSeen.put("veteran" + i, NOW.minus(Duration.ofDays(300)));
        }
        SignalReading reading = AuthorCohortFreshness
                .measure(active, firstSeen, NOW, WINDOW, 0.20).orElseThrow();
        assertEquals(0.4, reading.value(), 1e-9);
        assertTrue(reading.interpretation().contains("ELEVATED"), reading.interpretation());
        assertTrue(reading.interpretation().contains("baseline 0.20"), reading.interpretation());
    }

    @Test
    void tooFewAuthorsYieldEmpty() {
        Set<String> active = Set.of("a1", "a2", "a3", "a4");
        assertTrue(AuthorCohortFreshness
                .measure(active, Map.of(), NOW, WINDOW, 0.20).isEmpty());
        assertTrue(AuthorCohortFreshness
                .measure(null, Map.of(), NOW, WINDOW, 0.20).isEmpty());
    }
}
