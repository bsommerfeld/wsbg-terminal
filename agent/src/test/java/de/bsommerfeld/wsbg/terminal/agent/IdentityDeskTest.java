package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.price.InstrumentCandidate;
import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooQuote;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The identity desk's decision semantics: claim-with-stamp, considered news-only,
 * abstain-on-error, the exact-name bypass, session/ledger replay.
 */
class IdentityDeskTest {

    @TempDir
    Path dir;

    private final AtomicReference<Gemma4Judge.DeskPick> nextPick = new AtomicReference<>();
    private final AtomicInteger judgeCalls = new AtomicInteger();

    private final IdentityDesk.PickJudge judge = (subject, context, yahooLines, lsLines) -> {
        judgeCalls.incrementAndGet();
        return nextPick.get();
    };

    private static YahooQuote yq(String symbol, String name, String type) {
        return new YahooQuote(symbol, name, name, type, "GER", "XETRA", "", "", 100.0, 1.0, 200_000.0);
    }

    private static InstrumentCandidate ls(long id, String isin, String wkn, String name,
            String cat, String catName) {
        return new InstrumentCandidate("L&S", id, isin, wkn, name, cat, catName);
    }

    private IdentityDesk desk(List<InstrumentCandidate> venue) {
        IdentityDesk d = new IdentityDesk(judge, () -> true);
        d.installLookup(q -> venue);
        return d;
    }

    private static MatchContext ctx(String query, List<YahooQuote> quotes) {
        return new MatchContext(query, "thread title", quotes);
    }

    @Test
    void bothVenuePicksStampTheMatch() {
        nextPick.set(new Gemma4Judge.DeskPick(1, 2));
        IdentityDesk d = desk(List.of(
                ls(1, "US0000000001", "A1AAAA", "WRONG TWIN CORP.", "STK", "Aktie"),
                ls(2, "DE0007030009", "703000", "RHEINMETALL AG", "STK", "Aktie")));

        SubjectMatch m = d.decide(ctx("Rheinmetall", List.of(yq("RHM.DE", "Rheinmetall AG", "EQUITY")))).orElseThrow();
        assertEquals("RHM.DE", m.symbol());
        assertEquals("DE0007030009", m.isin(), "the stamped ISIN is the JUDGED L&S pick, not list order");
        assertEquals(2L, m.venueId());
        assertEquals("STK", m.category());
        assertTrue(m.isStamped());
    }

    @Test
    void doubleZeroIsAConsideredNewsOnlyClaim() {
        nextPick.set(new Gemma4Judge.DeskPick(0, 0));
        SubjectMatch m = desk(List.of(ls(9, "PL0000000001", "", "POLENERGIA SA", "STK", "Aktie")))
                .decide(ctx("Polen", List.of(yq("PEG.WA", "Polenergia SA", "EQUITY")))).orElseThrow();
        assertNull(m.symbol(), "the judge saw all facts and struck every candidate → news-only");
        assertEquals("Polen", m.canonicalName());
    }

    @Test
    void judgeFailureAbstainsToTheLegacyTower() {
        nextPick.set(null);
        Optional<SubjectMatch> m = desk(List.of()).decide(ctx("NVIDIA", List.of(yq("NVDA", "NVIDIA Corporation", "EQUITY"))));
        assertTrue(m.isEmpty(), "a model failure is no verdict — the legacy tower must decide");
    }

    @Test
    void exactNameIsAFactAndSurvivesJudgeFailure() {
        nextPick.set(null);
        SubjectMatch m = desk(List.of())
                .decide(ctx("Lithium Americas", List.of(
                        yq("XX", "Completely Different Inc.", "EQUITY"),
                        yq("LAC", "Lithium Americas Corp.", "EQUITY"))))
                .orElseThrow();
        assertEquals("LAC", m.symbol(), "a name-equivalent candidate is confirmed mechanically");
    }

    @Test
    void exactNameOverridesTheJudgesYahooPick() {
        nextPick.set(new Gemma4Judge.DeskPick(1, 0)); // judge picks the wrong candidate
        SubjectMatch m = desk(List.of())
                .decide(ctx("Lithium Americas", List.of(
                        yq("XX", "Completely Different Inc.", "EQUITY"),
                        yq("LAC", "Lithium Americas Corp.", "EQUITY"))))
                .orElseThrow();
        assertEquals("LAC", m.symbol(), "an exact-name fact beats the judge (no false vetoes on exact names)");
    }

