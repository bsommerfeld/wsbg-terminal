package de.bsommerfeld.wsbg.terminal.signals.price;

import de.bsommerfeld.wsbg.terminal.signals.SignalReading;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketRegimeHmmTest {

    /** Deterministische Reihe: wiederholtes Muster fester Amplituden, keine Randomness. */
    private static double[] cycle(int length, double... pattern) {
        double[] xs = new double[length];
        for (int i = 0; i < length; i++) {
            xs[i] = pattern[i % pattern.length];
        }
        return xs;
    }

    private static double[] concat(double[]... parts) {
        int total = 0;
        for (double[] p : parts) {
            total += p.length;
        }
        double[] out = new double[total];
        int pos = 0;
        for (double[] p : parts) {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    private static double[] calm(int length) {
        return cycle(length, 0.06, -0.04, 0.05, -0.07);
    }

    private static double[] normal(int length) {
        return cycle(length, 1.2, -0.8, 1.0, -1.4);
    }

    private static double[] stress(int length) {
        return cycle(length, 4.5, -3.5, 5.0, -4.0);
    }

    @Test
    void seriesEndingInLowVolatilityIsLabeledRuhe() {
        Optional<SignalReading> reading =
                MarketRegimeHmm.measure(concat(stress(40), normal(40), calm(40)));
        assertTrue(reading.isPresent());
        assertEquals(0.0, reading.get().value(), "RUHE muss Index 0 tragen");
        assertTrue(reading.get().formattedValue().startsWith("RUHE"));
        assertTrue(reading.get().interpretation().contains("sie sind hier selten"));
        assertEquals("market-regime-hmm", reading.get().id());
    }

    @Test
    void seriesEndingInMediumVolatilityIsLabeledNormal() {
        Optional<SignalReading> reading =
                MarketRegimeHmm.measure(concat(stress(40), calm(40), normal(40)));
        assertTrue(reading.isPresent());
        assertEquals(1.0, reading.get().value(), "NORMAL muss Index 1 tragen");
        assertTrue(reading.get().formattedValue().startsWith("NORMAL"));
        assertTrue(reading.get().interpretation().contains("Standardbasisraten gelten"));
    }

    @Test
    void seriesEndingInHighVolatilityIsLabeledStress() {
        Optional<SignalReading> reading =
                MarketRegimeHmm.measure(concat(calm(40), normal(40), stress(40)));
        assertTrue(reading.isPresent());
        assertEquals(2.0, reading.get().value(), "STRESS muss Index 2 tragen");
        assertTrue(reading.get().formattedValue().startsWith("STRESS"));
        assertTrue(reading.get().interpretation().contains("Korrelationen springen auf 1"));
    }

    @Test
    void interpretationAlwaysCarriesTheConditioningRule() {
        Optional<SignalReading> reading =
                MarketRegimeHmm.measure(concat(stress(40), normal(40), calm(40)));
        assertTrue(reading.isPresent());
        assertTrue(reading.get().interpretation()
                .contains("Alle anderen Signale in diesem Regime lesen"));
    }

    @Test
    void formattedValueCarriesLabelAndPosterior() {
        Optional<SignalReading> reading =
                MarketRegimeHmm.measure(concat(calm(40), normal(40), stress(40)));
        assertTrue(reading.isPresent());
        assertTrue(reading.get().formattedValue().contains("(p="));
    }

    @Test
    void shortSeriesCarriesCautionSuffix() {
        Optional<SignalReading> reading =
                MarketRegimeHmm.measure(concat(stress(20), normal(20), calm(20)));
        assertTrue(reading.isPresent());
        assertTrue(reading.get().interpretation().contains("Vorsicht"));
    }

    @Test
    void resultIsDeterministicAcrossRuns() {
        double[] series = concat(calm(40), normal(40), stress(40));
        Optional<SignalReading> first = MarketRegimeHmm.measure(series);
        Optional<SignalReading> second = MarketRegimeHmm.measure(series);
        assertTrue(first.isPresent());
        assertTrue(second.isPresent());
        assertEquals(first.get().value(), second.get().value());
        assertEquals(first.get().formattedValue(), second.get().formattedValue());
    }

    @Test
    void tooFewObservationsYieldEmpty() {
        assertTrue(MarketRegimeHmm.measure(concat(stress(20), normal(20), calm(19))).isEmpty());
        assertTrue(MarketRegimeHmm.measure(new double[0]).isEmpty());
        assertTrue(MarketRegimeHmm.measure(null).isEmpty());
    }

    @Test
    void constantSeriesYieldsEmpty() {
        double[] flat = new double[80];
        assertTrue(MarketRegimeHmm.measure(flat).isEmpty());
    }
}
