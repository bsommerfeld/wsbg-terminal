package de.bsommerfeld.wsbg.terminal.signals.flow;

import de.bsommerfeld.wsbg.terminal.signals.SignalReading;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static de.bsommerfeld.wsbg.terminal.signals.flow.HypePhase.Phase;

/**
 * The tally: every flow axis votes on the phase, and the house counts.
 *
 * <p><b>Why the house counts and the model does not.</b> A phase is a pattern
 * of signs across several independent axes - which is a classification, not a
 * judgment, and the one thing a small local model reliably gets wrong. So each
 * axis is thresholded on its OWN evidence (that part is testable), the votes
 * are counted here (that part is arithmetic), and only the finished tally goes
 * to the model, with exactly one thing left to do: check it against the
 * material and object if the material contradicts it. The model may overturn a
 * verdict, but it never composes one.
 *
 * <p><b>A cold axis is not a neutral vote.</b> An axis that has no data yet -
 * open interest before the archive has warmed, attention for a name nobody
 * writes about - is left OUT of the count and named as absent. Counting it as
 * neutral would let a warm-up period quietly outvote the axes that do speak.
 */
public final class HypePhaseVote {

    /** Below this many voting axes the tally is not worth stating. */
    private static final int MIN_AXES = 2;

    private HypePhaseVote() {
    }

    /**
     * One axis's vote.
     *
     * @param axis  the axis's own name, for the dissent line
     * @param phase what this axis alone says
     */
    public record Vote(String axis, Phase phase) {
    }

    /**
     * The counted tally.
     *
     * @param phase      the phase with the most votes
     * @param votes      how many axes voted for it
     * @param total      how many axes voted at all
     * @param dissent    the axes voting otherwise, with what they say
     * @param coldAxes   axes with no data yet, named so their silence is visible
     * @param unanimous  true when every voting axis agrees
     */
    public record Tally(Phase phase, int votes, int total, List<Vote> dissent,
            List<String> coldAxes, boolean unanimous) {

        /** True when the axes are split rather than merely not unanimous. */
        public boolean contested() {
            return total > 0 && votes * 2 <= total;
        }
    }

    /**
     * Counts the votes.
     *
     * @param votes    every axis that HAS data, one vote each
     * @param coldAxes names of the axes that have none yet
     * @return the tally, or empty when too few axes speak
     */
    public static Optional<Tally> count(List<Vote> votes, List<String> coldAxes) {
        if (votes == null || votes.size() < MIN_AXES) return Optional.empty();
        Map<Phase, Integer> counts = new EnumMap<>(Phase.class);
        for (Vote v : votes) {
            if (v == null || v.phase() == null) continue;
            counts.merge(v.phase(), 1, Integer::sum);
        }
        if (counts.isEmpty()) return Optional.empty();
        Phase winner = null;
        int best = 0;
        for (Map.Entry<Phase, Integer> e : counts.entrySet()) {
            // Ties break towards the LATER phase: mistaking a fading wave for a
            // running one invites a reader in at the top, the costlier error.
            if (e.getValue() > best
                    || (e.getValue() == best && winner != null
                            && e.getKey().ordinal() > winner.ordinal())) {
                winner = e.getKey();
                best = e.getValue();
            }
        }
        List<Vote> dissent = new ArrayList<>();
        for (Vote v : votes) {
            if (v != null && v.phase() != null && v.phase() != winner) dissent.add(v);
        }
        int total = 0;
        for (int c : counts.values()) total += c;
        return Optional.of(new Tally(winner, best, total, List.copyOf(dissent),
                coldAxes == null ? List.of() : List.copyOf(coldAxes), dissent.isEmpty()));
    }

    /**
     * The tally as the model's ONE order: here is the verdict, here is who
     * disagrees, now check it against the material.
     *
     * @param t the tally, may be null
     */
    public static Optional<SignalReading> reading(Tally t) {
        if (t == null) return Optional.empty();
        StringBuilder state = new StringBuilder();
        state.append(t.unanimous()
                ? "UNANIMOUS: all " + t.total() + " measurable axes say " + t.phase() + "."
                : t.votes() + " of " + t.total() + " measurable axes say " + t.phase() + ".");
        if (!t.dissent().isEmpty()) {
            state.append(" Dissenting: ");
            for (int i = 0; i < t.dissent().size(); i++) {
                if (i > 0) state.append("; ");
                state.append(t.dissent().get(i).axis()).append(" says ")
                        .append(t.dissent().get(i).phase());
            }
            state.append('.');
        }
        if (!t.coldAxes().isEmpty()) {
            state.append(" Silent for lack of data: ")
                    .append(String.join(", ", t.coldAxes()))
                    .append(" - these did NOT vote and must not be read as agreement.");
        }
        if (t.contested()) {
            state.append(" The axes are SPLIT, so treat the verdict as weak.");
        }
        return Optional.of(new SignalReading(
                "hype-phase-vote",
                "Where the flow axes come out",
                t.votes(),
                t.phase() + " (" + t.votes() + " of " + t.total() + " axes"
                        + (t.coldAxes().isEmpty() ? "" : ", " + t.coldAxes().size() + " silent")
                        + ")",
                "The tally over every flow axis that has data: each votes on the phase from "
                        + "its own evidence, the house counts.",
                state + " YOUR TASK with this line is exactly one thing: check whether anything "
                        + "in the material CONTRADICTS this verdict, name that place if it does, "
                        + "and otherwise let the verdict stand. Do NOT compose a phase of your "
                        + "own - the axes measured it, you only test it."));
    }
}
