package de.bsommerfeld.wsbg.terminal.signals.world;

import de.bsommerfeld.wsbg.terminal.signals.SignalReading;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupplyChainLagTest {

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

    @Test
    void confirmedBandOnPureShiftAndEmbedsLagAndLabels() {
        int n = 120;
        double[] cause = lcg(42, n);
        double[] effect = new double[n];
        for (int i = 3; i < n; i++) {
            effect[i] = cause[i - 3];
        }
        SignalReading reading = SupplyChainLag
                .measure(cause, effect, 5, "pegelFracht", "pegelMarge")
                .orElseThrow();
        assertTrue(reading.value() >= 0.4);
        assertTrue(reading.interpretation().contains("VORLAUFKETTE BESTÄTIGT"));
        assertTrue(reading.interpretation().contains("~3 Tage"));
        assertTrue(reading.interpretation().contains("pegelFracht"));
        assertTrue(reading.interpretation().contains("pegelMarge"));
        // 120 Tage sind komfortabel -> kein Vorsichts-Zusatz
        assertFalse(reading.interpretation().contains("Vorsicht"));
        assertEquals("supply-chain-lag", reading.id());
    }

    @Test
    void weakBandOnLooselyCoupledSeries() {
        int n = 120;
        double[] cause = lcg(42, n);
        double[] noise = lcg(7, n);
        double[] effect = new double[n];
        for (int i = 0; i < n; i++) {
            effect[i] = (i < 2 ? 0 : 0.45 * cause[i - 2]) + noise[i];
        }
        SignalReading reading = SupplyChainLag
                .measure(cause, effect, 5, "pegelFracht", "pegelMarge")
                .orElseThrow();
        assertTrue(reading.value() >= 0.2 && reading.value() < 0.4);
        assertTrue(reading.interpretation().contains("Nebenindiz"));
        assertTrue(reading.interpretation().contains("pegelFracht"));
    }

    @Test
    void noneBandOnIndependentSeries() {
        int n = 120;
        double[] cause = lcg(42, n);
        double[] effect = lcg(99, n);
        SignalReading reading = SupplyChainLag
                .measure(cause, effect, 5, "pegelFracht", "pegelMarge")
                .orElseThrow();
        assertTrue(reading.value() < 0.2);
        assertTrue(reading.interpretation().contains("verwerfen"));
    }

    @Test
    void cautionSuffixOnThinData() {
        int n = 30; // >= 5 + 20, aber unter der Komfort-Schwelle
        double[] cause = lcg(42, n);
        double[] effect = new double[n];
        for (int i = 3; i < n; i++) {
            effect[i] = cause[i - 3];
        }
        SignalReading reading = SupplyChainLag
                .measure(cause, effect, 5, "pegelFracht", "pegelMarge")
                .orElseThrow();
        assertTrue(reading.interpretation().contains("Vorsicht"));
    }

    @Test
    void emptyOnTooShortSeriesOrBadLag() {
        double[] a = lcg(1, 24);
        double[] b = lcg(2, 24);
        // 24 < maxLagDays + 20
        assertEquals(Optional.empty(), SupplyChainLag.measure(a, b, 5, "pegelA", "pegelB"));
        // maxLagDays < 1
        double[] c = lcg(3, 60);
        double[] d = lcg(4, 60);
        assertEquals(Optional.empty(), SupplyChainLag.measure(c, d, 0, "pegelA", "pegelB"));
        // ungleiche Laengen
        assertEquals(Optional.empty(), SupplyChainLag.measure(c, lcg(4, 59), 5, "pegelA", "pegelB"));
    }
}
