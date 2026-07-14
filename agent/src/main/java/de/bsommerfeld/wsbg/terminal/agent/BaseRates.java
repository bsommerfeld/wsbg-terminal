package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.db.MarketEventRecord;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The market memory's base-rate statistics — PURE and deterministic. Distills
 * a class's enriched events into the numbers a report sentence may carry:
 * N, median, mean, hit rate, range. The model never computes any of this; it
 * reads the finished line ({@link #describe}) off a shelf.
 *
 * <p><b>License gates are code, not prompt requests</b> (user mandate):
 * {@link Stats#licensesMean()} — below {@value #MIN_N_FOR_MEAN} events a mean
 * is noise (event-window vol of several percent, SE falls only with √n), so
 * the sentence carries median + hit rate WITH the thin-sample flag instead;
 * {@link #MIN_N_FOR_CONDITIONED} guards regime-sliced cells (class × regime
 * decomposes the sample multiplicatively — an anecdotal cell must fall back
 * to the unconditioned base rate, and say so).
 *
 * <p>Median AND hit rate travel beside the mean deliberately: event CARs are
 * heavily skewed (one −75 % biotech day drags any mean), the median plus
 * "x % negative" is the honest report figure.
 */
final class BaseRates {

    /** Below this, no mean — median + hit rate with the thin-sample flag only. */
    static final int MIN_N_FOR_MEAN = 30;
    /** A regime-conditioned cell below this falls back to the unconditioned rate. */
    static final int MIN_N_FOR_CONDITIONED = 30;
    /** Below this even the median is anecdote — no statement at all, prior only. */
    static final int MIN_N_FOR_ANY = 5;

    private BaseRates() {
    }

    /** One class's (optionally regime-sliced) distilled reaction statistics. */
    record Stats(String eventClass, String regimeBand, int n, double medianPct,
            double meanPct, double negativeShare, double minPct, double maxPct) {

        boolean licensesMean() {
            return n >= MIN_N_FOR_MEAN;
        }

        boolean licensesAny() {
            return n >= MIN_N_FOR_ANY;
        }
    }

    /**
     * Stats over the CAR(−1,+1) of every enriched event, optionally sliced to
     * one regime band ({@code null} = unconditioned). Confounded events are
     * excluded — they measure a cocktail, not the class. Empty when fewer than
     * {@value #MIN_N_FOR_ANY} usable events exist.
     */
    static Optional<Stats> forClass(String eventClass, List<MarketEventRecord> events,
            String regimeBand) {
        if (eventClass == null || events == null) return Optional.empty();
        List<Double> cars = new ArrayList<>();
        for (MarketEventRecord e : events) {
            if (!eventClass.equals(e.eventClass()) || e.carEvent() == null) continue;
            if (Boolean.TRUE.equals(e.confounded())) continue;
            if (regimeBand != null && !regimeBand.equalsIgnoreCase(e.regimeBand())) continue;
            if (Double.isFinite(e.carEvent())) cars.add(e.carEvent());
        }
        if (cars.size() < MIN_N_FOR_ANY) return Optional.empty();
        cars.sort(Double::compareTo);
        int n = cars.size();
        double median = n % 2 == 1 ? cars.get(n / 2) : (cars.get(n / 2 - 1) + cars.get(n / 2)) / 2.0;
        double sum = 0;
        int negative = 0;
        for (double c : cars) {
            sum += c;
            if (c < 0) negative++;
        }
        return Optional.of(new Stats(eventClass, regimeBand, n, median, sum / n,
                (double) negative / n, cars.get(0), cars.get(n - 1)));
    }

    /**
     * The finished ROOT-locale material line (the examiner's number contract):
     * always N, median, hit rate and range; the mean only when licensed, the
     * thin-sample flag when not.
     */
    static String describe(Stats s) {
        StringBuilder sb = new StringBuilder();
        sb.append("N=").append(s.n());
        if (s.regimeBand() != null) sb.append(" (Regime ").append(s.regimeBand()).append(")");
        sb.append(", CAR(-1,+1) median ").append(pct(s.medianPct()));
        if (s.licensesMean()) sb.append(", mean ").append(pct(s.meanPct()));
        sb.append(", ").append(Math.round(s.negativeShare() * 100)).append(" % negative, range ")
                .append(pct(s.minPct())).append(" to ").append(pct(s.maxPct()));
        if (!s.licensesMean()) sb.append(" [thin sample - median/hit rate only]");
        return sb.toString();
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%+.1f %%", v);
    }
}
