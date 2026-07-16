package de.bsommerfeld.wsbg.terminal.signals.world;

import de.bsommerfeld.wsbg.terminal.signals.SignalReading;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GroundUnrestResidualTest {

    private static final int DAYS = 40;

    /** Deterministische Pseudozufalls-Reihe (LCG), Werte in [-1, 1). */
    private static double[] lcg(long seed, int n) {
        double[] out = new double[n];
        long s = seed;
        for (int i = 0; i < n; i++) {
            s = (s * 1103515245L + 12345L) & 0x7fffffffL;
            out[i] = (s % 2000) / 1000.0 - 1.0;
        }
        return out;
    }

    private static double[] press() {
        double[] raw = lcg(5, DAYS);
        double[] press = new double[DAYS];
        for (int i = 0; i < DAYS; i++) {
            press[i] = 10 + 2 * raw[i];
        }
        return press;
    }

    /**
     * Zivilschicht = 2 + 0.5 * Presse plus alternierendes +-0.1-Residuum;
     * der letzte Tag bekommt stattdessen das uebergebene Extra-Residuum.
     */
    private static double[] civil(double[] press, double lastExtra) {
        double[] civil = new double[DAYS];
        for (int i = 0; i < DAYS; i++) {
            civil[i] = 2 + 0.5 * press[i] + (i % 2 == 0 ? 0.1 : -0.1);
        }
        civil[DAYS - 1] = 2 + 0.5 * press[DAYS - 1] + lastExtra;
        return civil;
    }

    @Test
    void coveredBandWhenCivilMatchesWhatPressExplains() {
        double[] press = press();
        SignalReading reading = GroundUnrestResidual
                .measure(civil(press, 0.0), press)
                .orElseThrow();
        assertTrue(reading.value() < 1.0);
        assertTrue(reading.interpretation().contains("covered"));
        // 40 Tage sind unter der Komfort-Schwelle -> Vorsichts-Zusatz
        assertTrue(reading.interpretation().contains("Caution"));
        assertEquals("ground-unrest-residual", reading.id());
    }

    @Test
    void slightBandOnModestUnexplainedUnrest() {
        double[] press = press();
        SignalReading reading = GroundUnrestResidual
                .measure(civil(press, 0.15), press)
                .orElseThrow();
        assertTrue(reading.value() >= 1.0 && reading.value() < 2.0);
        assertTrue(reading.interpretation().contains("head start"));
    }

    @Test
    void earlyBandOnStrongUnexplainedUnrest() {
        double[] press = press();
        SignalReading reading = GroundUnrestResidual
                .measure(civil(press, 0.5), press)
                .orElseThrow();
        assertTrue(reading.value() >= 2.0);
        assertTrue(reading.interpretation().contains("WE ARE EARLY"));
    }

    @Test
    void emptyOnTooFewDaysOrLengthMismatch() {
        double[] shortCivil = lcg(1, 29);
        double[] shortPress = lcg(2, 29);
        assertEquals(Optional.empty(), GroundUnrestResidual.measure(shortCivil, shortPress));
        assertEquals(Optional.empty(), GroundUnrestResidual.measure(lcg(1, 40), lcg(2, 39)));
    }
}
