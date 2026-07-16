package de.bsommerfeld.wsbg.terminal.signals.filings;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Optional;

/**
 * Ad-hoc-Timing-Prior: Meldezeitpunkt-Forensik ueber die eigene Melde-Historie
 * eines Emittenten.
 *
 * <p><b>Methode:</b> Die historischen Pflichtmeldungen werden in drei
 * Zeitfenster-Buckets sortiert (FREITAGNACHT, NACHBOERSLICH, HANDELSZEIT) und
 * pro Bucket wird die Laplace-geglaettete bedingte Wahrscheinlichkeit
 * P(negativ | Bucket) = (negativ + 1) / (n + 2) geschaetzt - ein
 * Beta(1,1)-Posterior-Mittelwert. Der Wert ist der Posterior fuer den Bucket
 * der aktuellen Meldung. Literaturanker: der "Friday Night Dump" bzw.
 * strategisches Disclosure-Timing (DellaVigna/Pollet 2009, "Investor
 * Inattention and Friday Earnings Announcements"; Niessner 2015).
 *
 * <p><b>Inputs im Terminal:</b> Zeitstempel und Tonalitaet historischer
 * Ad-hoc-/Pflichtmeldungen aus den Ad-hoc-Feeds (fn-adhoc, EQS) sowie
 * BaFin-Veroeffentlichungen; die Tonalitaets-Einstufung (negativ ja/nein)
 * kommt aus der Redaktions-Pipeline.
 */
public final class AdHocTimingPrior {

    /** Eine historische Pflichtmeldung: Wochentag, Stunde, und ob sie negativ war. */
    public record TimedFiling(DayOfWeek day, int hourOfDay, boolean negative) {
    }

    private static final int MIN_HISTORY = 30;
    private static final int THIN_BUCKET = 10;

    private AdHocTimingPrior() {
    }

    /**
     * Berechnet den Timing-Prior fuer eine Meldung am gegebenen Wochentag zur
     * gegebenen Stunde gegen die eigene Melde-Historie.
     *
     * @param history historische Meldungen (mindestens {@value #MIN_HISTORY})
     * @param day     Wochentag der aktuellen Meldung
     * @param hour    Stunde der aktuellen Meldung (0-23)
     * @return Befund, oder empty bei zu duenner Historie
     */
    public static Optional<SignalReading> measure(List<TimedFiling> history, DayOfWeek day, int hour) {
        if (history == null || history.size() < MIN_HISTORY) {
            return Optional.empty();
        }
        String bucket = bucketOf(day, hour);
        int n = 0;
        int neg = 0;
        for (TimedFiling f : history) {
            if (bucket.equals(bucketOf(f.day(), f.hourOfDay()))) {
                n++;
                if (f.negative()) neg++;
            }
        }
        double posterior = (neg + 1.0) / (n + 2.0);
        double[] ci = MathKit.jeffreysInterval(neg, n, 0.90);

        String formatted = "P(negativ)=" + MathKit.fmt(posterior, 2)
                + " (90%-Intervall " + MathKit.fmt(ci[0], 2) + "-" + MathKit.fmt(ci[1], 2)
                + ", Bucket " + bucket + ", n=" + n + ")";

        String interpretation;
        if (posterior >= 0.65) {
            interpretation = "TIMING-PRIOR SCHLECHT: klassisches Versteck-Zeitfenster fuer schlechte Nachrichten"
                    + " - den Inhalt besonders kritisch lesen, Beschoenigungen abklopfen"
                    + " (Bucket " + bucket + ", n=" + n + ").";
        } else if (posterior >= 0.45) {
            interpretation = "Neutraler Prior: dieses Zeitfenster war in der eigenen Historie weder auffaellig"
                    + " gut noch schlecht - der Inhalt entscheidet allein"
                    + " (Bucket " + bucket + ", n=" + n + ").";
        } else {
            interpretation = "Unauffaelliges Zeitfenster: Meldungen zu dieser Zeit waren historisch selten"
                    + " schlechte Nachrichten - kein Timing-Verdacht"
                    + " (Bucket " + bucket + ", n=" + n + ").";
        }
        if (n < THIN_BUCKET) {
            interpretation += " Vorsicht: nur n=" + n + " Meldungen in diesem Zeitfenster"
                    + " - duenne Datenbasis, Prior mit Zurueckhaltung lesen.";
        }

        return Optional.of(new SignalReading(
                "adhoc-timing-prior",
                "Ad-hoc-Timing-Prior (Meldezeitpunkt-Forensik)",
                posterior,
                formatted,
                "Basisrate aus der eigenen Historie, wie oft Meldungen in diesem Zeitfenster schlechte"
                        + " Nachrichten waren (Friday-Night-Dump-Effekt, in der Finanzliteratur gut belegt).",
                interpretation));
    }

    /** Ordnet Wochentag und Stunde einem der drei Zeitfenster-Buckets zu. */
    static String bucketOf(DayOfWeek day, int hour) {
        if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY
                || (day == DayOfWeek.FRIDAY && hour >= 17)) {
            return "FREITAGNACHT";
        }
        if (day != DayOfWeek.FRIDAY && (hour >= 17 || hour < 8)) {
            return "NACHBOERSLICH";
        }
        return "HANDELSZEIT";
    }
}
