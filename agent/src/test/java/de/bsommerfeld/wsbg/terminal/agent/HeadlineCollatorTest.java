package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.agent.HeadlineCollator.Decision;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Collation logic, unit-tested through the {@link EmbeddingService} seam with a
 * deterministic fake — NO Ollama, runs in plain {@code mvn test}. This is the
 * point of the abstraction: the windowing + replace-in-place behaviour is verified
 * without the model. Similarities that need to straddle the threshold precisely are
 * pinned, so the test asserts the LOGIC, not the fake's heuristic scale.
 */
class HeadlineCollatorTest {

    @Test
    void similarHeadlineReplacesTheEarlierRowInPlace() {
        String a = "NVIDIA fällt -5% — die Apes sehen rot";
        String b = "NVIDIA -5% — die Apes sehen tiefrot";
        FakeEmbeddingService fake = new FakeEmbeddingService().pin(a, b, 0.93);
        HeadlineCollator c = new HeadlineCollator(fake);

        Decision first = c.offer("NVDA", a);
        assertFalse(first.collated());

        Decision second = c.offer("name:nvidia", b);
        assertTrue(second.collated(), "same-story line should collate");
        assertEquals(first.id(), second.id(), "it takes over the earlier row's id");
        assertEquals(a, second.replacedText());
    }

    @Test
    void distinctHeadlinesEachGetTheirOwnRow() {
        FakeEmbeddingService fake = new FakeEmbeddingService();
        HeadlineCollator c = new HeadlineCollator(fake);

        Decision a = c.offer("NVDA", "NVIDIA fällt heute kräftig");
        Decision b = c.offer("AAPL", "Apple steigt auf neues Allzeithoch");
        assertFalse(a.collated());
        assertFalse(b.collated());
        assertFalse(a.id().equals(b.id()));
    }

    @Test
    void onlyTheLastFiveRowsAreReplaceable() {
        // A near-duplicate of a line that has scrolled out of the editable window
        // becomes a NEW row (you can't rewrite history off-screen).
        FakeEmbeddingService fake = new FakeEmbeddingService();
        HeadlineCollator c = new HeadlineCollator(fake);

        c.offer("a", "Rheinmetall springt nach Auftrag");      // row 1 — will be pushed out
        c.offer("b", "Bitcoin verliert massiv an Wert");
        c.offer("c", "Gold erreicht historisches Hoch");
        c.offer("d", "Lufthansa streikt erneut diese Woche");
        c.offer("e", "Bayer gewinnt wichtigen Prozess");
        c.offer("f", "TUI meldet starke Buchungszahlen");      // 5 distinct → row 1 evicted

        Decision d = c.offer("a2", "Rheinmetall springt nach Auftrag"); // identical to the evicted row
        assertFalse(d.collated(), "the matching row scrolled out of the window");
    }

    @Test
    void pinnedSimilarityDrivesTheDecisionExactly() {
        FakeEmbeddingService fake = new FakeEmbeddingService()
                .pin("Gold glänzt", "Bitcoin crasht", 0.95); // force unrelated texts to "collide"
        HeadlineCollator c = new HeadlineCollator(fake);
        c.offer("GLD", "Gold glänzt");
        Decision d = c.offer("BTC", "Bitcoin crasht");
        assertTrue(d.collated(), "pinned high similarity must collate regardless of words");
    }
}
