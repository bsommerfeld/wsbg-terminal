package de.bsommerfeld.wsbg.terminal.agent;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The evidence split that keeps a subject's own mentions from being crowded out
 * of the compose window. Three populations share the unit's evidence map, and
 * before they were told apart the largest one won every time:
 *
 * <ul>
 *   <li>{@code ROOM}/{@code VISION} — mentions of the subject. Few, irreplaceable.</li>
 *   <li>{@code CONTEXT} — the reply chain a mention hangs in. Bounded, and the
 *       mention often unreadable without it.</li>
 *   <li>{@code MOOD} — the thread's remaining chatter. Unbounded (every cached
 *       comment of every active thread), individually worthless, in sum the room's
 *       mood.</li>
 * </ul>
 *
 * <p>MOOD used to carry the CONTEXT tag and land in the compose brief, where the
 * char budget — which walks from the END of the list, and chatter is appended
 * last — resolved the contest in its favour. The fact-sheet watermark then marked
 * the displaced mentions absorbed, so they returned in no later brief either.
 * The numbers in {@link #mentionsSurviveHeavyChatter()} are that failure pinned
 * down: without the split they were 0.
 */
class EvidencePriorityTest {

    private static final String MENTION =
            "Nvidia meldet Rekordumsatz, Datacenter waechst 40 Prozent, ich hab Calls auf 200 - MENTION";
    private static final String ANCESTOR =
            "Der ganze KI-Sektor ist ueberbewertet, das platzt noch dieses Jahr, sagt mein Bauchgefuehl - ANCESTOR";
    private static final String CHATTER =
            "lol wer braucht fundamentaldaten, ich kauf calls und hoffe auf den mond, diamond hands - GELABER";

    private static SubjectUnit unit(int mentions, int ancestors, int mood) {
        long now = Instant.now().getEpochSecond();
        SubjectUnit u = new SubjectUnit("NVDA", "NVIDIA");
        // Insertion order mirrors the live pipeline: mentions first, then the reply
        // chains they hang in, then the thread dump. The budget walks from the end.
        for (int i = 0; i < mentions; i++) {
            u.addEvidence(new SubjectUnit.EvidenceRef("t1", "m" + i, MENTION + i,
                    SubjectUnit.EvidenceRef.ROOM, now - 100));
        }
        for (int i = 0; i < ancestors; i++) {
            u.addEvidence(new SubjectUnit.EvidenceRef("t1", "a" + i, ANCESTOR + i,
                    SubjectUnit.EvidenceRef.CONTEXT, now - 90));
        }
        for (int i = 0; i < mood; i++) {
            u.addEvidence(new SubjectUnit.EvidenceRef("t1", "x" + i, CHATTER + i,
                    SubjectUnit.EvidenceRef.MOOD, now));
        }
        return u;
    }

    private static long linesWith(String brief, String needle) {
        return brief.lines().filter(l -> l.contains(needle)).count();
    }

    @Test
    void moodNeverReachesTheComposeBrief() {
        String brief = UnitBriefWriter.unitBrief(unit(2, 0, 40), true, BriefLabels.DE);
        assertEquals(0, linesWith(brief, "GELABER"),
                "chatter is room-sheet material — the compose window never sees it");
        assertEquals(2, linesWith(brief, "MENTION"));
    }

    @Test
    void mentionsSurviveHeavyChatter() {
        // Each row was 0 mentions before the split.
        assertEquals(8, linesWith(UnitBriefWriter.unitBrief(unit(8, 16, 150), true, BriefLabels.DE), "MENTION"));
        assertEquals(20, linesWith(UnitBriefWriter.unitBrief(unit(20, 40, 300), true, BriefLabels.DE), "MENTION"));
        assertEquals(30, linesWith(UnitBriefWriter.unitBrief(unit(30, 60, 300), true, BriefLabels.DE), "MENTION"));
    }

    @Test
    void contextTakesTheRemainderAndSaysWhatItDropped() {
        // 8 mentions leave room for all 16 chain refs; 30 mentions do not.
        String small = UnitBriefWriter.unitBrief(unit(8, 16, 0), true, BriefLabels.DE);
        assertEquals(16, linesWith(small, "ANCESTOR"), "a cheap subject keeps its full context");
        assertFalse(small.contains("ältere weggelassen"), "nothing was dropped, so nothing is claimed");

        String big = UnitBriefWriter.unitBrief(unit(30, 60, 0), true, BriefLabels.DE);
        long shown = linesWith(big, "ANCESTOR");
        assertTrue(shown > 0 && shown < 60, "context is squeezed, not erased: " + shown);
        assertTrue(big.contains((60 - shown) + " ältere weggelassen"),
                "a cut context block must name its own omission — no silent caps");
    }

    @Test
    void moodStillReachesTheRoomSheetInbox() {
        SubjectUnit u = unit(8, 16, 150);
        List<SubjectUnit.EvidenceRef> inbox = u.evidenceSince(0);
        assertEquals(174, inbox.size(), "the sheet's inbox holds every population, chatter included");
        assertEquals(8, inbox.stream().filter(SubjectUnit.EvidenceRef::isStory).count());
        assertEquals(150, inbox.stream().filter(SubjectUnit.EvidenceRef::isMood).count());
    }

    @Test
    void moodIsNotAStoryAndContextIsNotMood() {
        long now = Instant.now().getEpochSecond();
        assertTrue(new SubjectUnit.EvidenceRef("t", "c", "x", SubjectUnit.EvidenceRef.ROOM, now).isStory());
        assertTrue(new SubjectUnit.EvidenceRef("t", "c", "x", SubjectUnit.EvidenceRef.VISION, now).isStory());
        assertFalse(new SubjectUnit.EvidenceRef("t", "c", "x", SubjectUnit.EvidenceRef.CONTEXT, now).isStory());
        assertFalse(new SubjectUnit.EvidenceRef("t", "c", "x", SubjectUnit.EvidenceRef.CONTEXT, now).isMood());
        // The one that decides whether chatter can wake a unit for a headline.
        assertFalse(new SubjectUnit.EvidenceRef("t", "c", "x", SubjectUnit.EvidenceRef.MOOD, now).isStory());
        assertTrue(new SubjectUnit.EvidenceRef("t", "c", "x", SubjectUnit.EvidenceRef.MOOD, now).isMood());
    }
}
