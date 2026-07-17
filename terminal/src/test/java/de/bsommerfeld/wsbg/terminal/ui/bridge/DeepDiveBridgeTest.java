package de.bsommerfeld.wsbg.terminal.ui.bridge;

import de.bsommerfeld.wsbg.terminal.agent.event.DeepDiveJournalEvent;
import de.bsommerfeld.wsbg.terminal.agent.event.DeepDiveLiveEvent;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The live workspace feed's wire mapping ({@link DeepDiveBridge#liveJson}):
 * compact keys, empties omitted, the diff attachment as journal-line maps.
 * The push/backlog wiring is exercised live; here we pin the pure shape.
 */
class DeepDiveBridgeTest {

    @Test
    void liveJsonCarriesTheFullEntry() {
        var entry = new DeepDiveLiveEvent.Entry("note", "sections", "diffjudge", 2, 3,
                "E: \"Der Umsatz sinkt\" — die Quelle meldet einen Anstieg.",
                List.of(new DeepDiveJournalEvent.Line("add", "Der Umsatz steigt.", 0, 4)));
        Map<String, Object> m = DeepDiveBridge.liveJson(entry, 7L);
        assertEquals(7L, m.get("seq"));
        assertEquals("note", m.get("k"));
        assertEquals("sections", m.get("ph"));
        assertEquals("diffjudge", m.get("who"));
        assertEquals(2, m.get("sec"));
        assertEquals(3, m.get("par"));
        assertTrue(((String) m.get("t")).contains("die Quelle meldet einen Anstieg"));
        @SuppressWarnings("unchecked")
        var diff = (List<Map<String, Object>>) m.get("diff");
        assertEquals(1, diff.size());
        assertEquals("add", diff.get(0).get("k"));
        assertEquals("Der Umsatz steigt.", diff.get(0).get("t"));
        assertEquals(4, diff.get(0).get("n"));
        assertFalse(diff.get(0).containsKey("o"));
    }

    @Test
    void liveJsonOmitsEmptyFields() {
        var entry = new DeepDiveLiveEvent.Entry("settled", "sections", null, 4, 0, "", List.of());
        Map<String, Object> m = DeepDiveBridge.liveJson(entry, 1L);
        assertEquals("settled", m.get("k"));
        assertEquals(4, m.get("sec"));
        assertFalse(m.containsKey("who"));
        assertFalse(m.containsKey("par"));
        assertFalse(m.containsKey("t"));
        assertFalse(m.containsKey("diff"));
        // Not section-bound (-1) stays off the wire entirely.
        var unbound = new DeepDiveLiveEvent.Entry("chat", "reclaim", "reclaim", -1, 0,
                "Nachlese: Titel", null);
        assertFalse(DeepDiveBridge.liveJson(unbound, 2L).containsKey("sec"));
    }

    @Test
    void liveJsonCarriesTheTriageBoardRef() {
        var entry = new DeepDiveLiveEvent.Entry("src-out", "triage", "triage", -1, 0,
                "Rheinmetall: Kursziel angehoben · dpa-AFX", List.of(),
                "news:https://example.org/a?b=1");
        Map<String, Object> m = DeepDiveBridge.liveJson(entry, 3L);
        assertEquals("src-out", m.get("k"));
        assertEquals("news:https://example.org/a?b=1", m.get("ref"));
        assertFalse(m.containsKey("sec"));
        // The no-ref convenience constructor stays off the wire.
        var plain = new DeepDiveLiveEvent.Entry("settled", "sections", null, 1, 0, "", List.of());
        assertFalse(DeepDiveBridge.liveJson(plain, 4L).containsKey("ref"));
    }
}
