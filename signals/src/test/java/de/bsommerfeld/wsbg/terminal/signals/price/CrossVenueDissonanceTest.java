package de.bsommerfeld.wsbg.terminal.signals.price;

import de.bsommerfeld.wsbg.terminal.signals.SignalReading;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrossVenueDissonanceTest {

    private static final String VENUE_A = "Alpha";
    private static final String VENUE_B = "Beta";

    /** Basis-Differenzreihe: deterministisch alternierend +-0.0005 (Log-Differenz). */
    private static double[] baseDifferences(int length) {
        double[] d = new double[length];
        for (int i = 0; i < length; i++) {
            d[i] = (i % 2 == 0) ? 0.0005 : -0.0005;
        }
        return d;
    }

    /** Platz A als Preisreihe aus der Log-Differenz gegen einen flachen Platz B. */
    private static double[] pricesFromDifferences(double[] differences) {
        double[] a = new double[differences.length];
        for (int i = 0; i < differences.length; i++) {
            a[i] = 100.0 * Math.exp(differences[i]);
        }
        return a;
    }

    private static double[] flatPrices(int length) {
        double[] b = new double[length];
        Arrays.fill(b, 100.0);
        return b;
    }

    @Test
    void persistentPositiveGapIsDissonanceBlamingVenueA() {
        double[] d = baseDifferences(50);
        for (int i = 45; i < 50; i++) {
            d[i] = 0.003;
        }
        Optional<SignalReading> reading = CrossVenueDissonance.measure(
                pricesFromDifferences(d), flatPrices(50), VENUE_A, VENUE_B);
        assertTrue(reading.isPresent());
        assertTrue(reading.get().value() >= 2.0, "z-Score muss ueber der Dissonanz-Schwelle liegen");
        assertTrue(reading.get().interpretation().contains("VENUE DISSONANCE"));
        assertTrue(reading.get().interpretation().contains(VENUE_A),
                "positive Differenz muss Platz A benennen");
        assertEquals("cross-venue-dissonance", reading.get().id());
    }

    @Test
    void persistentNegativeGapIsDissonanceBlamingVenueB() {
        double[] d = baseDifferences(50);
        for (int i = 45; i < 50; i++) {
            d[i] = -0.003;
        }
        Optional<SignalReading> reading = CrossVenueDissonance.measure(
                pricesFromDifferences(d), flatPrices(50), VENUE_A, VENUE_B);
        assertTrue(reading.isPresent());
        assertTrue(reading.get().value() <= -2.0, "Richtung muss im Vorzeichen stehen");
        assertTrue(reading.get().interpretation().contains("VENUE DISSONANCE"));
        assertTrue(reading.get().interpretation().contains(VENUE_B),
                "negative Differenz muss Platz B benennen");
    }

    @Test
    void singleSpikeWithoutPersistenceIsStaleQuote() {
        double[] d = baseDifferences(50);
        for (int i = 45; i < 49; i++) {
            d[i] = 0.0;
        }
        d[49] = 0.004;
        Optional<SignalReading> reading = CrossVenueDissonance.measure(
                pricesFromDifferences(d), flatPrices(50), VENUE_A, VENUE_B);
        assertTrue(reading.isPresent());
        assertTrue(reading.get().value() >= 2.0);
        assertTrue(reading.get().interpretation().contains("stale quote"));
        assertFalse(reading.get().interpretation().contains("VENUE DISSONANCE"));
    }

    @Test
    void alignedVenuesAreInHarmony() {
        double[] d = baseDifferences(50);
        Optional<SignalReading> reading = CrossVenueDissonance.measure(
                pricesFromDifferences(d), flatPrices(50), VENUE_A, VENUE_B);
        assertTrue(reading.isPresent());
        assertTrue(Math.abs(reading.get().value()) < 2.0);
        assertTrue(reading.get().interpretation().contains("harmony"));
        assertTrue(reading.get().interpretation().contains(VENUE_A));
        assertTrue(reading.get().interpretation().contains(VENUE_B));
    }

    @Test
    void shortSeriesCarriesCautionSuffix() {
        double[] d = baseDifferences(40);
        Optional<SignalReading> reading = CrossVenueDissonance.measure(
                pricesFromDifferences(d), flatPrices(40), VENUE_A, VENUE_B);
        assertTrue(reading.isPresent());
        assertTrue(reading.get().interpretation().contains("Caution"));
    }

    @Test
    void tooShortOrMismatchedSeriesYieldEmpty() {
        double[] d39 = baseDifferences(39);
        assertTrue(CrossVenueDissonance.measure(
                pricesFromDifferences(d39), flatPrices(39), VENUE_A, VENUE_B).isEmpty());
        assertTrue(CrossVenueDissonance.measure(
                pricesFromDifferences(baseDifferences(50)), flatPrices(49), VENUE_A, VENUE_B).isEmpty());
        assertTrue(CrossVenueDissonance.measure(
                null, flatPrices(50), VENUE_A, VENUE_B).isEmpty());
    }

    @Test
    void nonPositivePricesOrMissingNamesYieldEmpty() {
        double[] a = pricesFromDifferences(baseDifferences(50));
        double[] b = flatPrices(50);
        double[] broken = b.clone();
        broken[10] = 0.0;
        assertTrue(CrossVenueDissonance.measure(a, broken, VENUE_A, VENUE_B).isEmpty());
        assertTrue(CrossVenueDissonance.measure(a, b, null, VENUE_B).isEmpty());
        assertTrue(CrossVenueDissonance.measure(a, b, VENUE_A, " ").isEmpty());
    }

    @Test
    void formattedValueCarriesPersistenceAndDirectionKey() {
        double[] d = baseDifferences(50);
        Optional<SignalReading> reading = CrossVenueDissonance.measure(
                pricesFromDifferences(d), flatPrices(50), VENUE_A, VENUE_B);
        assertTrue(reading.isPresent());
        assertTrue(reading.get().formattedValue().contains("persistence"));
        assertTrue(reading.get().formattedValue().contains(VENUE_A));
    }
}
