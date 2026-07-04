package de.bsommerfeld.updater.launcher;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Remaining-time estimator for a determinate progress phase. Fits the observed
 * velocity as the linear-regression slope of the most recent
 * {@link #ETA_WINDOW_MS} of {@code (time, ratio)} samples, extrapolates it to
 * ratio 1.0, and exponentially smooths the result so the readout counts down
 * steadily instead of jumping with every re-extrapolation.
 *
 * <p>Pure computation, no Swing dependency — extracted from {@code
 * LauncherWindow} so the regression + smoothing math is unit-testable in
 * isolation. Not thread-safe: {@code LauncherWindow} feeds it EDT-only.
 */
final class EtaEstimator {

    /**
     * Sentinel returned by {@link #sample} meaning "leave the current readout
     * untouched" — distinct from {@code -1} ("hide"). It lets a stalled phase
     * with a running estimate keep counting down on its own (the coarse
     * 1%-granularity of ollama's output makes flat stretches normal), while a
     * genuine hide still clears the group.
     */
    static final long NO_CHANGE = Long.MIN_VALUE;

    // Short warm-up before the ETA is trusted — just enough samples for a
    // stable velocity fit, not a "hide until late" gate.
    private static final long ETA_SHOW_AFTER_MS = 4_000;
    // Sliding window the velocity is regressed over. Wide enough to smooth the
    // 1%-granularity of ollama's model-pull output, short enough to track real
    // rate changes (a download throttling) within a few seconds.
    private static final long ETA_WINDOW_MS = 10_000;
    // Time constant of the exponential smoothing on the ETA estimate.
    private static final long ETA_SMOOTH_TAU_MS = 4_000;

    // Progress-ratio samples for the velocity fit.
    private final Deque<long[]> etaSamples = new ArrayDeque<>(); // [time, ratioBits]
    private long etaPhaseStart;
    private double etaLastRatio = -1;
    // Smoothed remaining-time estimate (ms), -1 while none.
    private double etaSmoothedRemainingMs = -1;
    private long etaSmoothedAt;

    /** Discards the current velocity fit so the next phase starts fresh. */
    void reset() {
        etaSamples.clear();
        etaLastRatio = -1;
        etaSmoothedRemainingMs = -1;
    }

    /**
     * Feeds a progress ratio into the estimator using {@link
     * System#currentTimeMillis()} as the clock and returns the remaining
     * seconds to display: a value {@code >= 0} to show, {@code -1} to hide the
     * ETA group, or {@link #NO_CHANGE} to leave the readout as-is.
     */
    long sample(double ratio) {
        return sample(ratio, System.currentTimeMillis());
    }

    /** Clock-injected variant of {@link #sample(double)} for testing. */
    long sample(double ratio, long now) {
        // Indeterminate or finished — nothing meaningful to extrapolate.
        if (ratio < 0 || ratio >= 1.0) {
            etaSamples.clear();
            etaLastRatio = -1;
            etaSmoothedRemainingMs = -1;
            return -1;
        }

        // Start of a phase, or a backwards jump (a new sub-step reusing the bar)
        // restarts the fit, the smoothing, and the warm-up clock.
        if (etaSamples.isEmpty() || ratio + 0.0005 < etaLastRatio) {
            etaSamples.clear();
            etaPhaseStart = now;
            etaSmoothedRemainingMs = -1;
        }
        etaLastRatio = ratio;
        etaSamples.addLast(new long[]{now, Double.doubleToRawLongBits(ratio)});

        // Drop samples outside the sliding window, always keeping a minimum
        // spread to regress over.
        while (etaSamples.size() > 2 && now - etaSamples.peekFirst()[0] > ETA_WINDOW_MS) {
            etaSamples.removeFirst();
        }

        if (now - etaPhaseStart < ETA_SHOW_AFTER_MS) {
            return -1;
        }

        double slope = ratioSlopePerMs(); // ratio units per millisecond
        if (slope <= 1e-9) {
            // Stalled or not yet moving. With a running estimate the readout
            // keeps counting down on its own; without one there is nothing
            // worth guessing yet.
            return etaSmoothedRemainingMs < 0 ? -1 : NO_CHANGE;
        }

        double estimateMs = (1.0 - ratio) / slope;
        if (etaSmoothedRemainingMs < 0) {
            etaSmoothedRemainingMs = estimateMs;
        } else {
            // Let the previous value tick down with the elapsed wall time, then
            // pull it a time-proportional step toward the fresh estimate — the
            // readout counts down steadily and only drifts toward the regression
            // instead of jumping. Capped at half the gap per update.
            double elapsed = now - etaSmoothedAt;
            double predicted = Math.max(0, etaSmoothedRemainingMs - elapsed);
            double alpha = Math.min(0.5, elapsed / ETA_SMOOTH_TAU_MS);
            etaSmoothedRemainingMs = predicted + alpha * (estimateMs - predicted);
        }
        etaSmoothedAt = now;
        return Math.round(etaSmoothedRemainingMs / 1000.0);
    }

    /**
     * Least-squares slope of ratio over time across the current sample window.
     * Time is taken relative to the first sample to keep the sums well-scaled.
     *
     * @return ratio increase per millisecond, or 0 if it can't be determined
     */
    private double ratioSlopePerMs() {
        int n = etaSamples.size();
        if (n < 2) return 0;

        long t0 = etaSamples.peekFirst()[0];
        double sx = 0, sy = 0, sxx = 0, sxy = 0;
        for (long[] s : etaSamples) {
            double x = s[0] - t0;
            double y = Double.longBitsToDouble(s[1]);
            sx += x;
            sy += y;
            sxx += x * x;
            sxy += x * y;
        }
        double denom = n * sxx - sx * sx;
        if (denom == 0) return 0;
        return (n * sxy - sx * sy) / denom;
    }
}
