package de.bsommerfeld.wsbg.terminal.signals.flow;

import de.bsommerfeld.wsbg.terminal.signals.flow.HypePhase.Phase;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HypePhaseVoteTest {

    private static HypePhaseVote.Vote v(String axis, Phase p) {
        return new HypePhaseVote.Vote(axis, p);
    }

    @Test
    void agreeingAxesCarryTheVerdictUnanimously() {
        var t = HypePhaseVote.count(List.of(
                v("price and volume", Phase.MIDDLE),
                v("options book", Phase.MIDDLE),
                v("open interest", Phase.MIDDLE)), List.of()).orElseThrow();

        assertEquals(Phase.MIDDLE, t.phase());
        assertEquals(3, t.votes());
        assertEquals(3, t.total());
        assertTrue(t.unanimous());
        assertFalse(t.contested());
        assertTrue(t.dissent().isEmpty());
    }

    @Test
    void theMajorityWinsAndTheDissenterIsNamed() {
        var t = HypePhaseVote.count(List.of(
                v("price and volume", Phase.IGNITION),
                v("options book", Phase.IGNITION),
                v("open interest", Phase.FADING)), List.of()).orElseThrow();

        assertEquals(Phase.IGNITION, t.phase());
        assertEquals(2, t.votes());
        assertFalse(t.unanimous());
        assertEquals(1, t.dissent().size());
        assertEquals("open interest", t.dissent().get(0).axis());
        assertEquals(Phase.FADING, t.dissent().get(0).phase());
    }

    /**
     * The whole reason a cold axis is tracked separately: counted as neutral it
     * would let a warm-up period outvote the axes that actually measured
     * something.
     */
    @Test
    void aColdAxisIsNamedAsSilentAndNeverCountedAsAgreement() {
        var t = HypePhaseVote.count(List.of(
                v("price and volume", Phase.IGNITION),
                v("options book", Phase.IGNITION)),
                List.of("open interest (archive needs 5 sessions)")).orElseThrow();

        assertEquals(2, t.total(), "a silent axis is not part of the count");
        assertTrue(t.unanimous(), "and it does not break unanimity either");
        assertEquals(1, t.coldAxes().size());
        String reading = HypePhaseVote.reading(t).orElseThrow().interpretation();
        assertTrue(reading.contains("did NOT vote and must not be read as agreement"), reading);
    }

    @Test
    void aTieBreaksTowardsTheLaterPhase() {
        var t = HypePhaseVote.count(List.of(
                v("price and volume", Phase.MIDDLE),
                v("open interest", Phase.FADING)), List.of()).orElseThrow();

        assertEquals(Phase.FADING, t.phase(),
                "calling a fading wave 'running' invites a reader in at the top");
        assertTrue(t.contested(), "a 1:1 split is contested by definition");
        assertTrue(HypePhaseVote.reading(t).orElseThrow()
                .interpretation().contains("SPLIT"));
    }

    @Test
    void tooFewAxesProduceNoTally() {
        assertTrue(HypePhaseVote.count(null, List.of()).isEmpty());
        assertTrue(HypePhaseVote.count(List.of(), List.of()).isEmpty());
        assertTrue(HypePhaseVote.count(List.of(v("price and volume", Phase.MIDDLE)),
                List.of("options book")).isEmpty(),
                "one axis is a reading, not a tally");
    }

    @Test
    void theReadingHandsTheModelExactlyOneTask() {
        var t = HypePhaseVote.count(List.of(
                v("price and volume", Phase.MIDDLE),
                v("options book", Phase.MIDDLE)), List.of()).orElseThrow();
        String reading = HypePhaseVote.reading(t).orElseThrow().interpretation();

        assertTrue(reading.contains("check whether anything in the material CONTRADICTS"),
                reading);
        assertTrue(reading.contains("Do NOT compose a phase of your own"),
                "the model may overturn a verdict, never compose one: " + reading);
    }

    @Test
    void theReadingToleratesAMissingTally() {
        assertTrue(HypePhaseVote.reading(null).isEmpty());
    }
}