    @Test
    void venueOnlyPickCarriesTheWknAsSymbol() {
        nextPick.set(new Gemma4Judge.DeskPick(0, 1));
        SubjectMatch m = desk(List.of(ls(7, "DE000A1TNV91", "A1TNV9", "GERMAN SMALLCAP SE", "STK", "Aktie")))
                .decide(ctx("German Smallcap", List.of()))
                .orElseThrow();
        assertEquals("A1TNV9", m.symbol(), "a Yahoo-less listing is keyed by its WKN — the room's identifier");
        assertEquals("German Smallcap", m.canonicalName(), "the newswire canonical beats the venue's SHOUTY name");
        assertEquals(7L, m.venueId());
    }

    @Test
    void cryptoPickStampsTheCurrencyNotation() {
        nextPick.set(new Gemma4Judge.DeskPick(1, 2));
        SubjectMatch m = desk(List.of(
                ls(451698, "DE000A1TNV91", "A1TNV9", "BITCOIN GROUP SE  O.N.", "STK", "Aktie"),
                ls(3477757, "LS000LSOBTC1", "", "Bitcoin (BTC)", "CUR", "Währung")))
                .decide(ctx("Bitcoin", List.of(yq("BTC-USD", "Bitcoin", "CRYPTOCURRENCY"))))
                .orElseThrow();
        assertEquals("BTC-USD", m.symbol());
        assertEquals("CUR", m.category(), "the currency notation, not the same-named stock");
        assertEquals(3477757L, m.venueId());
    }

    /** A lookup whose venue prices are fixed — the price-veto seam. */
    private IdentityDesk deskWithPrices(List<InstrumentCandidate> venue, double venuePrice) {
        IdentityDesk d = new IdentityDesk(judge, () -> true);
        d.installLookup(new de.bsommerfeld.wsbg.terminal.core.price.InstrumentLookup() {
            @Override
            public List<InstrumentCandidate> search(String query) {
                return venue;
            }

            @Override
            public Optional<Double> lastPrice(InstrumentCandidate candidate) {
                return Optional.of(venuePrice);
            }
        });
        return d;
    }

    private static YahooQuote yqPriced(String symbol, String name, double price) {
        return new YahooQuote(symbol, name, name, "EQUITY", "NMS", "NasdaqGS", "", "", price, 1.0, 200_000.0);
    }

    @Test
    void priceVetoDropsAnImplausibleVenuePick() {
        nextPick.set(new Gemma4Judge.DeskPick(1, 1)); // the judge falls for the CDR twin
        IdentityDesk d = deskWithPrices(List.of(
                ls(4222103, "CA0000000001", "", "SAMENAME INC. CDR", "STK", "Aktie")), 20.70);

        SubjectMatch m = d.decide(ctx("Samename", List.of(yqPriced("SMNM", "Samename, Inc.", 330.0))))
                .orElseThrow();
        assertEquals("SMNM", m.symbol(), "the Yahoo identity stands");
        assertNull(m.isin(), "a venue pick orders of magnitude off the reference is the wrong paper");
        assertTrue(m.venueRuledOut(), "the struck venue side must stay shut for the price chain");
    }

    @Test
    void plausibleVenuePriceKeepsTheStamp() {
        nextPick.set(new Gemma4Judge.DeskPick(1, 1));
        IdentityDesk d = deskWithPrices(List.of(
                ls(41789, "US0079031078", "", "SAMENAME INC.", "STK", "Aktie")), 305.0);

        SubjectMatch m = d.decide(ctx("Samename", List.of(yqPriced("SMNM", "Samename, Inc.", 330.0))))
                .orElseThrow();
        assertEquals("US0079031078", m.isin(), "EUR/USD spread sits inside the band — the stamp holds");
        assertTrue(m.isStamped());
    }

    @Test
    void nonComparableCurrencySkipsThePriceVeto() {
        nextPick.set(new Gemma4Judge.DeskPick(1, 1));
        // A KRW listing: the raw units differ by orders of magnitude from the EUR
        // venue price without the paper being wrong — the veto must not fire.
        IdentityDesk d = deskWithPrices(List.of(
                ls(1, "KR0000000001", "", "KOREAN CORP.", "STK", "Aktie")), 1400.0);
        YahooQuote krw = new YahooQuote("000000.KS", "Korean Corp.", "Korean Corp.",
                "EQUITY", "KSC", "KSE", "", "", 70000.0, 1.0, 200_000.0);

        SubjectMatch m = d.decide(ctx("Korean Corp", List.of(krw))).orElseThrow();
        assertTrue(m.isStamped(), "no FX feed → no veto on a non-EUR/USD listing");
    }

