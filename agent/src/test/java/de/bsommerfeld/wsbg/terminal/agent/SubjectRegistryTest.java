package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.agent.SubjectUnit.EvidenceRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Conservative identity-merge (#2 step 2.5): a ticker-less name unit folds into
 * a ticker unit only when they share BOTH a piece of evidence and a name word —
 * never swallowing two genuinely different subjects.
 */
class SubjectRegistryTest {

    private static EvidenceRef ev(String thread, String comment, String snippet) {
        return new EvidenceRef(thread, comment, snippet, "reddit", 0L);
    }

    @Test
    void foldsNameUnitIntoTickerWhenSharingWordAndEvidence() {
        SubjectRegistry reg = new SubjectRegistry();
        // Same comment names both "TMC the metals company" and "the metals company".
        EvidenceRef shared = ev("t3_x", "t1_a", "TMC - the metals company oder IONQ");

        SubjectUnit ticker = reg.findOrCreate("TMC", "TMC the metals company Inc.");
        ticker.updateResolved("TMC the metals company Inc.", "TMC", null, null);
        ticker.addEvidence(shared);

        SubjectUnit name = reg.findOrCreate("name:the metals company", "The Metals Company");
        name.addEvidence(shared);

        assertEquals(1, reg.mergeIdentities());
        assertNull(reg.get("name:the metals company"), "name unit should be absorbed");
        SubjectUnit kept = reg.get("TMC");
        assertNotNull(kept);
        assertEquals(1, kept.evidenceCount(), "shared evidence deduped, not doubled");
    }

    @Test
    void doesNotMergeWithoutSharedEvidence() {
        SubjectRegistry reg = new SubjectRegistry();
        SubjectUnit ticker = reg.findOrCreate("TMC", "TMC the metals company Inc.");
        ticker.updateResolved("TMC the metals company Inc.", "TMC", null, null);
        ticker.addEvidence(ev("t3_x", "t1_a", "TMC the metals company"));

        SubjectUnit name = reg.findOrCreate("name:the metals company", "The Metals Company");
        name.addEvidence(ev("t3_x", "t1_DIFFERENT", "the metals company woanders"));

        assertEquals(0, reg.mergeIdentities(), "no shared comment → no merge");
        assertNotNull(reg.get("name:the metals company"));
    }

    @Test
    void doesNotMergeWithoutSharedWord() {
        SubjectRegistry reg = new SubjectRegistry();
        EvidenceRef shared = ev("t3_x", "t1_a", "Pennystocks und MustGrow");
        SubjectUnit ticker = reg.findOrCreate("MGRO.V", "MustGrow Biologics Corp.");
        ticker.updateResolved("MustGrow Biologics Corp.", "MGRO.V", null, null);
        ticker.addEvidence(shared);

        SubjectUnit name = reg.findOrCreate("name:pennystocks", "Pennystocks");
        name.addEvidence(shared);

        // Share the comment but no common significant word → must NOT swallow.
        assertEquals(0, reg.mergeIdentities());
        assertNotNull(reg.get("name:pennystocks"));
    }
}
