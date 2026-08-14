package de.bsommerfeld.wsbg.terminal.agent;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The unit's rolling fact sheet: distilled room knowledge that survives the
 * evidence prune. Facts append with a line cap, the watermark is monotonic and
 * splits the evidence inbox from the absorbed story, the sheet rides the
 * session snapshot, and the brief renders it as known context while omitting
 * the raw evidence it already condensed.
 */
class FactSheetTest {

    private static SubjectUnit.EvidenceRef ev(String comment, long epoch) {
        return new SubjectUnit.EvidenceRef("t1", comment, "Die Affen kaufen Testwerk bei 42 Euro.",
                "reddit", epoch);
    }

    @Test
    void appendFactsCapsTheSheetAndAdvancesTheWatermarkMonotonically() {
        SubjectUnit u = new SubjectUnit("TST", "Testwerk");
        for (int i = 0; i < SubjectUnit.MAX_FACT_LINES + 4; i++) {
            u.appendFacts(List.of("Fakt " + i), i + 1);
        }
        assertEquals(SubjectUnit.MAX_FACT_LINES, u.factSheet().size());
        assertEquals("Fakt 4", u.factSheet().get(0).text(), "oldest lines fall off first");
        long mark = u.factsUpToEpoch();
        u.appendFacts(List.of(), mark - 5); // a stale stamp must not move it backwards
        assertEquals(mark, u.factsUpToEpoch());
        u.appendFacts(List.of(), mark + 5); // empty lines still absorb the inbox
        assertEquals(mark + 5, u.factsUpToEpoch());
        assertEquals(SubjectUnit.MAX_FACT_LINES, u.factSheet().size());
    }

    @Test
    void evidenceSinceSplitsTheInboxAtTheWatermark() {
        SubjectUnit u = new SubjectUnit("TST", "Testwerk");
        u.addEvidence(ev("c1", 100));
        u.addEvidence(ev("c2", 200));
        u.appendFacts(List.of("Der Raum kauft."), 100);
        List<SubjectUnit.EvidenceRef> inbox = u.evidenceSince(u.factsUpToEpoch());
        assertEquals(1, inbox.size());
        assertEquals("c2", inbox.get(0).commentId());
    }

    @Test
    void factSheetSurvivesTheSessionSnapshotRoundtrip() {
        SubjectUnit u = new SubjectUnit("TST", "Testwerk");
        u.addEvidence(ev("c1", 100));
        u.appendFacts(List.of("Der Raum nennt ein Kursziel von 50 Euro."), 100);
        SubjectUnit restored = new SubjectUnit(u.toSnapshot());
        assertEquals(1, restored.factSheet().size());
        assertEquals("Der Raum nennt ein Kursziel von 50 Euro.", restored.factSheet().get(0).text());
        assertEquals(100, restored.factsUpToEpoch());
    }

    @Test
    void restoreToleratesAPreFactSheetSnapshot() {
        SubjectUnit.Snapshot old = new SubjectUnit.Snapshot("TST", "Testwerk", null, null,
                0, 0, null, null, null, List.of(), List.of(), List.of(), null, null);
        SubjectUnit restored = new SubjectUnit(old);
        assertTrue(restored.factSheet().isEmpty());
        assertEquals(0, restored.factsUpToEpoch());
    }

    @Test
    void briefRendersTheSheetAsKnownContextAndOmitsAbsorbedEvidenceRaw() {
        long now = Instant.now().getEpochSecond();
        SubjectUnit u = new SubjectUnit("TST", "Testwerk");
        u.addEvidence(ev("c1", now - 600));
        u.addEvidence(ev("c2", now - 10));
        u.appendFacts(List.of("Der Raum kauft Testwerk bei 42 Euro."), now - 600);

        String brief = UnitBriefWriter.unitBrief(u, true, BriefLabels.of("de"), null);
        assertTrue(brief.contains("RAUM-BLATT"), "the room-sheet block must render");
        assertTrue(brief.contains("Der Raum kauft Testwerk bei 42 Euro."));
        assertTrue(brief.contains("ins Raum-Blatt oben verdichtet"),
                "absorbed raw evidence is omitted with the room-sheet label");
        // The absorbed mention is gone raw, the fresh one still shows (the brief
        // renders each raw mention under its comment-id location tag).
        assertFalse(brief.contains("[c1,"), "absorbed evidence must not render raw");
        assertTrue(brief.contains("[c2,"), "the un-absorbed fresh mention still renders raw");
    }

    @Test
    void parseFactsStripsBulletsDropsEmptyVerdictAndCaps() {
        assertEquals(List.of(), FactSheetUpdater.parseFacts(null));
        assertEquals(List.of(), FactSheetUpdater.parseFacts("EMPTY"));
        assertEquals(List.of(), FactSheetUpdater.parseFacts("  empty  \n\n"));
        List<String> facts = FactSheetUpdater.parseFacts(
                "- Der Raum kauft.\n2) Kursziel 50 Euro genannt.\n• Dritter Fakt.\nVierter Fakt.");
        assertEquals(List.of("Der Raum kauft.", "Kursziel 50 Euro genannt.", "Dritter Fakt."),
                facts, "bullets/numbering stripped, capped at three");
        String runaway = "x".repeat(500);
        assertEquals(241, FactSheetUpdater.parseFacts(runaway).get(0).length(),
                "a runaway line is truncated with an ellipsis");
    }
}
