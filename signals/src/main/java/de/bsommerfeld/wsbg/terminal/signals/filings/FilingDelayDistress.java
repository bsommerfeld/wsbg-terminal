package de.bsommerfeld.wsbg.terminal.signals.filings;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.Optional;

/**
 * Publication delay as a distress signal: measures how late a mandatory
 * document arrives against the issuer's own historical filing behaviour.
 *
 * <p><b>Method:</b> z-score of the current delay (in days) against mean and
 * standard deviation of the issuer's own historical delays, complemented by
 * the empirical percentile. Literature anchor: late financial statements as a
 * distress predictor in the accounting literature (Whittred/Zimmer 1984;
 * "late filings" and 12b-25 NT notices as a negative signal,
 * Bartov/Konchitchki 2017) - the non-appearance is itself the datum.
 *
 * <p><b>Terminal inputs:</b> publication and due dates from the
 * Bundesanzeiger (annual/consolidated statements), BaFin publications, EDGAR
 * filings and the calendar briefing (announced vs. actual reporting dates).
 */
public final class FilingDelayDistress {

    private static final int MIN_HISTORY = 3;
    private static final int THIN_HISTORY = 5;

    private FilingDelayDistress() {
    }

    /**
     * @param ownHistoricalDelaysDays own historical delays in days
     *                                (at least {@value #MIN_HISTORY} values)
     * @param currentDelayDays        current delay in days
     * @return reading, or empty on too thin a history
     */
    public static Optional<SignalReading> measure(double[] ownHistoricalDelaysDays, double currentDelayDays) {
        if (ownHistoricalDelaysDays == null || ownHistoricalDelaysDays.length < MIN_HISTORY) {
            return Optional.empty();
        }
        int n = ownHistoricalDelaysDays.length;
        double z = MathKit.zScore(currentDelayDays, ownHistoricalDelaysDays);
        double percentile = MathKit.empiricalPercentile(currentDelayDays, ownHistoricalDelaysDays);

        String formatted = "z=" + MathKit.fmt(z, 2)
                + " (current " + MathKit.fmt(currentDelayDays, 1) + " days, percentile "
                + MathKit.fmt(percentile * 100, 0) + "%, n=" + n + ")";

        String interpretation;
        if (z >= 1.5) {
            interpretation = "DELAY FLAG: significantly later than ever usual"
                    + " - open a distress hypothesis and look for corroborating signs"
                    + " (auditor, refinancing).";
        } else if (z >= 0) {
            interpretation = "Within own range: the delay sits inside the issuer's historical"
                    + " filing behaviour - no standalone signal.";
        } else {
            interpretation = "Earlier than usual: the document arrives ahead of the issuer's own"
                    + " historical rhythm - rather reassuring, management delivers.";
        }
        if (n <= THIN_HISTORY) {
            interpretation += " Caution: only n=" + n + " historical filings"
                    + " - thin data, read the z-score with restraint.";
        }

        return Optional.of(new SignalReading(
                "filing-delay-distress",
                "Publication delay (distress signal)",
                z,
                formatted,
                "Measures how late a mandatory document arrives against the issuer's own historical"
                        + " filing behaviour - a validated distress predictor in the accounting"
                        + " literature; the non-appearance is itself the datum.",
                interpretation));
    }
}
