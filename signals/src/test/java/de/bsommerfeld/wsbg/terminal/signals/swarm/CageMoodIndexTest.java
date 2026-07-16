package de.bsommerfeld.wsbg.terminal.signals.swarm;

import de.bsommerfeld.wsbg.terminal.signals.SignalReading;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CageMoodIndexTest {

    private static double[] flat(double value, int n) {
        double[] out = new double[n];
        java.util.Arrays.fill(out, value);
        return out;
    }

    private static double[] ramp(int n) {
        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = i;
        return out;
    }

    @Test
    void topOfRecordComponentsReadExtremeGreed() {
        Optional<SignalReading> reading = CageMoodIndex.measure(List.of(
                new CageMoodIndex.Component("direction", 99, ramp(30)),
                new CageMoodIndex.Component("heat", 99, ramp(30)),
                new CageMoodIndex.Component("focus", 99, ramp(30))));
        assertTrue(reading.isPresent());
        assertTrue(reading.get().value() > 90);
        assertTrue(reading.get().interpretation().contains("EXTREME GREED"));
        assertTrue(reading.get().interpretation().contains("direction"));
        assertFalse(reading.get().interpretation().contains("Caution"),
                "30 days of history is not thin");
    }

    @Test
    void bottomOfRecordReadsExtremeFearAndMidReadsNeutral() {
        Optional<SignalReading> fear = CageMoodIndex.measure(List.of(
                new CageMoodIndex.Component("direction", -1, ramp(30)),
                new CageMoodIndex.Component("heat", -1, ramp(30))));
        assertTrue(fear.isPresent());
        assertTrue(fear.get().interpretation().contains("EXTREME FEAR"));

        Optional<SignalReading> neutral = CageMoodIndex.measure(List.of(
                new CageMoodIndex.Component("direction", 15, ramp(30)),
                new CageMoodIndex.Component("heat", 14.5, ramp(30))));
        assertTrue(neutral.isPresent());
        assertTrue(neutral.get().interpretation().contains("NEUTRAL"),
                neutral.get().interpretation());
    }

    @Test
    void greedAndFearSidesCarryTheirBands() {
        Optional<SignalReading> greed = CageMoodIndex.measure(List.of(
                new CageMoodIndex.Component("direction", 20, ramp(30)),
                new CageMoodIndex.Component("heat", 18, ramp(30))));
        assertTrue(greed.isPresent());
        assertTrue(greed.get().interpretation().contains("GREED side"));

        Optional<SignalReading> fearSide = CageMoodIndex.measure(List.of(
                new CageMoodIndex.Component("direction", 10, ramp(30)),
                new CageMoodIndex.Component("heat", 9, ramp(30))));
        assertTrue(fearSide.isPresent());
        assertTrue(fearSide.get().interpretation().contains("FEAR side"));
    }

    @Test
    void thinHistoryGetsCautionAndTooFewComponentsStayEmpty() {
        Optional<SignalReading> thin = CageMoodIndex.measure(List.of(
                new CageMoodIndex.Component("direction", 5, ramp(12)),
                new CageMoodIndex.Component("heat", 5, ramp(12))));
        assertTrue(thin.isPresent());
        assertTrue(thin.get().interpretation().contains("Caution"));

        assertTrue(CageMoodIndex.measure(List.of(
                new CageMoodIndex.Component("direction", 5, ramp(30)),
                new CageMoodIndex.Component("heat", 5, ramp(5)))).isEmpty(),
                "one scorable component is no composite");
        assertTrue(CageMoodIndex.measure(List.of()).isEmpty());
        assertTrue(CageMoodIndex.composite(null).isEmpty());
    }

    @Test
    void rawComponentsFollowTheSharedFormulas() {
        double[] c = CageMoodIndex.rawComponents(3, 1, 2, 0, 0.4);
        assertEquals(75.0, c[0], 1e-9);           // direction: 3 of 4 bull
        assertEquals(75.0, c[1], 1e-9);           // heat: 50 + 50*(2-0)/4
        assertEquals(60.0, c[2], 1e-9);           // focus: (1-0.4)*100
        double[] silent = CageMoodIndex.rawComponents(0, 0, 0, 0, Double.NaN);
        assertTrue(Double.isNaN(silent[0]) && Double.isNaN(silent[1]) && Double.isNaN(silent[2]));
        assertEquals(3, CageMoodIndex.componentLabels().size());
    }
}
