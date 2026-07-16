package de.bsommerfeld.wsbg.terminal.signals.archive;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Optional;

/**
 * Direction of information flow between the cage (Reddit) and the wire (press)
 * via transfer entropy.
 *
 * <p><b>Method:</b> Both time series (mention resp. headline counts per time
 * bin) are binarized (activity yes/no) and the lag-1 transfer entropy after
 * Schreiber ("Measuring Information Transfer", PRL 2000) is computed in both
 * directions: TE(X-&gt;Y) = sum p(y_t+1, y_t, x_t) *
 * log[ p(y_t+1 | y_t, x_t) / p(y_t+1 | y_t) ], in nats, with Laplace smoothing
 * (+1) over the eight joint states against empty cells. The signal value is
 * the difference TE(Reddit-&gt;wire) - TE(wire-&gt;Reddit).
 *
 * <p><b>Terminal inputs:</b> the Reddit series is the binned ticker mentions
 * from the story clusters of the subreddit feed; the wire series is the binned
 * headline counts of the same instrument from the headline archive (JSONL)
 * resp. the running news wire.
 */
public final class LeadLagInformationFlow {

    /** Below this bin count no measurement. */
    private static final int MIN_BINS = 30;
    /** Below this bin count the interpretation carries a caution note. */
    private static final int COMFORTABLE_BINS = 60;
    /** Interpretation threshold for a reliable flow direction (nats). */
    private static final double THRESHOLD = 0.02;

    private LeadLagInformationFlow() {
    }

    /**
     * Measures the net direction of information flow between the series.
     *
     * @param redditBins Reddit mentions per time bin
     * @param wireBins   press headlines per time bin (same bins)
     * @return reading, or empty on unequal length or fewer than {@value #MIN_BINS} bins
     */
    public static Optional<SignalReading> measure(int[] redditBins, int[] wireBins) {
        if (redditBins == null || wireBins == null
                || redditBins.length != wireBins.length
                || redditBins.length < MIN_BINS) {
            return Optional.empty();
        }

        int[] reddit = binarize(redditBins);
        int[] wire = binarize(wireBins);
        double teRedditToWire = transferEntropy(reddit, wire);
        double teWireToReddit = transferEntropy(wire, reddit);
        double value = teRedditToWire - teWireToReddit;

        String teText = "TE(Reddit->wire)=" + MathKit.fmt(teRedditToWire, 4)
                + " nats, TE(wire->Reddit)=" + MathKit.fmt(teWireToReddit, 4) + " nats.";

        String interpretation;
        if (value > THRESHOLD) {
            interpretation = "THE CAGE LEADS: Reddit is a genuine early indicator for this "
                    + "instrument - take the sentiment here seriously. " + teText;
        } else if (value < -THRESHOLD) {
            interpretation = "The cage parrots: Reddit sentiment is lagging for this instrument "
                    + "and should be discounted as a signal. " + teText;
        } else {
            interpretation = "No reliable information flow measurable: neither direction dominates - "
                    + "Reddit is neither a leading nor a lagging indicator. " + teText;
        }
        if (redditBins.length < COMFORTABLE_BINS) {
            interpretation += " Caution: only n=" + redditBins.length
                    + " time bins - the flow direction is accordingly uncertain.";
        }

        return Optional.of(new SignalReading(
                "lead-lag-information-flow",
                "Information flow cage<->wire (transfer entropy)",
                value,
                MathKit.fmt(value, 4) + " nats (TE difference Reddit->wire minus wire->Reddit)",
                "Measures whether Reddit mentions inform an instrument's press headlines "
                        + "or vice versa - calibrates where the cage is an early indicator.",
                interpretation));
    }

    private static int[] binarize(int[] bins) {
        int[] out = new int[bins.length];
        for (int i = 0; i < bins.length; i++) {
            out[i] = bins[i] > 0 ? 1 : 0;
        }
        return out;
    }

    /**
     * Lag-1 transfer entropy TE(src->dst) in nats with Laplace smoothing (+1)
     * over the eight joint states (dst_t+1, dst_t, src_t).
     */
    private static double transferEntropy(int[] src, int[] dst) {
        double[][][] c = new double[2][2][2]; // [dstNext][dstNow][srcNow]
        for (int y1 = 0; y1 < 2; y1++) {
            for (int y0 = 0; y0 < 2; y0++) {
                for (int x0 = 0; x0 < 2; x0++) {
                    c[y1][y0][x0] = 1; // Laplace
                }
            }
        }
        double total = 8;
        for (int t = 0; t + 1 < dst.length; t++) {
            c[dst[t + 1]][dst[t]][src[t]] += 1;
            total += 1;
        }

        double te = 0;
        for (int y0 = 0; y0 < 2; y0++) {
            double margDenom = c[0][y0][0] + c[0][y0][1] + c[1][y0][0] + c[1][y0][1];
            for (int x0 = 0; x0 < 2; x0++) {
                double fullDenom = c[0][y0][x0] + c[1][y0][x0];
                for (int y1 = 0; y1 < 2; y1++) {
                    double joint = c[y1][y0][x0] / total;
                    double pFull = c[y1][y0][x0] / fullDenom;
                    double pMarg = (c[y1][y0][0] + c[y1][y0][1]) / margDenom;
                    te += joint * Math.log(pFull / pMarg);
                }
            }
        }
        return Math.max(0, te);
    }
}
