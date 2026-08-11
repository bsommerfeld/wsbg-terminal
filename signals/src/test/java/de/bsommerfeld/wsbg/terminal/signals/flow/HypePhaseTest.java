package de.bsommerfeld.wsbg.terminal.signals.flow;

import de.bsommerfeld.wsbg.terminal.signals.SignalReading;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HypePhaseTest {

    private static final int QUIET_DAYS = 70;
    private static final double CALM_VOLUME = 1_000_000;

    /** A calm tape: flat-ish price, volume at the name's own normal. */
    private static double[][] calmSeries(int days) {
        double[] closes = new double[days];
        double[] volumes = new double[days];
        for (int i = 0; i < days; i++) {
            // A deterministic gentle wobble, so realized vol is not exactly zero.
            closes[i] = 100 + (i % 5) * 0.4 - (i % 3) * 0.3;
            volumes[i] = CALM_VOLUME * (1 + (i % 7) * 0.03);
        }
        return new double[][]{closes, volumes};
    }

    /**
     * Appends a wave of {@code waveDays} sessions to a calm base: volume at
     * {@code peakMultiple} of normal, price rising with it, then optionally
     * {@code afterDays} sessions of decay back towards normal.
     */
    private static double[][] withWave(int waveDays, double peakMultiple, int afterDays,
            double decayTo) {
        double[][] base = calmSeries(QUIET_DAYS);
        int total = QUIET_DAYS + waveDays + afterDays;
        double[] closes = new double[total];
        double[] volumes = new double[total];
        System.arraycopy(base[0], 0, closes, 0, QUIET_DAYS);
        System.arraycopy(base[1], 0, volumes, 0, QUIET_DAYS);

        double price = closes[QUIET_DAYS - 1];
        for (int i = 0; i < waveDays; i++) {
            price *= 1.04;
            closes[QUIET_DAYS + i] = price;
            volumes[QUIET_DAYS + i] = CALM_VOLUME * peakMultiple;
        }
        for (int i = 0; i < afterDays; i++) {
            price *= 0.985;
            closes[QUIET_DAYS + waveDays + i] = price;
            volumes[QUIET_DAYS + waveDays + i] = CALM_VOLUME * decayTo;
        }
        return new double[][]{closes, volumes};
    }

    private static HypePhase.Verdict measure(double[][] series) {
        Optional<HypePhase.Verdict> v = HypePhase.measure(series[0], series[1]);
        assertTrue(v.isPresent(), "expected a verdict for this series");
        return v.get();
    }

    // ---- the four phases ----

    @Test
    void aCalmTapeHasNoWave() {
        HypePhase.Verdict v = measure(calmSeries(90));

        assertEquals(HypePhase.Phase.QUIET, v.phase());
        assertEquals(0, v.waveAgeDays());
        assertFalse(v.running());
        assertFalse(v.waveFound());
        assertEquals(-1, v.peakIndex(), "a name without a wave gives the figure nothing to mark");
    }

    @Test
    void aWaveTwoSessionsOldIsIgniting() {
        HypePhase.Verdict v = measure(withWave(2, 4.0, 0, 1.0));

        assertEquals(HypePhase.Phase.IGNITION, v.phase());
        assertEquals(2, v.waveAgeDays());
        assertTrue(v.running());
        assertTrue(v.rvolNow() > 2.0, "expected clearly elevated volume, was " + v.rvolNow());
    }

    @Test
    void aWaveRunningForAWeekIsInItsMiddle() {
        HypePhase.Verdict v = measure(withWave(7, 4.0, 0, 1.0));

        assertEquals(HypePhase.Phase.MIDDLE, v.phase());
        assertEquals(7, v.waveAgeDays());
        assertTrue(v.running());
    }

    @Test
    void aWaveRunningForWeeksIsLate() {
        HypePhase.Verdict v = measure(withWave(20, 4.0, 0, 1.0));

        assertEquals(HypePhase.Phase.LATE, v.phase());
        assertEquals(20, v.waveAgeDays());
        assertTrue(v.running());
    }

    @Test
    void volumeRecedingFromItsOwnPeakFadesWhileStillElevated() {
        // Still above the participation threshold, but only a third of the peak.
        HypePhase.Verdict v = measure(withWave(8, 6.0, 5, 1.8));

        assertEquals(HypePhase.Phase.FADING, v.phase());
        assertTrue(v.rvolNow() < v.wavePeakRvol() * 0.5,
                "expected volume well below its peak, was " + v.rvolNow()
                        + " against " + v.wavePeakRvol());
        assertTrue(v.peakAgoDays() >= 3);
    }

    @Test
    void aWaveThatEndedRecentlyStillReadsAsFaded() {
        HypePhase.Verdict v = measure(withWave(6, 5.0, 4, 1.0));

        assertEquals(HypePhase.Phase.FADING, v.phase());
        assertFalse(v.running(), "the wave is over, not running");
        assertEquals(0, v.waveAgeDays());
    }

    @Test
    void aWaveLongDeadIsQuietAgainNotFading() {
        HypePhase.Verdict v = measure(withWave(5, 5.0, 25, 1.0));

        assertEquals(HypePhase.Phase.QUIET, v.phase(),
                "a wave outside the fade memory is history, not a fading wave");
    }

    @Test
    void aStrayBusyDayDoesNotResurrectAMonthOldWave() {
        // The real-tape defect (2026-08-07): AAPL, KO and SMCI all read FADING
        // off peaks 27, 28 and 37 sessions old, because a single ordinary busy
        // day kept the fade memory alive. The memory hangs on the PEAK.
        double[][] s = withWave(5, 5.0, 25, 1.0);
        s[1][s[1].length - 3] = CALM_VOLUME * 1.7; // one merely-busy session

        HypePhase.Verdict v = measure(s);
        assertEquals(HypePhase.Phase.QUIET, v.phase());
        assertTrue(v.peakAgoDays() > 20,
                "the peak keeps its true age even without a wave, was " + v.peakAgoDays());
    }

    // ---- the guards ----

    @Test
    void tooShortASeriesYieldsNothing() {
        double[][] s = calmSeries(30);

        assertTrue(HypePhase.measure(s[0], s[1]).isEmpty());
    }

    @Test
    void nullsAndMismatchedSeriesYieldNothing() {
        double[][] s = calmSeries(90);

        assertTrue(HypePhase.measure(null, s[1]).isEmpty());
        assertTrue(HypePhase.measure(s[0], null).isEmpty());
        assertTrue(HypePhase.measure(s[0], new double[]{1, 2, 3}).isEmpty());
    }

    @Test
    void anUntradedSeriesYieldsNothing() {
        double[][] s = calmSeries(90);
        java.util.Arrays.fill(s[1], 0.0);

        assertTrue(HypePhase.measure(s[0], s[1]).isEmpty(),
                "a zero baseline cannot carry a multiple");
    }

    @Test
    void nonFiniteInputYieldsNothing() {
        double[][] s = calmSeries(90);
        s[0][80] = Double.NaN;

        assertTrue(HypePhase.measure(s[0], s[1]).isEmpty());
    }

    @Test
    void theWaveDoesNotLiftItsOwnYardstick() {
        // A 10-session wave at 6x - the baseline must stay at the calm level,
        // otherwise the multiple collapses and the wave hides itself.
        HypePhase.Verdict v = measure(withWave(10, 6.0, 0, 1.0));

        assertTrue(v.baselineVolume() < CALM_VOLUME * 1.3,
                "baseline was pulled up by the wave: " + v.baselineVolume());
        assertTrue(v.wavePeakRvol() > 4.0,
                "expected the wave to show its true size, was " + v.wavePeakRvol());
    }

    @Test
    void aLowFloatNameIsMeasuredAgainstItsOwnNormal() {
        // Same shape, wildly different absolute volume: the verdict must match.
        double[][] small = withWave(7, 4.0, 0, 1.0);
        double[][] large = withWave(7, 4.0, 0, 1.0);
        for (int i = 0; i < large[1].length; i++) large[1][i] *= 850.0;

        assertEquals(measure(small).phase(), measure(large).phase());
        assertEquals(measure(small).waveAgeDays(), measure(large).waveAgeDays());
    }

    @Test
    void oneQuietSessionDoesNotBreakARunningWave() {
        double[][] s = withWave(9, 4.0, 0, 1.0);
        s[1][s[1].length - 2] = CALM_VOLUME; // a single lull mid-wave

        HypePhase.Verdict v = measure(s);
        assertTrue(v.running());
        assertEquals(9, v.waveAgeDays(), "a one-session lull must not restart the count");
    }

    @Test
    void twoQuietSessionsEndTheWave() {
        double[][] s = withWave(9, 4.0, 2, 1.0);

        HypePhase.Verdict v = measure(s);
        assertFalse(v.running());
        assertEquals(HypePhase.Phase.FADING, v.phase());
    }

    // ---- the readings ----

    @Test
    void everyPhaseProducesThreeUsableReadings() {
        HypePhase.Verdict v = measure(withWave(2, 4.0, 0, 1.0));

        SignalReading phase = HypePhase.phaseReading(v).orElseThrow();
        SignalReading thrust = HypePhase.thrustReading(v).orElseThrow();
        SignalReading coupling = HypePhase.couplingReading(v).orElseThrow();

        assertEquals("hype-phase", phase.id());
        assertEquals("hype-volume-thrust", thrust.id());
        assertEquals("hype-price-coupling", coupling.id());
        assertTrue(phase.interpretation().contains("IGNITING"));
        for (SignalReading r : java.util.List.of(phase, thrust, coupling)) {
            assertFalse(r.toContextLine().isBlank());
            assertTrue(r.toContextLine().startsWith("SIGNAL ["));
        }
    }

    @Test
    void readingsTolerateAMissingVerdict() {
        assertTrue(HypePhase.phaseReading(null).isEmpty());
        assertTrue(HypePhase.thrustReading(null).isEmpty());
        assertTrue(HypePhase.couplingReading(null).isEmpty());
    }

    @Test
    void aBoughtWaveAndASoldWaveReadDifferently() {
        double[][] bought = withWave(7, 4.0, 0, 1.0);
        double[][] sold = withWave(7, 4.0, 0, 1.0);
        double price = sold[0][QUIET_DAYS - 1];
        for (int i = QUIET_DAYS; i < sold[0].length; i++) {
            price *= 0.96; // same volume, price falling away
            sold[0][i] = price;
        }

        assertTrue(measure(bought).upDayVolumeShare() > 60);
        assertTrue(measure(sold).upDayVolumeShare() < 40);
        assertTrue(HypePhase.couplingReading(measure(sold)).orElseThrow()
                .interpretation().contains("SOLD"));
    }
}
