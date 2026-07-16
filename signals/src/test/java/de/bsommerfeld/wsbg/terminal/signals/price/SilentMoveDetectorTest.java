package de.bsommerfeld.wsbg.terminal.signals.price;

import de.bsommerfeld.wsbg.terminal.signals.SignalReading;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SilentMoveDetectorTest {

    /**
     * Deterministische Historie: Markt alterniert +1/-1, Asset = 1.2 mal Markt
     * plus ein Residuen-Zyklus, der orthogonal zum Markt liegt (OLS-Beta exakt 1.2).
     */
    private static double[][] historyPair(int length) {
        double[] market = new double[length];
        double[] asset = new double[length];
        double[] residualCycle = {0.1, -0.1, -0.1, 0.1};
        for (int i = 0; i < length; i++) {
            market[i] = (i % 2 == 0) ? 1.0 : -1.0;
            asset[i] = 1.2 * market[i] + residualCycle[i % 4];
        }
        return new double[][]{asset, market};
    }

    @Test
    void unexplainedAbnormalReturnWithoutHeadlineIsSilentMove() {
        double[][] pair = historyPair(40);
        // Markt +0.5 erklaert 0.6; das Asset macht 1.6 -> abnormale Rendite +1.0
        Optional<SignalReading> reading =
                SilentMoveDetector.measure(pair[0], pair[1], 1.6, 0.5, false);
        assertTrue(reading.isPresent());
        assertTrue(reading.get().value() >= 2.5, "z-Score muss ueber der Abnormal-Schwelle liegen");
        assertTrue(reading.get().interpretation().contains("SILENT MOVE"));
        assertEquals("silent-move-detector", reading.get().id());
    }

    @Test
    void negativeSilentMoveKeepsDirectionInValue() {
        double[][] pair = historyPair(40);
        // Markt erklaert +0.6; das Asset faellt auf -0.4 -> abnormale Rendite -1.0
        Optional<SignalReading> reading =
                SilentMoveDetector.measure(pair[0], pair[1], -0.4, 0.5, false);
        assertTrue(reading.isPresent());
        assertTrue(reading.get().value() <= -2.5, "Richtung des Moves muss im Vorzeichen stehen");
        assertTrue(reading.get().interpretation().contains("SILENT MOVE"));
    }

    @Test
    void abnormalReturnWithHeadlineIsAttributed() {
        double[][] pair = historyPair(40);
        Optional<SignalReading> reading =
                SilentMoveDetector.measure(pair[0], pair[1], 1.6, 0.5, true);
        assertTrue(reading.isPresent());
        assertTrue(reading.get().value() >= 2.5);
        assertTrue(reading.get().interpretation().contains("attributed"));
        assertFalse(reading.get().interpretation().contains("SILENT MOVE"));
    }

    @Test
    void moveExplainedByMarketIsWithinNoise() {
        double[][] pair = historyPair(40);
        // 0.65 bei Markt +0.5: abnormale Rendite nur +0.05
        Optional<SignalReading> reading =
                SilentMoveDetector.measure(pair[0], pair[1], 0.65, 0.5, false);
        assertTrue(reading.isPresent());
        assertTrue(Math.abs(reading.get().value()) < 2.5);
        assertTrue(reading.get().interpretation().contains("Within range"));
    }

    @Test
    void interpretationDisclosesBetaAndAbnormalReturn() {
        double[][] pair = historyPair(40);
        Optional<SignalReading> reading =
                SilentMoveDetector.measure(pair[0], pair[1], 1.6, 0.5, false);
        assertTrue(reading.isPresent());
        assertTrue(reading.get().interpretation().contains("beta 1.20"));
        assertTrue(reading.get().interpretation().contains("abnormal return 1.00 %"));
    }

    @Test
    void thinHistoryCarriesCautionSuffix() {
        double[][] pair = historyPair(30);
        Optional<SignalReading> reading =
                SilentMoveDetector.measure(pair[0], pair[1], 0.65, 0.5, false);
        assertTrue(reading.isPresent());
        assertTrue(reading.get().interpretation().contains("Caution"));
    }

    @Test
    void tooShortOrMismatchedSeriesYieldEmpty() {
        double[][] pair = historyPair(29);
        assertTrue(SilentMoveDetector.measure(pair[0], pair[1], 1.0, 0.5, false).isEmpty());

        double[][] longer = historyPair(40);
        assertTrue(SilentMoveDetector.measure(
                Arrays.copyOfRange(longer[0], 0, 39), longer[1], 1.0, 0.5, false).isEmpty());
        assertTrue(SilentMoveDetector.measure(null, longer[1], 1.0, 0.5, false).isEmpty());
        assertTrue(SilentMoveDetector.measure(longer[0], null, 1.0, 0.5, false).isEmpty());
    }

    @Test
    void degenerateMarketSeriesYieldsEmpty() {
        double[] flatMarket = new double[40];
        double[] asset = historyPair(40)[0];
        assertTrue(SilentMoveDetector.measure(asset, flatMarket, 1.0, 0.5, false).isEmpty());
    }
}
