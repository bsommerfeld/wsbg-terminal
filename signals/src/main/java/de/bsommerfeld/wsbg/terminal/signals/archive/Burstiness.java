package de.bsommerfeld.wsbg.terminal.signals.archive;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Burstiness fingerprint of an instrument's news flow.
 *
 * <p><b>Method:</b> Over the inter-arrival times of the headline events the
 * burstiness measure B = (sigma - mu) / (sigma + mu) after Goh/Barabasi
 * ("Burstiness and memory in complex systems", EPL 2008; Kim/Jo correction
 * line) is computed: B near -1 is a perfectly regular cadence, B near 0 a
 * Poisson stream, B near +1 an extremely bursty flow (explosions with long
 * silence in between). Mean and standard deviation come from {@link MathKit}.
 *
 * <p><b>Terminal inputs:</b> the event timestamps are the per-ticker indexed
 * publication times from the headline archive (JSONL), i.e. the entire news
 * wire flow that ever ran on this instrument.
 */
public final class Burstiness {

    /** Below this event count (= fewer than 7 gaps) no measurement. */
    private static final int MIN_EVENTS = 8;
    /** Below this event count the interpretation carries a caution note. */
    private static final int COMFORTABLE_EVENTS = 20;

    private Burstiness() {
    }

    /**
     * Measures the cadence character of the news flow.
     *
     * @param events publication times of an instrument's headlines
     * @return reading, or empty with fewer than {@value #MIN_EVENTS} events
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
            interpretation = "REGULAR CADENCE (blue-chip profile): a single headline is business "
                    + "as usual here - only the deviation from the cadence is the event.";
        } else if (value < 0.5) {
            interpretation = "Mixed profile: the flow has bursts but also a carrying cadence - "
                    + "a single headline is neither routine nor alarm.";
        } else {
            interpretation = "EXPLOSIVE PROFILE (pennystock pattern): this instrument only exists in "
                    + "bursts - even a single headline is an event and deserves attention.";
        }
        if (sorted.size() < COMFORTABLE_EVENTS) {
            interpretation += " Caution: only n=" + sorted.size()
                    + " events in the history - the fingerprint is accordingly uncertain.";
        }

        return Optional.of(new SignalReading(
                "burstiness",
                "Burstiness fingerprint (news cadence)",
                value,
                MathKit.fmt(value, 2) + " (scale -1 to +1, +1 = maximally bursty)",
                "Measures the character of an instrument's news flow - regular cadence "
                        + "or explosions with silence in between.",
                interpretation));
    }
}
