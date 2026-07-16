package de.bsommerfeld.wsbg.terminal.signals.archive;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Burstiness-Fingerprint des Nachrichtenflusses eines Papiers.
 *
 * <p><b>Methode:</b> Ueber die Zwischenankunftszeiten der Headline-Events wird
 * das Burstiness-Mass B = (sigma - mu) / (sigma + mu) nach Goh/Barabasi
 * ("Burstiness and memory in complex systems", EPL 2008; Kim/Jo-Korrekturlinie)
 * gerechnet: B nahe -1 ist ein perfekt regelmaessiger Takt, B nahe 0 ein
 * Poisson-Strom, B nahe +1 ein extrem geschubter Fluss (Explosionen mit
 * langer Stille dazwischen). Mittelwert und Streuung kommen aus
 * {@link MathKit}.
 *
 * <p><b>Inputs im Terminal:</b> die Event-Zeitstempel sind die per Ticker
 * indizierten Publikationszeiten aus dem Headline-Archiv (JSONL), also der
 * gesamte je gelaufene News-Wire-Fluss zu diesem Papier.
 */
public final class Burstiness {

    /** Unter dieser Event-Zahl (= weniger als 7 Abstaende) keine Messung. */
    private static final int MIN_EVENTS = 8;
    /** Unter dieser Event-Zahl traegt die Deutung einen Vorsichts-Zusatz. */
    private static final int COMFORTABLE_EVENTS = 20;

    private Burstiness() {
    }

    /**
     * Misst den Takt-Charakter des Nachrichtenflusses.
     *
     * @param events Publikations-Zeitpunkte der Headlines eines Papiers
     * @return Befund, oder empty bei weniger als {@value #MIN_EVENTS} Events
     */
    public static Optional<SignalReading> measure(List<Instant> events) {
        if (events == null) {
            return Optional.empty();
        }
        List<Instant> sorted = events.stream()
                .filter(e -> e != null)
                .sorted()
                .toList();
        if (sorted.size() < MIN_EVENTS) {
            return Optional.empty();
        }

        double[] gaps = new double[sorted.size() - 1];
        for (int i = 1; i < sorted.size(); i++) {
            gaps[i - 1] = (sorted.get(i).toEpochMilli() - sorted.get(i - 1).toEpochMilli()) / 1000.0;
        }
        double mu = MathKit.mean(gaps);
        double sigma = MathKit.std(gaps);
        double value = (sigma + mu) == 0 ? 0 : (sigma - mu) / (sigma + mu);

        String interpretation;
        if (value <= 0.1) {
            interpretation = "REGELMÄSSIGER TAKT (Blue-Chip-Profil): eine einzelne Headline ist hier "
                    + "Normalbetrieb - erst die Abweichung vom Takt ist das Ereignis.";
        } else if (value < 0.5) {
            interpretation = "Gemischtes Profil: der Fluss hat Schübe, aber auch einen tragenden Takt - "
                    + "eine einzelne Headline ist weder Routine noch Alarm.";
        } else {
            interpretation = "EXPLOSIVES PROFIL (Pennystock-Muster): dieses Papier existiert nur in "
                    + "Schüben - schon eine einzelne Meldung ist ein Ereignis und verdient Aufmerksamkeit.";
        }
        if (sorted.size() < COMFORTABLE_EVENTS) {
            interpretation += " Vorsicht: nur " + sorted.size()
                    + " Events in der Historie - der Fingerprint ist entsprechend unsicher.";
        }

        return Optional.of(new SignalReading(
                "burstiness",
                "Burstiness-Fingerprint (Nachrichten-Takt)",
                value,
                MathKit.fmt(value, 2) + " (Skala -1 bis +1, +1 = maximal geschubt)",
                "Misst den Charakter des Nachrichtenflusses eines Papiers - regelmässiger Takt "
                        + "oder Explosionen mit Stille dazwischen.",
                interpretation));
    }
}
