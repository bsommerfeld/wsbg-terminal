package de.bsommerfeld.wsbg.terminal.signals.price;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Optional;

/**
 * Silent move: event-study decomposition of today's return into market and residual.
 *
 * <p><b>Method:</b> From the joint history the instrument's beta against the
 * market is estimated via OLS (market model of the event-study literature,
 * Fama/Fisher/Jensen/Roll 1969, canonical in MacKinlay 1997). The abnormal
 * return is today's asset return minus beta times the market return; it is
 * z-scored against the historical residuals of the same model
 * ({@link MathKit#zScore}). If a large unexplained residual remains after
 * subtracting the market AND no headline attributes it, someone is trading
 * on information we do not have.
 *
 * <p><b>Terminal inputs:</b> the return series come from the terminal's
 * price histories (the instrument's venue quotes, an index series as market
 * proxy), the attribution flag from the headline archive's ticker mapping
 * for the current tick.
 */
public final class SilentMoveDetector {

    /** Below this history length no beta is reliable. */
    private static final int MIN_HISTORY = 30;
    /** Below this history length the interpretation carries a caution suffix. */
    private static final int COMFORTABLE_HISTORY = 60;
    /** At or above this absolute z-score the residual counts as abnormal. */
    private static final double Z_ABNORMAL = 2.5;

    private SilentMoveDetector() {
    }

    /**
     * Measures how unusual the market-adjusted residual of today's return is.
     *
     * @param assetReturnsPct      historical returns of the instrument in percent
     * @param marketReturnsPct     historical returns of the market proxy in percent (same length)
     * @param currentAssetReturnPct  today's return of the instrument in percent
     * @param currentMarketReturnPct today's return of the market proxy in percent
     * @param attributableHeadline whether a headline exists that explains the move
     * @return reading, or empty on mismatched series lengths, fewer than
     *         {@value #MIN_HISTORY} points, or a degenerate market
     */
    public static Optional<SignalReading> measure(double[] assetReturnsPct,
            double[] marketReturnsPct, double currentAssetReturnPct,
            double currentMarketReturnPct, boolean attributableHeadline) {
        if (assetReturnsPct == null || marketReturnsPct == null
                || assetReturnsPct.length != marketReturnsPct.length
                || assetReturnsPct.length < MIN_HISTORY
                || !Double.isFinite(currentAssetReturnPct)
                || !Double.isFinite(currentMarketReturnPct)) {
            return Optional.empty();
        }

        int n = assetReturnsPct.length;
        double marketMean = MathKit.mean(marketReturnsPct);
        double assetMean = MathKit.mean(assetReturnsPct);
        double covariance = 0;
        double marketSq = 0;
        for (int i = 0; i < n; i++) {
            double dm = marketReturnsPct[i] - marketMean;
            covariance += dm * (assetReturnsPct[i] - assetMean);
            marketSq += dm * dm;
        }
        if (marketSq == 0) {
            return Optional.empty();
        }
        double beta = covariance / marketSq;

        double[] residuals = new double[n];
        for (int i = 0; i < n; i++) {
            residuals[i] = assetReturnsPct[i] - beta * marketReturnsPct[i];
        }
        double abnormalReturnPct = currentAssetReturnPct - beta * currentMarketReturnPct;
        double z = MathKit.zScore(abnormalReturnPct, residuals);

        String interpretation;
        if (Math.abs(z) >= Z_ABNORMAL && !attributableHeadline) {
            interpretation = "SILENT MOVE: after subtracting the market a residual remains that no "
                    + "headline explains - someone is trading on information we do not have. "
                    + "Automatic research order: actively sweep the sources on this instrument.";
        } else if (Math.abs(z) >= Z_ABNORMAL) {
            interpretation = "Move is attributed: a headline explains the abnormal residual - "
                    + "now check the strength of the reaction against the event type's base rate.";
        } else {
            interpretation = "Within range: today's move is covered by the market and normal "
                    + "noise, no unexplained residual.";
        }
        interpretation += " Decomposition: beta " + MathKit.fmt(beta, 2)
                + " against the market, abnormal return " + MathKit.fmt(abnormalReturnPct, 2) + " %.";
        if (n < COMFORTABLE_HISTORY) {
            interpretation += " Caution: only " + n
                    + " historical points for beta and residuals - the decomposition is accordingly uncertain.";
        }

        return Optional.of(new SignalReading(
                "silent-move-detector",
                "Silent move (event-study attribution)",
                z,
                MathKit.fmt(z, 2) + " (z-score of the abnormal return; abnormal "
                        + MathKit.fmt(abnormalReturnPct, 2) + " %, beta " + MathKit.fmt(beta, 2) + ")",
                "Decomposes today's return per the event-study market model into beta times "
                        + "market plus residual and measures how unusual that residual is against "
                        + "its own historical residuals.",
                interpretation));
    }
}
