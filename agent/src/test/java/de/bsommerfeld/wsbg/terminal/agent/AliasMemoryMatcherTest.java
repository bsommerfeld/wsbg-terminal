package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.instruments.AliasStore;
import de.bsommerfeld.wsbg.terminal.instruments.CorpusSource;
import de.bsommerfeld.wsbg.terminal.instruments.InstrumentCorpus;
import de.bsommerfeld.wsbg.terminal.instruments.InstrumentEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stage that finally READS the learned memory. Before it existed the store was
 * written on every verdict and opened by nobody — a diary the app kept and never
 * consulted.
 */
class AliasMemoryMatcherTest {

    @TempDir
    Path dir;

    private InstrumentCorpus corpus() {
        InstrumentCorpus c = new InstrumentCorpus(dir.resolve("instruments.jsonl"),
                List.of(new CorpusSource() {
                    @Override
                    public String name() {
                        return "fixture";
                    }

                    @Override
                    public List<InstrumentEntry> fetch() {
                        return List.of(new InstrumentEntry("RHM.DE", "Rheinmetall AG",
                                "DE0007030009", "DE", "EQUITY", "xetra"));
                    }
                }));
        c.refresh();
        return c;
    }

    /** A ledger holding {@code n} postings for one reading, spread past the hourly grain. */
    private AliasStore ledger(String line, int n) throws Exception {
        Path f = dir.resolve("aliases.jsonl");
        long base = System.currentTimeMillis() / 1000 - (long) n * 3600;
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            lines.add(line.replace("$AT", String.valueOf(base + i * 3601L)));
        }
        Files.write(f, lines);
        return new AliasStore(f);
    }

    private static MatchContext ctx(String query) {
        return new MatchContext(query, "irgendein Thread", List.of(), List.of());
    }

    @Test
    void aSpellingTheRoomTaughtUsIsAnsweredFromMemory() throws Exception {
        AliasStore store = ledger(
                "{\"name\":\"rheiner\",\"symbol\":\"RHM.DE\",\"at\":$AT,\"tier\":\"DeskMatcher\"}", 5);
        AliasMemoryMatcher m = new AliasMemoryMatcher(() -> store, this::corpus);

        Optional<SubjectMatch> match = m.match(ctx("Rheiner"));

        assertTrue(match.isPresent(), "this is what replaced the hand-kept glossary entry");
        assertEquals("RHM.DE", match.get().symbol());
        assertEquals("Rheinmetall AG", match.get().canonicalName(), "the corpus supplies the real name");
    }

    @Test
    void withoutACorpusRowTheRoomsSpellingIsTheName_neverTheSymbol() throws Exception {
        // The old fallback was `name = symbol`, which shipped "675054 (675054)" and
        // "BE (BE)" units whose canonical name no sentence can carry (2026-08-13).
        // (A bare-WKN reading itself is muted outright these days — see the shape
        // hygiene — so this uses a clean venue symbol without a corpus row.)
        AliasStore store = ledger(
                "{\"name\":\"vontobel\",\"symbol\":\"VONN.SW\",\"at\":$AT,\"tier\":\"DeskMatcher\"}", 5);
        AliasMemoryMatcher m = new AliasMemoryMatcher(() -> store, this::corpus);

        Optional<SubjectMatch> match = m.match(ctx("Vontobel"));

        assertTrue(match.isPresent());
        assertEquals("VONN.SW", match.get().symbol());
        assertEquals("Vontobel", match.get().canonicalName(),
                "the query spelling is the display name when the corpus has no row");
    }

    @Test
    void aLoneVerdictIsNotAllowedToAnswerForItself() {
        // The live ledger holds exactly this shape: a single judge call that put the
        // key figure „EPS" on a Japanese printer maker. One posting must never
        // short-circuit the tower, or that mistake re-confirms itself forever.
        AliasStore store = new AliasStore(dir.resolve("aliases.jsonl"));
        store.learn("eps", "6724.T");
        AliasMemoryMatcher m = new AliasMemoryMatcher(() -> store, this::corpus);

        assertTrue(m.match(ctx("eps")).isEmpty(), "one posting is a data point, not a memory");
    }

    @Test
    void aContestedSpellingIsHandedOnToTheStagesThatCanInvestigate() throws Exception {
        Path f = dir.resolve("aliases.jsonl");
        long base = System.currentTimeMillis() / 1000 - 12 * 3600;
        List<String> lines = new ArrayList<>();
        // Two clean readings of comparable strength. (The historical pair used
        // 0O8X.IL, which the shape hygiene now mutes on load as a numeric-prefixed
        // western secondary — the contest itself is what this test is about.)
        for (int i = 0; i < 5; i++) {
            lines.add("{\"name\":\"wirecard ag\",\"symbol\":\"WDI.DE\",\"at\":" + (base + i * 3601L) + "}");
            lines.add("{\"name\":\"wirecard ag\",\"symbol\":\"WDI.HM\",\"at\":" + (base + i * 3601L) + "}");
        }
        Files.write(f, lines);
        AliasMemoryMatcher m = new AliasMemoryMatcher(() -> new AliasStore(f), this::corpus);

        assertTrue(m.match(ctx("Wirecard AG")).isEmpty(),
                "two live readings mean the question needs context this file has not got");
    }

    @Test
    void aFadedSpellingIsNoLongerAnAnswer() throws Exception {
        Path f = dir.resolve("aliases.jsonl");
        long old = System.currentTimeMillis() / 1000 - 40L * 24 * 3600;
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            lines.add("{\"name\":\"rheiner\",\"symbol\":\"RHM.DE\",\"at\":" + (old + i * 3601L) + "}");
        }
        Files.write(f, lines);
        AliasMemoryMatcher m = new AliasMemoryMatcher(() -> new AliasStore(f), this::corpus);

        assertTrue(m.match(ctx("Rheiner")).isEmpty(), "unused, therefore unlearned");
    }

    @Test
    void arememberedNoIsNotAClaim() throws Exception {
        AliasStore store = ledger("{\"name\":\"hopium\",\"none\":true,\"at\":$AT}", 5);
        AliasMemoryMatcher m = new AliasMemoryMatcher(() -> store, this::corpus);

        assertTrue(m.match(ctx("Hopium")).isEmpty(),
                "the negative is the coinage stage's business, not a claim on the subject");
    }

    @Test
    void withoutAStoreItSimplyAbstains() {
        assertTrue(new AliasMemoryMatcher(() -> null, this::corpus).match(ctx("Rheiner")).isEmpty());
    }
}
