package de.bsommerfeld.wsbg.terminal.signals.swarm;

import de.bsommerfeld.wsbg.terminal.signals.MathKit;
import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Author cohort freshness: the share of young accounts among the carriers of a
 * topic, compared with the house baseline. The idea is the cohort view of
 * survival analysis (only the share of the youngest cohort instead of a full
 * Kaplan-Meier build-out): organic discussions are carried by the cage's
 * regulars, coordinated pump patterns by conspicuously fresh accounts (cf. the
 * astroturfing/sockpuppet literature, e.g. Kumar et al. 2017 on sockpuppetry
 * in online discussions).
 *
 * <p>Numerics: an author counts as "young" if their firstSeen lies within the
 * window or they have never been seen (missing from the map). value = young
 * share in [0,1]; the interpretation compares it with the given baseline (the
 * subreddit's usual young share).
 *
 * <p>Terminal input: the active authors of a cluster/thread from the Reddit
 * snapshot, firstSeen per author from the persisted author register
 * (session snapshot/archive), the baseline from the long-term feed average.
 */
public final class AuthorCohortFreshness {

    private static final String ID = "author-cohort-freshness";
    private static final String TITLE = "Author cohort freshness";
    private static final String DEFINITION =
            "Measures whether a topic is carried by the cage's regulars or by"
                    + " fresh/coordinated accounts.";

    private static final int MIN_AUTHORS = 5;
    private static final int THIN_AUTHORS = 10;

    private AuthorCohortFreshness() {
    }

    /**
     * Computes the young share of the active authors.
     * At least {@value #MIN_AUTHORS} active authors, otherwise {@link Optional#empty()}.
     *
     * @param activeAuthors      authors currently carrying the topic
     * @param firstSeenByAuthor  first sighting per author; missing authors count as never seen
     * @param now                reference instant
     * @param youngWindow        window within which an author counts as young
     * @param baselineYoungShare the feed's usual young share [0,1]
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
                + " (young share, scale 0-1; baseline " + MathKit.fmt(baselineYoungShare, 2) + ")";
        return Optional.of(new SignalReading(ID, TITLE, value, formatted, DEFINITION, interpretation));
    }

    private static String interpret(double value, double baseline, int authorCount) {
        String baselineText = "baseline " + MathKit.fmt(baseline, 2);
        String band;
        if (value > 0.5 && value > 2 * baseline) {
            band = "PUMP SUSPICION: the carrier swarm is unusually fresh"
                    + " (" + baselineText + ", currently more than double) -"
                    + " coordination pattern, distrust the claims twice over.";
        } else if (value <= 1.5 * baseline) {
            band = "ORGANIC: the regulars are talking, the young share is within"
                    + " the usual range (" + baselineText + ").";
        } else {
            band = "ELEVATED: more fresh accounts than usual (" + baselineText + ") -"
                    + " watch it, not yet a coordination finding.";
        }
        if (authorCount < THIN_AUTHORS) {
            band += " Caution: only n=" + authorCount + " active authors - the share"
                    + " is correspondingly coarse.";
        }
        return band;
    }
}
