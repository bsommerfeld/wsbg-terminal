package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.instruments.AliasProvenance;
import de.bsommerfeld.wsbg.terminal.instruments.AliasStore;
import de.bsommerfeld.wsbg.terminal.instruments.CorpusSource;
import de.bsommerfeld.wsbg.terminal.instruments.InstrumentCorpus;
import de.bsommerfeld.wsbg.terminal.instruments.InstrumentEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The stage that reads the room's own words — the replacement for the hand-kept
 * nickname glossary. What is asserted here is the behaviour that glossary could
 * never have: learning the nickname nobody typed in.
 */
class CoinageMatcherTest {

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
                        return List.of(
                                new InstrumentEntry("RHM.DE", "Rheinmetall AG", "DE0007030009",
                                        "DE", "EQUITY", "xetra"),
                                new InstrumentEntry("LHA.DE", "Deutsche Lufthansa AG", "DE0008232125",
                                        "DE", "EQUITY", "xetra"),
                                new InstrumentEntry("NVDA", "NVIDIA Corporation", null,
                                        "US", "EQUITY", "sec"));
                    }
                }));
        c.refresh();
        return c;
    }

    private AliasStore store() {
        return new AliasStore(dir.resolve("aliases.jsonl"));
    }

    /** A judge that always takes the first candidate offered. */
    private static TickerResolver.MatchJudge firstPick(List<List<String>> seen, AtomicInteger calls) {
        return (subject, context, candidates) -> {
            seen.add(candidates);
            calls.incrementAndGet();
            return 0;
        };
    }

    /** A judge that refuses everything — the fail-closed default. */
    private static TickerResolver.MatchJudge refuses(AtomicInteger calls) {
        return (subject, context, candidates) -> {
            calls.incrementAndGet();
            return -1;
        };
    }

    @Test
    void theRoomsCorruptionIsResolvedToWhatItCorrupts() {
        AtomicInteger calls = new AtomicInteger();
        List<List<String>> seen = new ArrayList<>();
        CoinageMatcher m = new CoinageMatcher(() -> firstPick(seen, calls), this::corpus, this::store);

        InstrumentEntry picked = m.pick(new MatchContext(
                "Keinmetall", "Keinmetall macht mich arm", List.of(), List.of()));

        assertNotNull(picked);
        assertEquals("RHM.DE", picked.symbol());
        assertEquals(1, calls.get());
        assertTrue(seen.get(0).get(0).contains("Rheinmetall"),
                "the near miss must be on the judge's list");
    }

    @Test
    void theThreadsOwnSubjectsAreNoLongerOfferedAsCandidates() {
        // The neighbour channel was the theme-word trap (2026-08-13): in an oil
        // thread it made „Hormuz" and „Midterms" into the oil paper, because a 4B
        // judge shown a plausible instrument next to an unplaceable word takes the
        // instrument. A word with NO orthographic near miss now yields no candidates
        // at all — no judge call, no claim — even when the thread has resolved
        // subjects to offer.
        AtomicInteger calls = new AtomicInteger();
        List<List<String>> seen = new ArrayList<>();
        CoinageMatcher m = new CoinageMatcher(() -> firstPick(seen, calls), this::corpus, this::store);

        InstrumentEntry picked = m.pick(new MatchContext(
                "Hormuz", "Öl Long wegen Hormuz", List.of(), List.of("Deutsche Lufthansa AG")));

        assertNull(picked, "no near miss → no candidates → no claim");
        assertEquals(0, calls.get(), "and no judge call to pay for");
    }

    @Test
    void theOrthographicChannelAloneFeedsTheJudge() {
        // A corruption with a real near miss still reaches the judge — but the
        // thread's resolved subjects no longer ride along on the list.
        AtomicInteger calls = new AtomicInteger();
        List<List<String>> seen = new ArrayList<>();
        CoinageMatcher m = new CoinageMatcher(() -> firstPick(seen, calls), this::corpus, this::store);

        m.pick(new MatchContext("Keinmetall", "Lufthansa und der ganze Rest",
                List.of(), List.of("Deutsche Lufthansa AG")));

        assertEquals(1, calls.get(), "exactly one call for the near-miss candidates");
        String offered = String.join(" | ", seen.get(0));
        assertTrue(offered.contains("Rheinmetall"), "the near miss is on the list");
        assertFalse(offered.contains("Lufthansa"),
                "the thread neighbour is NOT — that channel was the theme-word trap");
    }

    @Test
    void aRefusedSpellingIsBookedAsAConsideredNo() {
        AtomicInteger calls = new AtomicInteger();
        AliasStore store = store();
        CoinageMatcher m = new CoinageMatcher(() -> refuses(calls), this::corpus, () -> store);

        assertNull(m.pick(new MatchContext("Keinmetall", "irgendwas", List.of(), List.of())));
        assertTrue(store.ledgerFor("Keinmetall").get(0).isNone(),
                "the considered no is what stops the re-judging");
    }

    @Test
    void aSettledNoIsNotPaidForTwice() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        // Four postings spread across the ledger's hourly grain: a no it actually trusts.
        Path f = dir.resolve("aliases.jsonl");
        long base = System.currentTimeMillis() / 1000 - 4 * 3600;
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            lines.add("{\"name\":\"keinmetall\",\"none\":true,\"at\":" + (base + i * 3601L) + "}");
        }
        java.nio.file.Files.write(f, lines);
        AliasStore store = new AliasStore(f);
        CoinageMatcher m = new CoinageMatcher(() -> refuses(calls), this::corpus, () -> store);

        assertNull(m.pick(new MatchContext("Keinmetall", "irgendwas", List.of(), List.of())));
        assertEquals(0, calls.get(), "the model must not be asked a settled question again");
    }

    @Test
    void theSameCoinageCostsExactlyOneJudgeCall() {
        AtomicInteger calls = new AtomicInteger();
        List<List<String>> seen = new ArrayList<>();
        CoinageMatcher m = new CoinageMatcher(() -> firstPick(seen, calls), this::corpus, this::store);
        MatchContext ctx = new MatchContext("Keinmetall", "…", List.of(), List.of());

        m.pick(ctx);
        m.pick(ctx);
        m.pick(ctx);

        assertEquals(1, calls.get(), "one call per novel spelling, ever — then it is memory");
    }

    @Test
    void anOrdinaryWordNeverReachesTheModel() {
        AtomicInteger calls = new AtomicInteger();
        CoinageMatcher m = new CoinageMatcher(() -> refuses(calls), this::corpus, this::store);

        assertNull(m.pick(new MatchContext("Zinsentscheidung", "EZB", List.of(), List.of())));
        assertEquals(0, calls.get(), "no plausible candidate → no call, and no verdict either");
    }

    @Test
    void aSpellingTooShortToMeanAnythingIsLeftAlone() {
        AtomicInteger calls = new AtomicInteger();
        CoinageMatcher m = new CoinageMatcher(() -> refuses(calls), this::corpus, this::store);
        assertNull(m.pick(new MatchContext("OP", "…", List.of(), List.of())));
        assertEquals(0, calls.get());
    }

    @Test
    void withoutAJudgeOrCorpusItSimplyAbstains() {
        assertNull(new CoinageMatcher(() -> null, this::corpus, this::store)
                .pick(new MatchContext("Keinmetall", "…", List.of(), List.of())));
        assertNull(new CoinageMatcher(() -> refuses(new AtomicInteger()), () -> null, this::store)
                .pick(new MatchContext("Keinmetall", "…", List.of(), List.of())));
    }
}
