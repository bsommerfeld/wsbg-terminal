package de.bsommerfeld.wsbg.terminal.signals.archive;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Optional;

/**
 * Richtung des Informationsflusses zwischen Käfig (Reddit) und Presse (Wire)
 * via Transfer-Entropie.
 *
 * <p><b>Methode:</b> Beide Zeitreihen (Erwaehnungs- bzw. Headline-Zaehler pro
 * Zeit-Bin) werden binarisiert (Aktivitaet ja/nein) und die Lag-1-Transfer-
 * Entropie nach Schreiber ("Measuring Information Transfer", PRL 2000) in
 * beide Richtungen gerechnet: TE(X-&gt;Y) = Summe p(y_t+1, y_t, x_t) *
 * log[ p(y_t+1 | y_t, x_t) / p(y_t+1 | y_t) ], in Nats, mit Laplace-Glaettung
 * (+1) ueber die acht gemeinsamen Zustaende gegen leere Zellen. Der
 * Signalwert ist die Differenz TE(Reddit-&gt;Wire) - TE(Wire-&gt;Reddit).
 *
 * <p><b>Inputs im Terminal:</b> die Reddit-Reihe sind die gebinnten
 * Ticker-Erwaehnungen aus den Story-Clustern des Subreddit-Feeds, die
 * Wire-Reihe die gebinnten Headline-Zaehler desselben Papiers aus dem
 * Headline-Archiv (JSONL) bzw. dem laufenden News-Wire.
 */
public final class LeadLagInformationFlow {

    /** Unter dieser Bin-Zahl keine Messung. */
    private static final int MIN_BINS = 30;
    /** Unter dieser Bin-Zahl traegt die Deutung einen Vorsichts-Zusatz. */
    private static final int COMFORTABLE_BINS = 60;
    /** Deutungs-Schwelle fuer eine belastbare Fluss-Richtung (Nats). */
    private static final double THRESHOLD = 0.02;

    private LeadLagInformationFlow() {
    }

    /**
     * Misst die Netto-Richtung des Informationsflusses zwischen den Reihen.
     *
     * @param redditBins Reddit-Erwaehnungen pro Zeit-Bin
     * @param wireBins   Presse-Headlines pro Zeit-Bin (gleiche Bins)
     * @return Befund, oder empty bei ungleicher Laenge oder weniger als {@value #MIN_BINS} Bins
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

        String teText = "TE(Reddit->Presse)=" + MathKit.fmt(teRedditToWire, 4)
                + " Nats, TE(Presse->Reddit)=" + MathKit.fmt(teWireToReddit, 4) + " Nats.";

        String interpretation;
        if (value > THRESHOLD) {
            interpretation = "DER KÄFIG LÄUFT VORAUS: Reddit ist bei diesem Papier ein echter "
                    + "Frühindikator - das Sentiment hier ernst nehmen. " + teText;
        } else if (value < -THRESHOLD) {
            interpretation = "Der Käfig plappert nach: das Reddit-Sentiment ist bei diesem Papier "
                    + "nachlaufend und als Signal abzuwerten. " + teText;
        } else {
            interpretation = "Kein belastbarer Informationsfluss messbar: keine Richtung dominiert - "
                    + "Reddit weder Früh- noch Nachlaufindikator. " + teText;
        }
        if (redditBins.length < COMFORTABLE_BINS) {
            interpretation += " Vorsicht: nur " + redditBins.length
                    + " Zeit-Bins - die Fluss-Richtung ist entsprechend unsicher.";
        }

        return Optional.of(new SignalReading(
                "lead-lag-information-flow",
                "Informationsfluss Käfig↔Presse (Transfer-Entropie)",
                value,
                MathKit.fmt(value, 4) + " Nats (TE-Differenz Reddit->Presse minus Presse->Reddit)",
                "Misst, ob die Reddit-Erwähnungen die Presse-Headlines eines Papiers informieren "
                        + "oder umgekehrt - kalibriert, wo der Käfig Frühindikator ist.",
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
     * Lag-1-Transfer-Entropie TE(src->dst) in Nats mit Laplace-Glaettung (+1)
     * ueber die acht gemeinsamen Zustaende (dst_t+1, dst_t, src_t).
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
