package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.yahoofinance.Bar;

import java.util.List;
import java.util.Optional;

/**
 * The market memory's structure arithmetic — a volume-at-price profile from
 * intraday bars, PURE and deterministic. This is methodically what the
 * established charting tools themselves do (TradingView's own documentation:
 * a bar-range APPROXIMATION — each bar's volume spread evenly across its
 * high–low span onto price buckets — not tick data), so the house profile is
 * on par with what the audience sees elsewhere:
 *
 * <ul>
 *   <li><b>POC</b> — the bucket with the most volume (the market's accepted
 *       price);</li>
 *   <li><b>Value Area 70 %</b> — grown from the POC by always taking the
 *       stronger neighbouring bucket until 70 % of total volume is covered
 *       → VAH/VAL, the standard definition;</li>
 *   <li>high-volume nodes read as support/resistance ZONES (price sticks),
 *       low-volume nodes as slip-through zones.</li>
 * </ul>
 *
 * <p>Every level this produces carries its own justification — the volume
 * that traded there — which is exactly the licence a report sentence needs
 * ("Support bei X, dort Y Stück umgegangen") instead of an unexplained line.
 */
final class VolumeProfile {

    /** Standard bucket count — TradingView's row-count class. */
    static final int BUCKETS = 50;
    /** The standard value-area share. */
    static final double VALUE_AREA_SHARE = 0.70;

    private VolumeProfile() {
    }

    /**
     * One finished profile.
     *
     * @param poc         point of control (bucket midpoint), the volume-heaviest price
     * @param vah         value area high
     * @param val         value area low
     * @param pocUnits    volume in the POC bucket
     * @param totalUnits  volume across the whole profile
     * @param bucketWidth width of one price bucket
     * @param low         profile floor (lowest priced bucket edge)
     * @param high        profile ceiling
     */
    record Profile(double poc, double vah, double val, long pocUnits, long totalUnits,
            double bucketWidth, double low, double high) {
    }

    /**
     * Builds the profile from bars (any granularity; hourly over 3–6 months is
     * the intended input). A bar's volume is spread evenly across its high–low
     * span; bars without a usable range put their whole volume at the close.
     * Empty when the bars carry no volume at all (an index, an L&amp;S-only
     * name) or no usable price range — a wrong profile is worse than none.
     */
    static Optional<Profile> build(List<Bar> bars) {
        if (bars == null || bars.isEmpty()) return Optional.empty();

        double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
        long total = 0;
        for (Bar b : bars) {
            double lo = Double.isFinite(b.low()) ? b.low() : b.close();
            double hi = Double.isFinite(b.high()) ? b.high() : b.close();
            if (lo > 0 && lo < min) min = lo;
            if (hi > 0 && hi > max) max = hi;
            total += Math.max(0, b.volume());
        }
        if (total <= 0 || !(max > min)) return Optional.empty();

        double width = (max - min) / BUCKETS;
        double[] volume = new double[BUCKETS];
        for (Bar b : bars) {
            long v = Math.max(0, b.volume());
            if (v == 0) continue;
            double lo = Double.isFinite(b.low()) ? b.low() : b.close();
            double hi = Double.isFinite(b.high()) ? b.high() : b.close();
            int from = bucketOf(lo, min, width);
            int to = bucketOf(hi, min, width);
            if (to < from) {
                int t = from;
                from = to;
                to = t;
            }
            double share = (double) v / (to - from + 1);
            for (int i = from; i <= to; i++) volume[i] += share;
        }

        int poc = 0;
        for (int i = 1; i < BUCKETS; i++) {
            if (volume[i] > volume[poc]) poc = i;
        }

        // Grow the value area from the POC: always absorb the stronger neighbour.
        double covered = volume[poc];
        int lo = poc, hi = poc;
        double target = VALUE_AREA_SHARE * sum(volume);
        while (covered < target && (lo > 0 || hi < BUCKETS - 1)) {
            double below = lo > 0 ? volume[lo - 1] : -1;
            double above = hi < BUCKETS - 1 ? volume[hi + 1] : -1;
            if (above > below) {
                hi++;
                covered += above;
            } else {
                lo--;
                covered += below;
            }
        }

        return Optional.of(new Profile(
                mid(poc, min, width),
                min + (hi + 1) * width,
                min + lo * width,
                Math.round(volume[poc]),
                total, width, min, max));
    }

    private static int bucketOf(double price, double min, double width) {
        int i = (int) ((price - min) / width);
        return Math.max(0, Math.min(BUCKETS - 1, i));
    }

    private static double mid(int bucket, double min, double width) {
        return min + (bucket + 0.5) * width;
    }

    private static double sum(double[] a) {
        double s = 0;
        for (double v : a) s += v;
        return s;
    }
}
