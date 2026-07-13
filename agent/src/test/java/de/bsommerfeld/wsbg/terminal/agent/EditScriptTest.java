package de.bsommerfeld.wsbg.terminal.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The KI-DD's surgical edit protocol (user mandate 2026-07-13): the integrate
 * and final passes emit operations, never the whole report — these tests pin
 * the parser (sentinel blocks, chatter tolerance, missing terminators) and the
 * applier (replace/insert/delete, whitespace-tolerant anchors, skip-on-miss).
 */
class EditScriptTest {

    private static final String REPORT = """
            ## Worum es geht
            Die Firma baut Dinge. Sie verkauft sie weltweit. [1]

            ## These
            (Dieser Abschnitt folgt mit dem nächsten Materialpaket.)

            ## Lage
            Der Kurs stieg am 2026-07-10 um 3 Prozent. [1]""";

    @Test
    void replaceSwapsThePlaceholderForProse() {
        EditScript s = EditScript.parse("""
                <<<REPLACE
                (Dieser Abschnitt folgt mit dem nächsten Materialpaket.)
                <<<WITH
                Das Material macht einen bullishen Fall mit zwei Argumenten. [2]
                <<<END""");
        assertEquals(1, s.size());
        EditScript.Result r = s.apply(REPORT);
        assertEquals(1, r.applied());
        assertTrue(r.text().contains("bullishen Fall mit zwei Argumenten. [2]"));
        assertFalse(r.text().contains("Materialpaket"));
        // Untouched sections survive verbatim.
        assertTrue(r.text().contains("Die Firma baut Dinge. Sie verkauft sie weltweit. [1]"));
    }

    @Test
    void insertAfterHeadingAndAfterSentence() {
        EditScript s = EditScript.parse("""
                <<<INSERT-AFTER
                ## Lage
                <<<WITH
                Neu eröffnender Absatz zur Lage. [3]
                <<<END
                <<<INSERT-AFTER
                Der Kurs stieg am 2026-07-10 um 3 Prozent. [1]
                <<<WITH
                Am 2026-07-12 kam ein Rücksetzer. [3]
                <<<END""");
        EditScript.Result r = s.apply(REPORT);
        assertEquals(2, r.applied());
        // After a full line → own paragraph; after a sentence at line end too.
        assertTrue(r.text().contains("## Lage\n\nNeu eröffnender Absatz zur Lage. [3]"));
        assertTrue(r.text().contains("[1]\n\nAm 2026-07-12 kam ein Rücksetzer. [3]"));
    }

    @Test
    void deleteRemovesAndCollapsesBlankLines() {
        EditScript s = EditScript.parse("""
                <<<DELETE
                Sie verkauft sie weltweit.
                <<<END""");
        EditScript.Result r = s.apply(REPORT);
        assertEquals(1, r.applied());
        assertFalse(r.text().contains("verkauft"));
        assertFalse(r.text().contains("\n\n\n"), "blank lines must collapse");
    }

    @Test
    void whitespaceDriftInTheAnchorStillMatches() {
        EditScript s = EditScript.parse("""
                <<<REPLACE
                Die Firma  baut
                Dinge.
                <<<WITH
                Die Firma baut Raketen.
                <<<END""");
        EditScript.Result r = s.apply(REPORT);
        assertEquals(1, r.applied(), String.join("; ", r.failures()));
        assertTrue(r.text().contains("Die Firma baut Raketen. Sie verkauft sie weltweit."));
    }

    @Test
    void unknownAnchorIsSkippedNeverFatal() {
        EditScript s = EditScript.parse("""
                <<<REPLACE
                Diesen Satz gibt es nicht.
                <<<WITH
                Egal.
                <<<END
                <<<INSERT-AFTER
                ## These
                <<<WITH
                Trotzdem angekommen. [2]
                <<<END""");
        EditScript.Result r = s.apply(REPORT);
        assertEquals(1, r.applied());
        assertEquals(1, r.failures().size());
        assertTrue(r.text().contains("Trotzdem angekommen. [2]"));
    }

    @Test
    void chatterAndMissingTerminatorAreTolerated() {
        // Prose around the blocks is ignored; a block cut off by the output end
        // still parses; a fenced reply parses after the caller's cleanReport.
        EditScript s = EditScript.parse("""
                Hier sind die Änderungen:
                <<<INSERT-AFTER
                ## These
                <<<WITH
                Absatz eins. [2]""");
        assertEquals(1, s.size());
        assertEquals(1, s.apply(REPORT).applied());
    }

    @Test
    void noopIsExplicitAndDistinctFromWhiff() {
        assertTrue(EditScript.parse("<<<NOOP").isNoop());
        assertTrue(EditScript.parse("<<<NOOP").isEmpty());
        assertFalse(EditScript.parse("irgendein Prosa-Gebrabbel").isNoop());
        assertTrue(EditScript.parse("irgendein Prosa-Gebrabbel").isEmpty());
        // NOOP beside real ops is not a noop — the ops count.
        EditScript mixed = EditScript.parse("""
                <<<NOOP
                <<<DELETE
                Sie verkauft sie weltweit.
                <<<END""");
        assertFalse(mixed.isNoop());
        assertEquals(1, mixed.size());
    }

    @Test
    void tickerShapeGate() {
        assertTrue(DeepDiveService.looksLikeTicker("RHM.DE"));
        assertTrue(DeepDiveService.looksLikeTicker("005930.KS"));
        assertTrue(DeepDiveService.looksLikeTicker("BRK-B"));
        assertTrue(DeepDiveService.looksLikeTicker("^GDAXI"));
        assertTrue(DeepDiveService.looksLikeTicker("CC=F"));
        assertTrue(DeepDiveService.looksLikeTicker("BTC-USD"));
        assertFalse(DeepDiveService.looksLikeTicker("Rheinmetall AG"), "names are rejected");
        assertFalse(DeepDiveService.looksLikeTicker("SAP SE"));
        assertFalse(DeepDiveService.looksLikeTicker(""));
        assertFalse(DeepDiveService.looksLikeTicker("^^^"), "needs at least one letter/digit");
        assertFalse(DeepDiveService.looksLikeTicker("VERYLONGTICKER123"), "over 15 chars");
    }
}
