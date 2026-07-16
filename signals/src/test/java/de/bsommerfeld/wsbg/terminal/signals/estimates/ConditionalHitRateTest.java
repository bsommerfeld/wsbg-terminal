package de.bsommerfeld.wsbg.terminal.signals.estimates;

import de.bsommerfeld.wsbg.terminal.signals.SignalReading;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ConditionalHitRateTest {

    private static List<ConditionalHitRate.Trial> trials(
            int highSuccesses, int highTotal, int lowSuccesses, int lowTotal) {
        List<ConditionalHitRate.Trial> trials = new ArrayList<>();
        for (int i = 0; i < highTotal; i++) {
            trials.add(new ConditionalHitRate.Trial(i < highSuccesses, true));
        }
        for (int i = 0; i < lowTotal; i++) {
            trials.add(new ConditionalHitRate.Trial(i < lowSuccesses, false));
        }
        return trials;
    }

    @Test
    void muchWorseUnderAttentionIsDevalued() {
        Optional<SignalReading> reading = ConditionalHitRate.measure(
                "Testsignal", trials(2, 12, 10, 12));
        assertTrue(reading.isPresent());
        assertTrue(reading.get().value() <= -0.15);
        assertTrue(reading.get().interpretation().contains("DEVALUED UNDER ATTENTION"));
        assertTrue(reading.get().interpretation().contains("Testsignal"));
        assertTrue(reading.get().interpretation().contains("n=12"));
        assertTrue(reading.get().interpretation().contains("90% CI"));
    }

    @Test
    void muchBetterUnderAttentionIsAmplified() {
        Optional<SignalReading> reading = ConditionalHitRate.measure(
                "Testsignal", trials(10, 12, 2, 12));
        assertTrue(reading.isPresent());
        assertTrue(reading.get().value() >= 0.15);
        assertTrue(reading.get().interpretation().contains("amplifies under attention"));
    }

    @Test
    void similarRatesAreNeutralAndFlaggedAsNotSignificant() {
        Optional<SignalReading> reading = ConditionalHitRate.measure(
                "Testsignal", trials(6, 12, 6, 12));
        assertTrue(reading.isPresent());
        assertTrue(Math.abs(reading.get().value()) < 0.15);
        assertTrue(reading.get().interpretation().contains("No robust difference"));
        assertTrue(reading.get().interpretation().contains("not statistically secured"));
    }

    @Test
    void smallBucketsCarryCaution() {
        Optional<SignalReading> reading = ConditionalHitRate.measure(
                "Testsignal", trials(6, 12, 6, 12));
        assertTrue(reading.isPresent());
        assertTrue(reading.get().interpretation().contains("Caution"));
    }

    @Test
    void emptyWhenOneBucketIsTooSmall() {
        assertTrue(ConditionalHitRate.measure("Testsignal", trials(5, 9, 10, 20)).isEmpty());
        assertTrue(ConditionalHitRate.measure("Testsignal", trials(10, 20, 5, 9)).isEmpty());
        assertTrue(ConditionalHitRate.measure("Testsignal", List.of()).isEmpty());
    }
}
