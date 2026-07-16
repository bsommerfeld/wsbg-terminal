package de.bsommerfeld.wsbg.terminal.signals.wire;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Source latency profile: flags exclusive suspicion when a chronically slow
 * source prints a story first.
 *
 * <p><b>Method:</b> robust location measures instead of means (median statistics
 * in the spirit of Tukey's exploratory data analysis, "Exploratory Data
 * Analysis", 1977). For each source, the median of its historical minutes behind
 * the respective first printer is computed (0 = was first printer itself). The
 * finding is the slowness ratio: median of the current first-printer source
 * divided by the median of all per-source medians. A ratio well above 1 means a
 * source that usually trails is out front this time - the classic pattern of own
 * material/exclusive reporting rather than wire pickup.
 *
 * <p><b>Inputs in the terminal:</b> the newsroom's story clusters carry a source
 * timestamp per member; from these one can derive, per story, the printing order
 * and, per source, the history "minutes behind the first printer". That history
 * is passed here as {@code minutesBehindFirstBySource}, together with the name
 * of the source that printed the current story first.
 */
public final class SourceLatencyProfile {

    /** Stable machine key of this signal. */
    public static final String ID = "source-latency-profile";

    private static final String TITLE = "Source latency (first-printer analysis)";

    private static final int MIN_OBSERVATIONS = 10;
    private static final int COMFORTABLE_OBSERVATIONS = 20;
    private static final int MIN_SOURCES = 2;
    private static final int COMFORTABLE_SOURCES = 3;

    private SourceLatencyProfile() {
    }

    /**
     * @param firstPrinterNow            name of the source that printed the current story first
     * @param minutesBehindFirstBySource per source, the historical minutes behind the first printer
     *                                   (0 = was first printer itself)
     */
    public static Optional<SignalReading> measure(
            String firstPrinterNow, Map<String, double[]> minutesBehindFirstBySource) {
        if (firstPrinterNow == null || minutesBehindFirstBySource == null) {
            return Optional.empty();
        }
        double[] ownHistory = minutesBehindFirstBySource.get(firstPrinterNow);
        if (ownHistory == null || ownHistory.length < MIN_OBSERVATIONS
                || minutesBehindFirstBySource.size() < MIN_SOURCES) {
            return Optional.empty();
        }

        double ownMedian = median(ownHistory);

        List<Double> perSourceMedians = new ArrayList<>();
        for (double[] history : minutesBehindFirstBySource.values()) {
            if (history != null && history.length > 0) {
                perSourceMedians.add(median(history));
            }
        }
        if (perSourceMedians.size() < MIN_SOURCES) {
            return Optional.empty();
        }
        double[] mediansArray = new double[perSourceMedians.size()];
        for (int i = 0; i < mediansArray.length; i++) {
            mediansArray[i] = perSourceMedians.get(i);
        }
        double fieldMedian = median(mediansArray);
        if (fieldMedian <= 0 || !Double.isFinite(fieldMedian)) {
            // Degenerate field (all sources chronically first printers) - no reliable ratio.
            return Optional.empty();
        }

        double value = ownMedian / fieldMedian;

        String ownMedianText = MathKit.fmt(ownMedian, 1);
        String fieldMedianText = MathKit.fmt(fieldMedian, 1);

        String interpretation;
        if (value >= 1.5) {
            interpretation = "EXCLUSIVE SUSPICION: " + firstPrinterNow
                    + " is chronically slow (median " + ownMedianText
                    + " min behind the first printer, field median " + fieldMedianText
                    + " min) yet prints this story first - the source most likely holds own"
                    + " material, the story is worth more than its reach.";
        } else if (value > 0.7) {
            interpretation = "Unremarkable: " + firstPrinterNow + " (median " + ownMedianText
                    + " min) sits within the normal latency field (field median " + fieldMedianText
                    + " min) - ordinary first-printer order with no extra message.";
        } else {
            interpretation = "The usual fastest was first: " + firstPrinterNow
                    + " (median " + ownMedianText + " min) is quicker than the field anyway (field median "
                    + fieldMedianText + " min) - no extra signal from the printing order.";
        }
        if (ownHistory.length < COMFORTABLE_OBSERVATIONS
                || minutesBehindFirstBySource.size() < COMFORTABLE_SOURCES) {
            interpretation += " Caution: only thin data (few observations or sources)"
                    + " - read the finding as a weak hint only.";
        }

        String formattedValue = MathKit.fmt(value, 2)
                + "x (slowness ratio, >1 = chronically slower than the field)";
        String definition = "Ratio of the current first-printer source's latency median"
                + " (minutes behind the fastest printer) to the median of all per-source"
                + " medians - measures whether a usually slow source is out front this time.";

        return Optional.of(new SignalReading(ID, TITLE, value, formattedValue, definition, interpretation));
    }

    private static double median(double[] xs) {
        double[] sorted = xs.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        if (n % 2 == 1) {
            return sorted[n / 2];
        }
        return (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0;
    }
}
