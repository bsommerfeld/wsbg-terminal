package de.bsommerfeld.wsbg.terminal.signals.swarm;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Autoren-Kohorten-Frische: der Anteil junger Accounts unter den Trägern eines
 * Themas, verglichen mit dem Haus-Basisniveau. Die Idee ist die
 * Kohorten-Sicht der Survival-Analyse (statt eines Kaplan-Meier-Vollausbaus
 * nur der Anteil der jüngsten Kohorte): organische Diskussionen werden von der
 * Stammbelegschaft getragen, koordinierte Pump-Muster von auffallend frischen
 * Accounts (vgl. die Astroturfing-/Sockpuppet-Literatur, z.B. Kumar et al.
 * 2017 zu Sockpuppetry in Online-Diskussionen).
 *
 * <p>Numerik: ein Autor gilt als "jung", wenn sein firstSeen innerhalb des
 * Fensters liegt oder er noch nie gesehen wurde (fehlt in der Map). value =
 * Jung-Anteil in [0,1]; die Deutung vergleicht ihn mit der übergebenen
 * Baseline (dem üblichen Jung-Anteil des Subreddits).
 *
 * <p>Input im Terminal: die aktiven Autoren eines Clusters/Threads aus dem
 * Reddit-Snapshot, firstSeen pro Autor aus dem persistierten Autoren-Register
 * (Session-Snapshot/Archiv), die Baseline aus dem langfristigen Feed-Schnitt.
 */
public final class AuthorCohortFreshness {

    private static final String ID = "author-cohort-freshness";
    private static final String TITLE = "Autoren-Kohorten-Frische";
    private static final String DEFINITION =
            "Misst, ob ein Thema von der Stammbelegschaft oder von frischen/koordinierten"
                    + " Accounts getragen wird.";

    private static final int MIN_AUTHORS = 5;
    private static final int THIN_AUTHORS = 10;

    private AuthorCohortFreshness() {
    }

    /**
     * Berechnet den Jung-Anteil der aktiven Autoren.
     * Mindestens {@value #MIN_AUTHORS} aktive Autoren, sonst {@link Optional#empty()}.
     *
     * @param activeAuthors      Autoren, die das Thema aktuell tragen
     * @param firstSeenByAuthor  Erstsichtung pro Autor; fehlende Autoren gelten als nie gesehen
     * @param now                Bezugszeitpunkt
     * @param youngWindow        Fenster, innerhalb dessen ein Autor als jung gilt
     * @param baselineYoungShare üblicher Jung-Anteil des Feeds [0,1]
     */
    public static Optional<SignalReading> measure(
            Set<String> activeAuthors,
            Map<String, Instant> firstSeenByAuthor,
            Instant now,
            Duration youngWindow,
            double baselineYoungShare) {
        if (activeAuthors == null || activeAuthors.size() < MIN_AUTHORS) {
            return Optional.empty();
        }
        Instant windowStart = now.minus(youngWindow);
        int young = 0;
        for (String author : activeAuthors) {
            Instant firstSeen = firstSeenByAuthor == null ? null : firstSeenByAuthor.get(author);
            if (firstSeen == null || !firstSeen.isBefore(windowStart)) {
                young++;
            }
        }
        int total = activeAuthors.size();
        double value = (double) young / total;

        String interpretation = interpret(value, baselineYoungShare, total);
        String formatted = MathKit.fmt(value, 2)
                + " (Jung-Anteil, Skala 0-1; Baseline " + MathKit.fmt(baselineYoungShare, 2) + ")";
        return Optional.of(new SignalReading(ID, TITLE, value, formatted, DEFINITION, interpretation));
    }

    private static String interpret(double value, double baseline, int authorCount) {
        String baselineText = "Baseline " + MathKit.fmt(baseline, 2);
        String band;
        if (value > 0.5 && value > 2 * baseline) {
            band = "PUMP-VERDACHT: der Träger-Schwarm ist ungewöhnlich frisch"
                    + " (" + baselineText + ", aktuell mehr als das Doppelte) -"
                    + " Koordinationsmuster, Aussagen doppelt misstrauen.";
        } else if (value <= 1.5 * baseline) {
            band = "Organisch: die Stammbelegschaft diskutiert, der Jung-Anteil liegt"
                    + " im üblichen Rahmen (" + baselineText + ").";
        } else {
            band = "Erhöht: mehr frische Accounts als üblich (" + baselineText + ") -"
                    + " beobachten, noch kein Koordinationsbefund.";
        }
        if (authorCount < THIN_AUTHORS) {
            band += " Vorsicht: nur " + authorCount + " aktive Autoren, der Anteil"
                    + " ist entsprechend grob.";
        }
        return band;
    }
}