    @Test
    void indexSymbolsNeverCarryAVenueStamp() {
        // The 2026-07-10 live run: the judge stamped the SPDR ETF onto ^DJI, so the
        // price chain showed 458 EUR instead of index points. An index has no venue
        // paper — the mechanical guard strips any L&S pick from a ^-symbol verdict.
        nextPick.set(new Gemma4Judge.DeskPick(1, 1));
        YahooQuote index = new YahooQuote("^DJI", "Dow Jones Industrial Average",
                "Dow Jones Industrial Average", "INDEX", "DJI", "Dow Jones", "", "",
                47000.0, 0.5, 200_000.0);
        SubjectMatch m = desk(List.of(ls(5, "US78467X1090", "", "SPDR DOW JONES IND.AV.ETF", "ETF", "ETF")))
                .decide(ctx("Dow Jones Industrial Average", List.of(index)))
                .orElseThrow();
        assertEquals("^DJI", m.symbol());
        assertFalse(m.isStamped(), "an index never executes a venue stamp — it prices in points");
        assertTrue(m.venueRuledOut(), "the wrapper ETF stays struck for the price chain too");
    }

    @Test
    void consideredVenueStrikeRulesTheVenueOut() {
        nextPick.set(new Gemma4Judge.DeskPick(1, 0)); // judge: yahoo yes, venue none
        SubjectMatch m = desk(List.of(ls(9, "CA0000000002", "", "SILVER TWIN CORP.", "STK", "Aktie")))
                .decide(ctx("BlackRock", List.of(yq("BLK", "BlackRock, Inc.", "EQUITY"))))
                .orElseThrow();
        assertTrue(m.venueRuledOut(), "the desk saw venue candidates and struck them all");

        nextPick.set(new Gemma4Judge.DeskPick(1, 0));
        SubjectMatch noVenue = desk(List.of())
                .decide(ctx("Abivax", List.of(yq("ABVX", "Abivax SA", "EQUITY"))))
                .orElseThrow();
        assertFalse(noVenue.venueRuledOut(), "no venue candidates seen → nothing was ruled out");
    }

    @Test
    void venueRuledOutSurvivesTheLedgerReplay() {
        Path file = dir.resolve("ledger.jsonl");
        nextPick.set(new Gemma4Judge.DeskPick(1, 0));
        IdentityDesk first = desk(List.of(ls(9, "CA0000000002", "", "SILVER TWIN CORP.", "STK", "Aktie")));
        first.installLedger(new IdentityLedger(file));
        first.decide(ctx("BlackRock", List.of(yq("BLK", "BlackRock, Inc.", "EQUITY"))));

        IdentityDesk second = desk(List.of());
        second.installLedger(new IdentityLedger(file));
        SubjectMatch replay = second.decide(ctx("blackrock", List.of())).orElseThrow();
        assertTrue(replay.venueRuledOut(), "the considered strike must keep the name search shut across restarts");
    }

    @Test
    void disabledDeskAbstains() {
        nextPick.set(new Gemma4Judge.DeskPick(1, 0));
        IdentityDesk d = new IdentityDesk(judge, () -> false);
        assertTrue(d.decide(ctx("NVIDIA", List.of(yq("NVDA", "NVIDIA Corporation", "EQUITY")))).isEmpty());
        assertEquals(0, judgeCalls.get(), "a disabled desk never calls the model");
    }

    @Test
    void noFactsAtAllAbstains() {
        nextPick.set(new Gemma4Judge.DeskPick(0, 0));
        assertTrue(desk(List.of()).decide(ctx("irgendwas", List.of())).isEmpty());
        assertEquals(0, judgeCalls.get(), "nothing to judge → no call");
    }

    @Test
    void sessionVerdictIsReusedWithoutASecondCall() {
        nextPick.set(new Gemma4Judge.DeskPick(1, 0));
        IdentityDesk d = desk(List.of());
        MatchContext c = ctx("NVIDIA", List.of(yq("NVDA", "NVIDIA Corporation", "EQUITY")));
        d.decide(c);
        d.decide(c);
        assertEquals(1, judgeCalls.get(), "identity is decided ONCE per subject per session");
    }

    @Test
    void ledgerReplaysAcrossDeskInstancesWithoutAModelCall() {
        Path file = dir.resolve("ledger.jsonl");
        nextPick.set(new Gemma4Judge.DeskPick(1, 1));
        IdentityDesk first = desk(List.of(ls(2, "DE0007030009", "703000", "RHEINMETALL AG", "STK", "Aktie")));
        first.installLedger(new IdentityLedger(file));
        first.decide(ctx("Rheinmetall", List.of(yq("RHM.DE", "Rheinmetall AG", "EQUITY"))));
        assertEquals(1, judgeCalls.get());

        IdentityDesk second = desk(List.of());
        second.installLedger(new IdentityLedger(file));
        SubjectMatch replay = second.decide(ctx("rheinmetall", List.of())).orElseThrow();
        assertEquals(1, judgeCalls.get(), "the persisted verdict replays — no second model call ever");
        assertEquals("RHM.DE", replay.symbol());
        assertEquals("DE0007030009", replay.isin());
        assertTrue(replay.isStamped());
    }
}
