package de.bsommerfeld.wsbg.terminal.signals.world;

import de.bsommerfeld.wsbg.terminal.signals.SignalReading;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldAnomalyIndexTest {

    private static final String[] NAMES_2D = {"pegelA", "pegelB"};

    /**
     * 40 Tage, 2 Pegel, deterministische Muster: pegelA alterniert um 10,
     * pegelB laeuft mit Periode 4 um 5 - unkorreliert, Varianz je ~1.
     */
    private static double[][] history2d() {
        int[] cycleB = {1, 1, -1, -1};
        double[][] history = new double[40][2];
        for (int i = 0; i < 40; i++) {
            history[i][0] = 10 + (i % 2 == 0 ? 1 : -1);
            history[i][1] = 5 + cycleB[i % 4];
        }
        return history;
    }

    @Test
    void normalBandWhenCurrentSitsOnTheMean() {
        SignalReading reading = WorldAnomalyIndex
                .measure(history2d(), new double[]{10, 5}, NAMES_2D)
                .orElseThrow();
        assertTrue(reading.value() < 1.5);
        assertTrue(reading.interpretation().contains("ruhig"));
        // 40 Tage sind unter der Komfort-Schwelle -> Vorsichts-Zusatz
        assertTrue(reading.interpretation().contains("Vorsicht"));
        assertEquals("world-anomaly-index", reading.id());
    }

    @Test
    void elevatedBandOnClearButNotExtremeDeviation() {
        SignalReading reading = WorldAnomalyIndex
                .measure(history2d(), new double[]{12.2, 7.2}, NAMES_2D)
                .orElseThrow();
        assertTrue(reading.value() >= 1.5 && reading.value() < 3.0);
        assertTrue(reading.interpretation().contains("Weltspannung"));
    }

    @Test
    void extremeBandOnMassiveDeviation() {
        SignalReading reading = WorldAnomalyIndex
                .measure(history2d(), new double[]{16, 11}, NAMES_2D)
                .orElseThrow();
        assertTrue(reading.value() >= 3.0);
        assertTrue(reading.interpretation().contains("AUSNAHMEZUSTAND"));
    }

    @Test
    void namesTheDrivingDimension() {
        // 36 Tage, 3 Pegel; nur pegelC weicht heute ab
        int[] cycleB = {1, 1, -1, -1};
        int[] cycleC = {1, 0, -1};
        double[][] history = new double[36][3];
        for (int i = 0; i < 36; i++) {
            history[i][0] = 10 + (i % 2 == 0 ? 1 : -1);
            history[i][1] = 5 + cycleB[i % 4];
            history[i][2] = 7 + cycleC[i % 3];
        }
        SignalReading reading = WorldAnomalyIndex
                .measure(history, new double[]{10, 5, 11}, new String[]{"pegelA", "pegelB", "pegelC"})
                .orElseThrow();
        assertTrue(reading.value() >= 1.5);
        assertTrue(reading.interpretation().contains("pegelC"));
        // der Ausreisser-Pegel steht vor den unauffaelligen
        assertTrue(reading.interpretation().indexOf("pegelC") < reading.interpretation().indexOf("pegelA"));
    }

    @Test
    void emptyOnTooFewRows() {
        double[][] history = new double[20][2];
        for (int i = 0; i < 20; i++) {
            history[i] = new double[]{i % 2, i % 3};
        }
        assertEquals(Optional.empty(),
                WorldAnomalyIndex.measure(history, new double[]{0, 0}, NAMES_2D));
    }

    @Test
    void emptyOnDimensionMismatchOrSingleDimension() {
        assertEquals(Optional.empty(),
                WorldAnomalyIndex.measure(history2d(), new double[]{10, 5}, new String[]{"pegelA"}));
        double[][] oneDim = new double[40][1];
        for (int i = 0; i < 40; i++) {
            oneDim[i][0] = i % 2;
        }
        assertEquals(Optional.empty(),
                WorldAnomalyIndex.measure(oneDim, new double[]{0}, new String[]{"pegelA"}));
    }
}
