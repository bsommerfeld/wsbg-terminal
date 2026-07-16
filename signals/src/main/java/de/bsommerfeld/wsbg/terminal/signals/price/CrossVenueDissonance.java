package de.bsommerfeld.wsbg.terminal.signals.price;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Arrays;
import java.util.Optional;

/**
 * Venue dissonance: the same stock compared across two trading venues.
 *
 * <p><b>Method:</b> From the mid prices of both venues the log price
 * differential d = ln(A) - ln(B) is formed. The latest value is z-scored
 * against the earlier values of the differential series
 * ({@link MathKit#zScore}); on top comes a persistence ratio: the share of
 * the last 5 values whose distance from the historical mean exceeds one
 * standard deviation. The theory anchor is the Law of One Price: identical
 * instruments may only differ within fee and latency noise - a persistent
 * differential shows which side the pressure comes from (retail venue vs
 * institutionally driven flow).
 *
 * <p><b>Terminal inputs:</b> the mid prices (bid/ask mid) of the same
 * instrument from two trading venues; the venue names come in as runtime
 * parameters.
 */
public final class CrossVenueDissonance {

    /** Below this series length no differential z-score is reliable. */
    private static final int MIN_SERIES = 40;
    /** Below this series length the interpretation carries a caution suffix. */
    private static final int COMFORTABLE_SERIES = 80;
    /** At or above this absolute z-score the differential counts as notable. */
    private static final double Z_DISSONANT = 2.0;
    /** At or above this persistence ratio the differential counts as sustained. */
    private static final double PERSISTENCE_THRESHOLD = 0.6;
    /** Window for the persistence ratio. */
    private static final int PERSISTENCE_WINDOW = 5;

    private CrossVenueDissonance() {
    }

    /**
     * Measures the current price differential of the same instrument between two venues.
     *
     * @param midsVenueA mid prices at venue A, all &gt; 0
     * @param midsVenueB mid prices at venue B, same length, all &gt; 0
     * @param venueAName display name of venue A
     * @param venueBName display name of venue B
     * @return reading, or empty on mismatched or too-short series
     *         (below {@value #MIN_SERIES}), non-positive prices, or missing names
     */
    public static Optional<SignalReading> measure(double[] midsVenueA, double[] midsVenueB,
            String venueAName, String venueBName) {
        if (midsVenueA == null || midsVenueB == null
                || midsVenueA.length != midsVenueB.length
                || midsVenueA.length < MIN_SERIES
                || venueAName == null || venueAName.isBlank()
                || venueBName == null || venueBName.isBlank()) {
            return Optional.empty();
        }
        int n = midsVenueA.length;
        double[] difference = new double[n];
        for (int i = 0; i < n; i++) {
            if (!(midsVenueA[i] > 0) || !(midsVenueB[i] > 0)
                    || !Double.isFinite(midsVenueA[i]) || !Double.isFinite(midsVenueB[i])) {
                return Optional.empty();
            }
            difference[i] = Math.log(midsVenueA[i]) - Math.log(midsVenueB[i]);
        }

        double[] history = Arrays.copyOfRange(difference, 0, n - 1);
        double z = MathKit.zScore(difference[n - 1], history);

        double historyMean = MathKit.mean(history);
        double historyStd = MathKit.std(history);
        double persistence = 0;
        if (historyStd > 0) {
            int outliers = 0;
            for (int i = n - PERSISTENCE_WINDOW; i < n; i++) {
                if (Math.abs(difference[i] - historyMean) > historyStd) {
                    outliers++;
                }
            }
            persistence = (double) outliers / PERSISTENCE_WINDOW;
        }

        String interpretation;
        if (Math.abs(z) >= Z_DISSONANT && persistence >= PERSISTENCE_THRESHOLD) {
            String payingVenue = z > 0 ? venueAName : venueBName;
            interpretation = "VENUE DISSONANCE: the pressure comes one-sidedly from " + payingVenue
                    + " - this venue keeps paying more for the same instrument. "
                    + "Fold the flow origin into the situation read.";
        } else if (Math.abs(z) >= Z_DISSONANT) {
            interpretation = "Short-lived outlier between " + venueAName + " and " + venueBName
                    + " without persistence - likely a stale quote, no reliable signal.";
        } else {
            interpretation = "Venues in harmony: " + venueAName + " and " + venueBName
                    + " price the same instrument alike within fee noise.";
        }
        if (n < COMFORTABLE_SERIES) {
            interpretation += " Caution: only " + n
                    + " price pairs as comparison base - z-score and persistence are accordingly uncertain.";
        }

        return Optional.of(new SignalReading(
                "cross-venue-dissonance",
                "Venue dissonance (price differential)",
                z,
                MathKit.fmt(z, 2) + " (z-score of the log price differential; persistence "
                        + MathKit.fmt(persistence, 2) + ", positive = " + venueAName + " pays more)",
                "Measures how far the current log price differential of the same instrument "
                        + "between two trading venues has run out of its own historical band and "
                        + "how sustained the deviation is.",
                interpretation));
    }
}
