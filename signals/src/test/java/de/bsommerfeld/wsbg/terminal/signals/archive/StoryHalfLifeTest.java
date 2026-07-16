package de.bsommerfeld.wsbg.terminal.signals.archive;

import de.bsommerfeld.wsbg.terminal.signals.SignalReading;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoryHalfLifeTest {

    private static final List<Duration> LIFETIMES = List.of(
            Duration.ofHours(10), Duration.ofHours(12), Duration.ofHours(14), Duration.ofHours(16),
            Duration.ofHours(18), Duration.ofHours(20), Duration.ofHours(22), Duration.ofHours(24));

    @Test
    void storyFarBeyondTypicalLifetimeIsStructural() {
        Optional<SignalReading> reading = StoryHalfLife.measure(LIFETIMES, Duration.ofHours(100));

        assertTrue(reading.isPresent());
        assertTrue(reading.get().value() > 0.9, "erwartet Perzentil > 0.9, war " + reading.get().value());
        assertTrue(reading.get().interpretation().contains("STRUKTURELL"));
        assertEquals("story-half-life", reading.get().id());
    }

    @Test
    void storyPastMedianIsMature() {
        Optional<SignalReading> reading = StoryHalfLife.measure(LIFETIMES, Duration.ofHours(20));

        assertTrue(reading.isPresent());
        assertTrue(reading.get().value() >= 0.5 && reading.get().value() <= 0.9,
                "erwartet Perzentil in [0.5, 0.9], war " + reading.get().value());
        assertTrue(reading.get().interpretation().contains("Reif"));
    }

    @Test
    void youngStoryIsEpisodicallyNormal() {
        Optional<SignalReading> reading = StoryHalfLife.measure(LIFETIMES, Duration.ofHours(4));

        assertTrue(reading.isPresent());
        assertTrue(reading.get().value() < 0.5, "erwartet Perzentil < 0.5, war " + reading.get().value());
        assertTrue(reading.get().interpretation().contains("Episodisch"));
    }

    @Test
    void interpretationReportsFittedParametersAndCaution() {
        Optional<SignalReading> reading = StoryHalfLife.measure(LIFETIMES, Duration.ofHours(20));

        assertTrue(reading.isPresent());
        assertTrue(reading.get().interpretation().contains("k="));
        assertTrue(reading.get().interpretation().contains("lambda="));
        assertTrue(reading.get().interpretation().contains("Vorsicht"));
    }

    @Test
    void tooFewLifetimesYieldEmpty() {
        assertTrue(StoryHalfLife.measure(LIFETIMES.subList(0, 7), Duration.ofHours(5)).isEmpty());
        // Nullen und negative Dauern zaehlen nicht als Lebensdauern
        List<Duration> padded = List.of(
                Duration.ofHours(10), Duration.ofHours(12), Duration.ofHours(14), Duration.ofHours(16),
                Duration.ofHours(18), Duration.ofHours(20), Duration.ofHours(22), Duration.ZERO);
        assertTrue(StoryHalfLife.measure(padded, Duration.ofHours(5)).isEmpty());
    }
}
