package de.bsommerfeld.wsbg.terminal.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the compose gate's checkable half ({@link EditorialAgent#namesTheSubject}).
 *
 * <p>The gate exists because a model can read the "the name is always in the line"
 * rule and still paraphrase the subject away - measured on a foreign model, three
 * lines out of three described the subject instead of naming it. The line is read
 * ALONE, so a paraphrase leaves it without a referent. What the house can check,
 * the house checks.
 */
class ComposeNameGateTest {

    private static SubjectUnit unit(String canonicalName) {
        return new SubjectUnit("name:" + canonicalName.toLowerCase(), canonicalName);
    }

    @Test
    void theNameInTheLinePasses() {
        assertTrue(EditorialAgent.namesTheSubject(
                "Die Affen setzen alles auf NVIDIA als einzigen KI-Pick", unit("NVIDIA")));
    }

    @Test
    void aParaphraseWithoutTheNameFails() {
        // The exact defect the gate was built for.
        assertFalse(EditorialAgent.namesTheSubject(
                "Die Affen setzen alles auf den einzigen KI-Pick und hebeln all-in.",
                unit("NVIDIA")));
    }

    @Test
    void theTickerIsNoSubstituteForTheName() {
        // The prompt forbids the symbol in the name's place, so a line carrying only
        // the symbol is the defect, not a pass - hence no ticker fallback in the check.
        assertFalse(EditorialAgent.namesTheSubject(
                "Affen kaufen $NVDA mit 100er Hebel", unit("NVIDIA")));
    }

    @Test
    void aMultiWordNameMatchesOnItsDistinctiveWord() {
        assertTrue(EditorialAgent.namesTheSubject(
                "Rheinmetall meldet einen Auftrag über 1,2 Mrd. Euro",
                unit("Rheinmetall AG")));
    }

    @Test
    void umlautSpellingsStillMatch() {
        assertTrue(EditorialAgent.namesTheSubject(
                "Muenchener Rueck hebt die Prognose", unit("Münchener Rück")));
    }

    @Test
    void anUncheckableNameNeverBlocksALine() {
        // Nothing significant to check = no deviation to hold up; the line stands.
        assertTrue(EditorialAgent.namesTheSubject("irgendeine Zeile ohne den Namen", unit("AG")));
    }

    @Test
    void anEmptyLineIsNotTheGatesBusiness() {
        // The empty/redundant path is decided before the gate - it must not interfere.
        assertTrue(EditorialAgent.namesTheSubject("", unit("NVIDIA")));
        assertTrue(EditorialAgent.namesTheSubject(null, unit("NVIDIA")));
    }
}
