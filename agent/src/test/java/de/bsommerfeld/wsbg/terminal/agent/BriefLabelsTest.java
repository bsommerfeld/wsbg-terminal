package de.bsommerfeld.wsbg.terminal.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BriefLabelsTest {

    @Test
    void of_picksGermanOnlyForDe() {
        assertSame(BriefLabels.DE, BriefLabels.of("de"));
        assertSame(BriefLabels.DE, BriefLabels.of("DE"));
        assertSame(BriefLabels.EN, BriefLabels.of("en"));
        assertSame(BriefLabels.EN, BriefLabels.of("fr"));
        assertSame(BriefLabels.EN, BriefLabels.of(null));
    }

    @Test
    void englishLabelsMatchTheHistoricStrings() {
        // The no-language builder overloads and the brief tests rely on these verbatim.
        assertEquals("=== SUBJECT: NVIDIA (NVDA) ===\n", BriefLabels.EN.subjectHeader("NVIDIA", "NVDA"));
        assertEquals("5m ago", BriefLabels.EN.ago("5m"));
        assertTrue(BriefLabels.EN.caseId("t3_1").startsWith("--- CASE ID: "));
        assertEquals("Active Threads: ", BriefLabels.EN.activeThreads());
    }

    @Test
    void germanLabelsAreLocalised() {
        assertEquals("=== THEMA: NVIDIA (NVDA) ===\n", BriefLabels.DE.subjectHeader("NVIDIA", "NVDA"));
        assertEquals("vor 5m", BriefLabels.DE.ago("5m"));
        assertTrue(BriefLabels.DE.caseId("t3_1").startsWith("--- FALL-ID: "));
        assertEquals("Aktive Threads: ", BriefLabels.DE.activeThreads());
    }
}
