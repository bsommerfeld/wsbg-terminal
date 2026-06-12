package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.agent.SubjectUnit.EvidenceRef;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Conservative identity-merge (#2 step 2.5): a ticker-less name unit folds into
 * a ticker unit only when they share BOTH a piece of evidence and a name word —
 * never swallowing two genuinely different subjects.
 */
class SubjectRegistryTest {

    private static EvidenceRef ev(String thread, String comment, String snippet) {
        return new EvidenceRef(thread, comment, snippet, "reddit", java.time.Instant.now().getEpochSecond());
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

    @Test
    void prunesConsumedEvidencePastThresholdButKeepsUnitAndHeadlines() {
        SubjectRegistry reg = new SubjectRegistry();
        SubjectUnit u = reg.findOrCreate("NVDA", "NVIDIA");
        u.addEvidence(ev("t3_x", "t1_now", "Nvidia"));
        u.addHeadline("NVIDIA (NVDA) +2%", false);

        // Fresh content: nothing is stale yet.
        assertEquals(0, reg.pruneContentOlderThan(Duration.ofMinutes(60)));
        assertEquals(1, reg.get("NVDA").evidenceCount());

        // Negative age → cutoff in the future, so all evidence counts as consumed
        // and is dropped — but the UNIT survives, and its headlines survive too:
        // they are the story memory that stops a post-prune unit from looking
        // brand-new and re-publishing its old story as NEW.
        assertEquals(1, reg.pruneContentOlderThan(Duration.ofMinutes(-1)));
        assertNotNull(reg.get("NVDA"), "unit must survive content pruning");
        assertEquals(0, reg.get("NVDA").evidenceCount());
        assertEquals("NVIDIA (NVDA) +2%", reg.get("NVDA").lastHeadlineText(),
                "headlines are story memory — never pruned");
    }

    @Test
    void coveredNewsIsTrackedPerUnitAndIgnoresBlanks() {
        // #2 step 3b: a cited news id is marked covered so the next compose skips it.
        SubjectRegistry reg = new SubjectRegistry();
        SubjectUnit u = reg.findOrCreate("NVDA", "NVIDIA");
        assertFalse(u.isNewsCovered("uuid-1"));
        u.markNewsCovered(Arrays.asList("uuid-1", "uuid-2", "", null));
        assertTrue(u.isNewsCovered("uuid-1"));
        assertTrue(u.isNewsCovered("uuid-2"));
        assertFalse(u.isNewsCovered("uuid-3"));
        assertEquals(2, u.coveredNewsIds().size(), "blank/null ids are not tracked");
    }
}
