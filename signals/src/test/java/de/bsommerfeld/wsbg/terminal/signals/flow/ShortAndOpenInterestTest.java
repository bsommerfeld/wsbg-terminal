package de.bsommerfeld.wsbg.terminal.signals.flow;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The selling side and the standing book - the two axes that need a record. */
class ShortAndOpenInterestTest {

    // ---- the short side ----

    private static double[] shares(int n, double level) {
        double[] out = new double[n];
        for (int i = 0; i < n; i++) out[i] = level + (i % 5) * 0.004 - 0.008;
        return out;
    }

    @Test
    void aShortShareInsideItsOwnRangeIsNotReportedAsBearish() {
        var r = ShortSideCrowding.measure(shares(40, 0.62)).orElseThrow();

        assertTrue(Math.abs(r.value()) < 1.5, "z was " + r.value());
        assertTrue(r.interpretation().contains("inside this name's own normal range"));
        assertTrue(r.interpretation().contains("NEVER read the LEVEL as bearishness"),
                "the caveat is the point of this signal: " + r.interpretation());
    }

    @Test
    void aHighButNormalLevelStaysQuiet() {
        // 80 % short volume every day - alarming to a reader, meaningless here.
        var r = ShortSideCrowding.measure(shares(40, 0.80)).orElseThrow();

        assertTrue(Math.abs(r.value()) < 1.5,
                "a constant level carries no deviation, z was " + r.value());
    }

    @Test
    void aRisingShortShareReadsAsCrowdingIn() {
        double[] s = shares(40, 0.40);
        for (int i = s.length - 3; i < s.length; i++) s[i] = 0.62;

        var r = ShortSideCrowding.measure(s).orElseThrow();
        assertTrue(r.value() >= 1.5, "z was " + r.value());
        assertTrue(r.interpretation().startsWith("CROWDING IN"));
    }

    @Test
    void aFallingShortShareReadsAsSteppingAway() {
        double[] s = shares(40, 0.60);
        for (int i = s.length - 3; i < s.length; i++) s[i] = 0.38;

        var r = ShortSideCrowding.measure(s).orElseThrow();
        assertTrue(r.value() <= -1.5, "z was " + r.value());
        assertTrue(r.interpretation().startsWith("STEPPING AWAY"));
    }

    @Test
    void theShortAxisGuardsItsInput() {
        assertTrue(ShortSideCrowding.measure(null).isEmpty());
        assertTrue(ShortSideCrowding.measure(new double[0]).isEmpty());
        assertTrue(ShortSideCrowding.measure(shares(20, 0.5)).isEmpty(), "too short");
        double[] broken = shares(40, 0.5);
        broken[7] = 1.4; // a share above 1 is not a share
        assertTrue(ShortSideCrowding.measure(broken).isEmpty());
        double[] nan = shares(40, 0.5);
        nan[3] = Double.NaN;
        assertTrue(ShortSideCrowding.measure(nan).isEmpty());
    }

    // ---- the standing book ----

    @Test
    void aGrowingBookReadsAsMoneyEntering() {
        long[] oi = {1_000_000, 1_020_000, 1_080_000, 1_150_000, 1_210_000, 1_260_000};

        var r = OpenInterestDrift.measure(oi).orElseThrow();
        assertTrue(r.value() > 0.05, "change was " + r.value());
        assertTrue(r.interpretation().contains("GROWING"));
        assertTrue(r.interpretation().contains("ONLY figure here that distinguishes"));
    }

    @Test
    void aShrinkingBookReadsAsPositionsBeingUnwound() {
        long[] oi = {1_400_000, 1_380_000, 1_300_000, 1_180_000, 1_090_000, 1_020_000};

        var r = OpenInterestDrift.measure(oi).orElseThrow();
        assertTrue(r.value() < -0.05, "change was " + r.value());
        assertTrue(r.interpretation().contains("SHRINKING"));
        assertTrue(r.interpretation().contains("usually turns before the price"));
    }

    @Test
    void aFlatBookSaysSo() {
        long[] oi = {1_000_000, 1_005_000, 998_000, 1_002_000, 1_001_000, 1_000_500};

        var r = OpenInterestDrift.measure(oi).orElseThrow();
        assertTrue(r.interpretation().contains("flat"));
    }

    @Test
    void aColdArchiveMeasuresNothingRatherThanGuessing() {
        assertTrue(OpenInterestDrift.measure(null).isEmpty());
        assertTrue(OpenInterestDrift.measure(new long[]{1_000, 1_100}).isEmpty());
        assertTrue(OpenInterestDrift.cold(0));
        assertTrue(OpenInterestDrift.cold(OpenInterestDrift.requiredSessions() - 1));
        assertFalse(OpenInterestDrift.cold(OpenInterestDrift.requiredSessions()));
    }

    @Test
    void aZeroSessionInTheArchiveDisqualifiesTheSeries() {
        long[] oi = new long[6];
        Arrays.fill(oi, 1_000_000);
        oi[0] = 0;

        assertTrue(OpenInterestDrift.measure(oi).isEmpty(),
                "a session without a book cannot anchor a change");
    }
}
