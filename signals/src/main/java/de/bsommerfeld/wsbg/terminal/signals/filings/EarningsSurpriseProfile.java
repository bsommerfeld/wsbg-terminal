package de.bsommerfeld.wsbg.terminal.signals.filings;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Optional;

/**
 * Sandbagging profile: uses a beta-binomial posterior to measure whether a
 * firm systematically beats its guidance - and whether the current surprise
 * is exceptional even against that pattern.
 *
 * <p><b>Method:</b> Beta(1,1) posterior for the beat probability,
 * P(beat) = (beats + 1) / (reports + 2). If a current surprise percentage and
 * enough surprise history are available, the standardized surprise
 * z = (current - mean) / std is computed in addition. Literature anchor:
 * expectations management and "meet or beat" behaviour (Bartov/Givoly/Hayn
 * 2002; Matsumoto 2002) - the priced-in beat carries no information, only the
 * deviation from the firm's own pattern moves.
 *
 * <p><b>Terminal inputs:</b> historical beat/miss record and surprise
 * percentages from the calendar briefing (actual vs. forecast) plus earnings
 * releases from the ad-hoc feeds (fn-adhoc, EQS) and EDGAR filings.
 */
public final class EarningsSurpriseProfile {

    private static final int MIN_REPORTS = 4;
    private static final int MIN_SURPRISE_HISTORY = 5;
    private static final int THIN_REPORTS = 8;

    private EarningsSurpriseProfile() {
    }

    /**
     * @param historicalBeats         number of historical beats
     * @param historicalReports       number of historical reports (at least {@value #MIN_REPORTS})
     * @param currentSurprisePct      current surprise in percent, or null if unknown
     * @param historicalSurprisesPct  historical surprises in percent (for the z-score,
     *                                at least {@value #MIN_SURPRISE_HISTORY} values required)
     * @return reading, or empty on too thin data
     */
    public static Optional<SignalReading> measure(int historicalBeats, int historicalReports,
                                                  Double currentSurprisePct, double[] historicalSurprisesPct) {
        if (historicalReports < MIN_REPORTS || historicalBeats < 0 || historicalBeats > historicalReports) {
            return Optional.empty();
        }
        double posterior = (historicalBeats + 1.0) / (historicalReports + 2.0);
        double[] ci = MathKit.jeffreysInterval(historicalBeats, historicalReports, 0.90);

        Double z = null;
        if (currentSurprisePct != null && historicalSurprisesPct != null
                && historicalSurprisesPct.length >= MIN_SURPRISE_HISTORY) {
            double sd = MathKit.std(historicalSurprisesPct);
            if (sd > 0 && Double.isFinite(sd)) {
                z = (currentSurprisePct - MathKit.mean(historicalSurprisesPct)) / sd;
            }
        }

        String formatted = "P(beat)=" + MathKit.fmt(posterior, 2)
                + " (90% interval " + MathKit.fmt(ci[0], 2) + "-" + MathKit.fmt(ci[1], 2)
                + ", n=" + historicalReports
                + (z != null ? ", surprise z=" + MathKit.fmt(z, 2) : "") + ")";

        String zNote = z != null
                ? " Standardized surprise z=" + MathKit.fmt(z, 2) + " at n=" + historicalReports + "."
                : " No standardized surprise computable (too little surprise history), n="
                + historicalReports + ".";

        String interpretation;
        if (posterior >= 0.7 && z != null && z >= 1) {
            interpretation = "GENUINE SURPRISE: exceptional even against the firm's own sandbagging"
                    + " pattern - this moves." + zNote;
        } else if (posterior >= 0.7) {
            interpretation = "A BEAT IS THE NORM HERE: the current print is no real signal,"
                    + " the market knows the pattern." + zNote;
        } else if (posterior < 0.5) {
            interpretation = "Honest scatterer: no systematic expectations management visible"
                    + " - here the plain beat/miss already counts." + zNote;
        } else {
            interpretation = "Mixed beat profile: no clear pattern in the history"
                    + " - judge the current print on its own." + zNote;
        }
        if (historicalReports < THIN_REPORTS) {
            interpretation += " Caution: only n=" + historicalReports + " reports"
                    + " - thin data, read the profile with restraint.";
        }

        return Optional.of(new SignalReading(
                "earnings-surprise-profile",
                "Sandbagging profile (beta-binomial)",
                posterior,
                formatted,
                "Measures a firm's expectations management - if it systematically beats its guidance"
                        + " (sandbagging), a beat is the norm and priced in by the market.",
                interpretation));
    }
}
