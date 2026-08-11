package de.bsommerfeld.wsbg.terminal.instruments;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The near-miss lookup — the one thing the exact-token search structurally cannot
 * do, and the reason the room's own words were invisible to the resolver.
 */
class NearestNameTest {

    private static InstrumentCorpus corpusOf(Path dir, InstrumentEntry... entries) {
        InstrumentCorpus c = new InstrumentCorpus(dir.resolve("instruments.jsonl"),
                List.of(new CorpusSource() {
                    @Override
                    public String name() {
                        return "fixture";
                    }

                    @Override
                    public List<InstrumentEntry> fetch() {
                        return List.of(entries);
                    }
                }));
        c.refresh();
        return c;
    }

    private static InstrumentCorpus cage(Path dir) {
        return corpusOf(dir,
                new InstrumentEntry("RHM.DE", "Rheinmetall AG", "DE0007030009", "DE", "EQUITY", "xetra"),
                new InstrumentEntry("SPCX", "SpaceX", null, "US", "EQUITY", "sec"),
                new InstrumentEntry("NVDA", "NVIDIA Corporation", null, "US", "EQUITY", "sec"),
                new InstrumentEntry("AAPL", "Apple Inc.", null, "US", "EQUITY", "sec"),
                new InstrumentEntry("RWE.DE", "RWE AG", "DE0007037129", "DE", "EQUITY", "xetra"));
    }

    @Test
    void aCorruptedNameIsInvisibleToTheExactSearch(@TempDir Path dir) {
        // The finding that made this whole mechanism necessary: the room's word shares
        // no whole token with the name it corrupts, so every earlier stage is blind.
        assertTrue(cage(dir).search("Keinmetall", 5).isEmpty());
    }

    @Test
    void theRoomsCorruptionsAreFoundAsNearMisses(@TempDir Path dir) {
        InstrumentCorpus c = cage(dir);
        assertEquals("RHM.DE", c.nearest("Keinmetall", 3).get(0).symbol());
        assertEquals("SPCX", c.nearest("SpaceEx", 3).get(0).symbol());
        assertEquals("SPCX", c.nearest("SpaceSex", 3).get(0).symbol());
        assertEquals("NVDA", c.nearest("Nvidea", 3).get(0).symbol());
    }

    @Test
    void anOrdinaryWordDragsNothingIn(@TempDir Path dir) {
        InstrumentCorpus c = cage(dir);
        // The whole risk of a distance-based proposal is that everything looks a little
        // like everything. The floor has to hold, or the judge drowns in noise.
        assertTrue(c.nearest("Zinsentscheidung", 5).isEmpty());
        assertTrue(c.nearest("Hopium", 5).isEmpty());
        assertTrue(c.nearest("Montag", 5).isEmpty());
    }

    @Test
    void tooShortToJudgeIsLeftAlone(@TempDir Path dir) {
        assertTrue(cage(dir).nearest("RWE", 5).isEmpty(), "three letters carry no distance signal");
    }

    @Test
    void itProposesRatherThanDecides(@TempDir Path dir) {
        List<InstrumentEntry> hits = cage(dir).nearest("Keinmetall", 5);
        assertFalse(hits.isEmpty());
        assertTrue(hits.size() <= 5, "a bounded candidate list for the judge, not an answer");
    }

    @Test
    void similarityIsSymmetricAndBounded() {
        assertEquals(1.0, InstrumentIndex.similarity("rheinmetall", "rheinmetall"));
        assertEquals(InstrumentIndex.similarity("keinmetall", "rheinmetall"),
                InstrumentIndex.similarity("rheinmetall", "keinmetall"));
        assertTrue(InstrumentIndex.similarity("keinmetall", "rheinmetall") > 0.8);
        assertTrue(InstrumentIndex.similarity("apple", "rheinmetall") < 0.3);
    }
}
