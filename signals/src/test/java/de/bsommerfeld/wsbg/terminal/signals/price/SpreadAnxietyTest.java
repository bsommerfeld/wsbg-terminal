package de.bsommerfeld.wsbg.terminal.signals.price;

import de.bsommerfeld.wsbg.terminal.signals.SignalReading;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpreadAnxietyTest {

    /** 60 historische relative Spreads um 0.11 %, deterministisch alternierend. */
    private static double[] history(int length) {
        double[] xs = new double[length];
        for (int i = 0; i < length; i++) {
            xs[i] = (i % 2 == 0) ? 0.0010 : 0.0012;
        }
        return xs;
    }

    @Test
    void wideSpreadWithoutMoveOrHeadlineTriggersAnxiety() {
        Optional<SignalReading> reading =
                SpreadAnxiety.measure(100.0, 100.15, history(60), 0.1, false);
        assertTrue(reading.isPresent());
        assertTrue(reading.get().value() >= 2.0, "z-Score muss ueber der Alarmschwelle liegen");
        assertTrue(reading.get().interpretation().contains("ANXIETY SENSOR FIRING"));
        assertEquals("spread-anxiety", reading.get().id());
    }

    @Test
    void wideSpreadWithHeadlineIsConsistentNotAlarming() {
        Optional<SignalReading> reading =
                SpreadAnxiety.measure(100.0, 100.15, history(60), 0.1, true);
        assertTrue(reading.isPresent());
        assertTrue(reading.get().value() >= 2.0);
        assertTrue(reading.get().interpretation().contains("consistent with the news flow"));
        assertFalse(reading.get().interpretation().contains("ANXIETY SENSOR"));
    }

    @Test
    void wideSpreadWithPriceMoveIsConsistentNotAlarming() {
        Optional<SignalReading> reading =
                SpreadAnxiety.measure(100.0, 100.15, history(60), 1.2, false);
        assertTrue(reading.isPresent());
        assertTrue(reading.get().interpretation().contains("consistent with the news flow"));
    }

    @Test
    void normalSpreadIsUnremarkable() {
        Optional<SignalReading> reading =
                SpreadAnxiety.measure(100.0, 100.11, history(60), 0.1, false);
        assertTrue(reading.isPresent());
        assertTrue(Math.abs(reading.get().value()) < 2.0);
        assertTrue(reading.get().interpretation().contains("Unremarkable"));
    }

    @Test
    void tightSpreadSignalsCalmWaters() {
        Optional<SignalReading> reading =
                SpreadAnxiety.measure(100.0, 100.08, history(60), 0.1, false);
        assertTrue(reading.isPresent());
        assertTrue(reading.get().value() <= -1.5, "z-Score muss unter der Eng-Schwelle liegen");
        assertTrue(reading.get().interpretation().contains("calm waters"));
    }

    @Test
    void thinHistoryCarriesCautionSuffix() {
        Optional<SignalReading> reading =
                SpreadAnxiety.measure(100.0, 100.11, history(30), 0.1, false);
        assertTrue(reading.isPresent());
        assertTrue(reading.get().interpretation().contains("Caution"));
    }

    @Test
    void tooFewHistoricalSpreadsYieldsEmpty() {
        assertTrue(SpreadAnxiety.measure(100.0, 100.11, history(29), 0.1, false).isEmpty());
        assertTrue(SpreadAnxiety.measure(100.0, 100.11, new double[0], 0.1, false).isEmpty());
        assertTrue(SpreadAnxiety.measure(100.0, 100.11, null, 0.1, false).isEmpty());
    }

    @Test
    void invalidQuotesYieldEmpty() {
        double[] history = history(60);
        assertTrue(SpreadAnxiety.measure(0.0, 100.11, history, 0.1, false).isEmpty());
        assertTrue(SpreadAnxiety.measure(-1.0, 100.11, history, 0.1, false).isEmpty());
        assertTrue(SpreadAnxiety.measure(100.11, 100.0, history, 0.1, false).isEmpty(),
                "ask unter bid ist ungueltig");
        assertTrue(SpreadAnxiety.measure(Double.NaN, 100.0, history, 0.1, false).isEmpty());
    }

    @Test
    void formattedValueCarriesScaleAndUnit() {
        Optional<SignalReading> reading =
                SpreadAnxiety.measure(100.0, 100.15, history(60), 0.1, false);
        assertTrue(reading.isPresent());
        assertTrue(reading.get().formattedValue().contains("z-score"));
        assertTrue(reading.get().formattedValue().contains("%"));
    }

    @Test
    void interpretationNeverUsesLongDashes() {
        Optional<SignalReading> reading =
                SpreadAnxiety.measure(100.0, 100.15, Arrays.copyOf(history(30), 30), 0.1, false);
        assertTrue(reading.isPresent());
        String all = reading.get().interpretation() + reading.get().definition()
                + reading.get().formattedValue() + reading.get().title();
        assertFalse(all.contains("—"), "Geviertstrich ist verboten");
        assertFalse(all.contains("–"), "Halbgeviertstrich ist verboten");
    }
}
