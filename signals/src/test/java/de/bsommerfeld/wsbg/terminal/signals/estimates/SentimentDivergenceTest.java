package de.bsommerfeld.wsbg.terminal.signals.estimates;

import de.bsommerfeld.wsbg.terminal.signals.SignalReading;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SentimentDivergenceTest {

    private static double[] constant(int length, double value) {
        double[] series = new double[length];
        Arrays.fill(series, value);
        return series;
    }

    @Test
    void cageGreedyWallStreetFearful() {
        double[] cage = constant(20, 50);
        cage[19] = 80;
        Optional<SignalReading> reading = SentimentDivergence.measure(cage, constant(20, 50));
        assertTrue(reading.isPresent());
        assertTrue(reading.get().value() >= 1.5);
        assertTrue(reading.get().interpretation().contains("KÄFIG GIERIG, WALL STREET ÄNGSTLICH"));
    }

    @Test
    void cageFearfulWallStreetGreedy() {
        double[] cage = constant(20, 50);
        cage[19] = 20;
        Optional<SignalReading> reading = SentimentDivergence.measure(cage, constant(20, 50));
        assertTrue(reading.isPresent());
        assertTrue(reading.get().value() <= -1.5);
        assertTrue(reading.get().interpretation().contains("KÄFIG ÄNGSTLICH, WALL STREET GIERIG"));
    }

    @Test
    void identicalSeriesAreInSync() {
        Optional<SignalReading> reading = SentimentDivergence.measure(
                constant(25, 50), constant(25, 50));
        assertTrue(reading.isPresent());
        assertEquals(0.0, reading.get().value(), 1e-12);
        assertTrue(reading.get().interpretation().contains("im Einklang"));
    }

    @Test
    void persistentRecentShiftTriggersCusum() {
        double[] cage = new double[30];
        for (int i = 0; i < 20; i++) {
            cage[i] = 50 + (i % 2 == 0 ? 1 : -1);
        }
        for (int i = 20; i < 30; i++) {
            cage[i] = 55;
        }
        Optional<SignalReading> reading = SentimentDivergence.measure(cage, constant(30, 50));
        assertTrue(reading.isPresent());
        assertTrue(reading.get().interpretation()
                .contains("frischer Umschwung der Divergenz seit kurzem"));
    }

    @Test
    void shortHistoryCarriesCaution() {
        Optional<SignalReading> reading = SentimentDivergence.measure(
                constant(25, 50), constant(25, 50));
        assertTrue(reading.isPresent());
        assertTrue(reading.get().interpretation().contains("Vorsicht"));
    }

    @Test
    void emptyOnTooShortOrMismatchedSeries() {
        assertTrue(SentimentDivergence.measure(constant(19, 50), constant(19, 50)).isEmpty());
        assertTrue(SentimentDivergence.measure(constant(25, 50), constant(24, 50)).isEmpty());
        assertTrue(SentimentDivergence.measure(null, constant(25, 50)).isEmpty());
    }
}
