package de.bsommerfeld.wsbg.terminal.signals;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignalCoreTest {

    @Test
    void contextLineCarriesDefinitionAndInterpretation() {
        SignalReading r = new SignalReading(
                "test-signal", "Test-Signal", 0.42, "0.42 (Skala 0-1)",
                "misst etwas Bestimmtes", "so ist der Wert zu lesen");
        String line = r.toContextLine();
        assertTrue(line.contains("Test-Signal"));
        assertTrue(line.contains("0.42 (Skala 0-1)"));
        assertTrue(line.contains("misst etwas Bestimmtes"));
        assertTrue(line.contains("so ist der Wert zu lesen"));
    }

    @Test
    void boardRendersHeaderAndOneLinePerReading() {
        SignalReading a = new SignalReading("a", "A", 1, "1", "d", "i");
        SignalReading b = new SignalReading("b", "B", 2, "2", "d", "i");
        String block = SignalBoard.render(List.of(a, b));
        assertTrue(block.startsWith("QUANT SIGNALS"));
        assertEquals(3, block.split("\n").length);
    }

    @Test
    void boardIsEmptyForNoReadings() {
        assertEquals("", SignalBoard.render(List.of()));
        assertEquals("", SignalBoard.render(null));
    }

    @Test
    void entropyIsZeroForSinglePointAndOneForUniform() {
        assertEquals(0, MathKit.normalizedEntropy(new double[]{5}), 1e-9);
        assertEquals(1, MathKit.normalizedEntropy(new double[]{3, 3, 3, 3}), 1e-9);
        double skewed = MathKit.normalizedEntropy(new double[]{100, 1, 1, 1});
        assertTrue(skewed > 0 && skewed < 0.5);
    }

    @Test
    void zScoreAndPercentileBehave() {
        double[] hist = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        assertTrue(MathKit.zScore(20, hist) > 3);
        assertEquals(0.5, MathKit.empiricalPercentile(5.5, hist), 1e-9);
        assertEquals(1.0, MathKit.empiricalPercentile(99, hist), 1e-9);
    }

    @Test
    void wilsonAndJeffreysBracketTheRate() {
        double[] w = MathKit.wilsonInterval(68, 100, 1.645);
        assertTrue(w[0] < 0.68 && 0.68 < w[1]);
        double[] j = MathKit.jeffreysInterval(68, 100, 0.90);
        assertTrue(j[0] < 0.68 && 0.68 < j[1]);
        // kleines n ergibt ein breiteres Intervall
        double[] jSmall = MathKit.jeffreysInterval(5, 7, 0.90);
        assertTrue(jSmall[1] - jSmall[0] > j[1] - j[0]);
    }

    @Test
    void incompleteBetaMatchesKnownValues() {
        // I_x(1,1) = x (Gleichverteilung)
        assertEquals(0.3, MathKit.regularizedIncompleteBeta(0.3, 1, 1), 1e-9);
        // Symmetrie: I_x(a,b) = 1 - I_{1-x}(b,a)
        double v = MathKit.regularizedIncompleteBeta(0.4, 3, 5);
        assertEquals(1 - MathKit.regularizedIncompleteBeta(0.6, 5, 3), v, 1e-9);
        // Median der Beta(2,2) ist 0.5
        assertEquals(0.5, MathKit.betaQuantile(0.5, 2, 2), 1e-6);
    }

    @Test
    void crossCorrelationFindsTheLag() {
        double[] cause = new double[60];
        double[] effect = new double[60];
        for (int i = 0; i < 60; i++) {
            cause[i] = Math.sin(i * 0.4);
        }
        for (int i = 0; i < 60; i++) {
            effect[i] = i >= 3 ? cause[i - 3] : 0;
        }
        double atLag3 = MathKit.crossCorrelationAtLag(cause, effect, 3);
        double atLag0 = MathKit.crossCorrelationAtLag(cause, effect, 0);
        assertTrue(atLag3 > 0.9);
        assertTrue(atLag3 > atLag0);
    }
}
