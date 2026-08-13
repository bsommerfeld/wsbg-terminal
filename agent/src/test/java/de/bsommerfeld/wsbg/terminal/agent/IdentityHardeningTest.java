package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.instruments.AliasStore;
import de.bsommerfeld.wsbg.terminal.instruments.SymbolShapes;
import de.bsommerfeld.wsbg.terminal.web.facts.InstrumentCandidate;
import de.bsommerfeld.wsbg.terminal.web.facts.InstrumentLookup;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.yahoofinance.YahooQuote;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The six documented live failure cases of the 2026-08-13 identity run, as
 * fixtures against the hardening (candidate hygiene, crypto positive list,
 * product kind, symbol-stem rerank, replay price veto, corpus veto):
 * <ol>
 *   <li>'Gemini' → GEMINI34655-USD (junk coin on a product name)</li>
 *   <li>'GTA' → GTA6-USD (coin instead of the game)</li>
 *   <li>'Rockstar' → 0P0001L7U0.SA (Morningstar fund id)</li>
 *   <li>'Munich Re' → 1MUV2.MI (Milan secondary instead of MUV2.DE)</li>
 *   <li>'Lindt &amp; Sprüngli' → 870503 (WKN as unit id — documented open, the
 *       ledger no longer REMEMBERS such a reading)</li>
 *   <li>'IREN' → Italian utility ISIN stamped onto the Nasdaq miner (invisible
 *       to every shape heuristic — caught by the replay price veto)</li>
 * </ol>
 */
class IdentityHardeningTest {

    @TempDir
    Path dir;

    private static YahooQuote yq(String symbol, String name, String type, double price, double score) {
        return new YahooQuote(symbol, name, name, type, "GER", "X", "", "", price, 1.0, score);
    }

    // ---- Fixture 1+2: junk coins and non-major coins never become candidates ----

    @Test
    void geminiJunkCoinIsStruckFromTheCandidateSpace() {
        List<YahooQuote> in = List.of(
                yq("GEMINI34655-USD", "Gemini USD", "CRYPTOCURRENCY", 0.0001, 20_000),
                yq("GOOGL", "Alphabet Inc.", "EQUITY", 200.0, 500_000));
        List<YahooQuote> out = QuoteClassifier.admissibleQuotes(in);
        assertEquals(1, out.size());
        assertEquals("GOOGL", out.get(0).symbol(), "the minted namesake token is gone before any judge");
    }

    @Test
    void gtaCoinIsStruckByTheCryptoPositiveList() {
        List<YahooQuote> out = QuoteClassifier.admissibleQuotes(List.of(
                yq("GTA6-USD", "GTA6", "CRYPTOCURRENCY", 0.002, 21_000)));
        assertTrue(out.isEmpty(), "a coin outside the declared positive list is no candidate — news-only");
    }

    @Test
    void majorCoinsStayAdmissible() {
        List<YahooQuote> out = QuoteClassifier.admissibleQuotes(List.of(
                yq("BTC-USD", "Bitcoin USD", "CRYPTOCURRENCY", 100_000, 37_000),
                yq("ETH-USD", "Ethereum USD", "CRYPTOCURRENCY", 4_000, 32_000)));
        assertEquals(2, out.size(), "BTC and ETH are real subjects of the room");
    }

    @Test
    void productKindStopsTheDeskLikePersonAndTheme() {
        assertTrue(new Gemma4Judge.DeskPick(3, 1, "product").nonInstrument(),
                "a product (Gemini, GTA, ChatGPT) never claims an instrument");
        assertTrue(new Gemma4Judge.DeskPick(1, 1, "Produkt").nonInstrument());
        assertFalse(new Gemma4Judge.DeskPick(1, 1, "instrument").nonInstrument());
    }

    // ---- Fixture 3: Morningstar fund ids ----

    @Test
    void rockstarMorningstarFundIdIsNeverACandidate() {
        List<YahooQuote> out = QuoteClassifier.admissibleQuotes(List.of(
                yq("0P0001L7U0.SA", "Rockstar Fundo de Investimento", "MUTUALFUND", 1.2, 20_000)));
        assertTrue(out.isEmpty(), "a 0P… fund id is never an equity room's subject");
    }

    // ---- Fixture 4: Munich Re — numeric-prefixed secondary + symbol-stem rerank ----

    @Test
    void numericPrefixedWesternSecondaryIsDroppedWhenAPeerSurvives() {
        List<YahooQuote> out = QuoteClassifier.admissibleQuotes(List.of(
                yq("1MUV2.MI", "Munich Re", "EQUITY", 480.0, 90_000),
                yq("MUV2.DE", "Muenchener Rueckversicherungs-Gesellschaft AG", "EQUITY", 480.0, 80_000)));
        assertEquals(1, out.size());
        assertEquals("MUV2.DE", out.get(0).symbol());
    }

    @Test
    void numericPrefixedSecondaryStaysAsTheLoneHandle() {
        List<YahooQuote> out = QuoteClassifier.admissibleQuotes(List.of(
                yq("1ELF.MI", "e.l.f. Beauty", "EQUITY", 100.0, 50_000)));
        assertEquals(1, out.size(), "the right referent at a thin venue beats no referent");
    }

    @Test
    void asianNumericHomeListingsAreExemptFromTheVeto() {
        for (String sym : List.of("1211.HK", "285A.T", "000660.KS", "2353.TW", "000012.SZ", "2222.SR")) {
            assertFalse(SymbolShapes.isNumericPrefixedWesternSecondary(sym), sym + " is a home listing");
        }
        assertFalse(SymbolShapes.isNumericPrefixedWesternSecondary("1COV.DE"), "Covestro's Xetra primary");
        assertFalse(SymbolShapes.isNumericPrefixedWesternSecondary("8TRA.DE"), "Traton's Xetra primary");
        for (String sym : List.of("1MUV2.MI", "9YM.F", "4HEI.TI", "0DHC.IL", "1ELF.MI", "1HH.MU", "2CI0.F")) {
            assertTrue(SymbolShapes.isNumericPrefixedWesternSecondary(sym), sym + " is a secondary line");
        }
    }

    @Test
    void munichReReranksAcrossTheLanguageGapViaTheSymbolStem() {
        // Even when the secondary line survives to the desk (lone-handle path or a
        // direct context), a judge pick on 1MUV2.MI must land on MUV2.DE: the two
        // NAMES share no word across the languages, the symbol STEM (MUV2) does.
        AtomicReference<Gemma4Judge.DeskPick> pick = new AtomicReference<>(
                new Gemma4Judge.DeskPick(1, 0));
        IdentityDesk desk = new IdentityDesk((s, c, y, l) -> pick.get(), () -> true);
        SubjectMatch m = desk.decide(new MatchContext("Munich Re", "Munich Re Rekordzahlen", List.of(
                yq("1MUV2.MI", "Munich Re", "EQUITY", 480.0, 90_000),
                yq("MUV2.DE", "Muenchener Rueckversicherungs-Gesellschaft AG", "EQUITY", 481.0, 80_000))))
                .orElseThrow();
        assertEquals("MUV2.DE", m.symbol(), "the stem rerank crosses the language gap");
    }

    @Test
    void stemRerankNeverFoldsDifferentPapers() {
        AtomicReference<Gemma4Judge.DeskPick> pick = new AtomicReference<>(
                new Gemma4Judge.DeskPick(2, 0));
        IdentityDesk desk = new IdentityDesk((s, c, y, l) -> pick.get(), () -> true);
        SubjectMatch m = desk.decide(new MatchContext("Siemens Energy", "t", List.of(
                yq("SIE.DE", "Siemens AG", "EQUITY", 200.0, 500_000),
                yq("ENR.F", "Siemens Energy AG", "EQUITY", 90.0, 400_000)))).orElseThrow();
        assertEquals("ENR.F", m.symbol(), "different stems, different word sets — no fold");
    }

    // ---- Fixture 5: the WKN class is at least never REMEMBERED any more ----

    @Test
    void bareWknReadingsAreMutedInTheAliasFold() throws Exception {
        Path f = dir.resolve("aliases.jsonl");
        long base = System.currentTimeMillis() / 1000 - 12 * 3600;
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            lines.add("{\"name\":\"lindt & sprüngli\",\"symbol\":\"870503\",\"at\":" + (base + i * 3601L)
                    + ",\"tier\":\"DeskMatcher\"}");
        }
        Files.write(f, lines, StandardCharsets.UTF_8);
        AliasStore store = new AliasStore(f);
        assertTrue(store.candidatesFor("Lindt & Sprüngli").isEmpty(),
                "a bare WKN answers no price/news/chart question — never replayed");
        store.learn("lindt & sprüngli", "870503");
        assertTrue(store.candidatesFor("Lindt & Sprüngli").isEmpty(), "and never re-learned");
    }

    @Test
    void junkCryptoAndFundIdReadingsAreMutedInTheAliasFold() throws Exception {
        Path f = dir.resolve("aliases2.jsonl");
        long base = System.currentTimeMillis() / 1000 - 12 * 3600;
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            lines.add("{\"name\":\"gemini\",\"symbol\":\"GEMINI34655-USD\",\"at\":" + (base + i * 3601L) + "}");
            lines.add("{\"name\":\"rockstar\",\"symbol\":\"0P0001L7U0.SA\",\"at\":" + (base + i * 3601L) + "}");
            lines.add("{\"name\":\"bitcoin\",\"symbol\":\"BTC-USD\",\"at\":" + (base + i * 3601L) + "}");
        }
        Files.write(f, lines, StandardCharsets.UTF_8);
        AliasStore store = new AliasStore(f);
        assertTrue(store.candidatesFor("gemini").isEmpty());
        assertTrue(store.candidatesFor("rockstar").isEmpty());
        assertFalse(store.candidatesFor("bitcoin").isEmpty(), "a major coin stays remembered");
    }

    // ---- Fixture 6: IREN — the replay price veto ----

    private AliasStore irenStore() throws Exception {
        Path f = dir.resolve("iren.jsonl");
        long base = System.currentTimeMillis() / 1000 - 12 * 3600;
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            lines.add("{\"name\":\"iren\",\"symbol\":\"IREN\",\"at\":" + (base + i * 3601L)
                    + ",\"tier\":\"DeskMatcher\",\"isin\":\"IT0003027817\",\"venue\":275805,\"cat\":\"STK\"}");
        }
        Files.write(f, lines, StandardCharsets.UTF_8);
        return new AliasStore(f);
    }

    @Test
    void irenBurnedInStampAbstainsUnderTheReplayPriceVeto() throws Exception {
        // The live case: symbol IREN correct, but the stamped venue paper is the
        // Italian utility at 2.52 EUR against a two-digit-dollar Nasdaq reference.
        InstrumentLookup lookup = new InstrumentLookup() {
            @Override
            public List<InstrumentCandidate> search(String query) {
                return List.of();
            }

            @Override
            public Optional<Double> lastPrice(InstrumentCandidate candidate) {
                return Optional.of(2.518);
            }
        };
        AliasMemoryMatcher m = new AliasMemoryMatcher(this::irenStoreUnchecked, () -> null, () -> lookup);
        MatchContext ctx = new MatchContext("IREN", "Microsoft KI-Rechenzentren",
                List.of(yq("IREN", "IREN Limited", "EQUITY", 40.0, 300_000)));
        assertTrue(m.match(ctx).isEmpty(),
                "ratio 0.06 is far outside the band — the memory abstains, the desk re-decides");
    }

    @Test
    void irenUnpriceableStampAlsoAbstains() throws Exception {
        InstrumentLookup lookup = new InstrumentLookup() {
            @Override
            public List<InstrumentCandidate> search(String query) {
                return List.of();
            }
        };
        AliasMemoryMatcher m = new AliasMemoryMatcher(this::irenStoreUnchecked, () -> null, () -> lookup);
        MatchContext ctx = new MatchContext("IREN", "t",
                List.of(yq("IREN", "IREN Limited", "EQUITY", 40.0, 300_000)));
        assertTrue(m.match(ctx).isEmpty(), "a missing venue price is no acquittal");
    }

    @Test
    void aPlausibleStampStillReplaysFromMemory() throws Exception {
        InstrumentLookup lookup = new InstrumentLookup() {
            @Override
            public List<InstrumentCandidate> search(String query) {
                return List.of();
            }

            @Override
            public Optional<Double> lastPrice(InstrumentCandidate candidate) {
                return Optional.of(36.0); // EUR — same magnitude as the 40 USD reference
            }
        };
        AliasMemoryMatcher m = new AliasMemoryMatcher(this::irenStoreUnchecked, () -> null, () -> lookup);
        MatchContext ctx = new MatchContext("IREN", "t",
                List.of(yq("IREN", "IREN Limited", "EQUITY", 40.0, 300_000)));
        Optional<SubjectMatch> match = m.match(ctx);
        assertTrue(match.isPresent(), "a verified stamp keeps saving the desk's work");
        assertEquals("IREN", match.get().symbol());
    }

    @Test
    void withoutAYahooReferenceTheReplayStaysUnverifiedButAlive() throws Exception {
        // No quote for the symbol in this search: the stamp cannot be judged, and an
        // unverifiable stamp must not punish every venue-only listing.
        InstrumentLookup lookup = new InstrumentLookup() {
            @Override
            public List<InstrumentCandidate> search(String query) {
                return List.of();
            }
        };
        AliasMemoryMatcher m = new AliasMemoryMatcher(this::irenStoreUnchecked, () -> null, () -> lookup);
        assertTrue(m.match(new MatchContext("IREN", "t", List.of())).isPresent());
    }

    private AliasStore irenStoreUnchecked() {
        try {
            return irenStore();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- The desk's missing-venue-price rule ----

    @Test
    void anUnpriceableVenuePickShipsTheSymbolWithoutTheStamp() {
        AtomicReference<Gemma4Judge.DeskPick> pick = new AtomicReference<>(
                new Gemma4Judge.DeskPick(1, 1));
        IdentityDesk desk = new IdentityDesk((s, c, y, l) -> pick.get(), () -> true);
        desk.installLookup(new InstrumentLookup() {
            @Override
            public List<InstrumentCandidate> search(String query) {
                return List.of(new InstrumentCandidate("L&S", 7, "IT0003027817", "", "IREN S.P.A.",
                        "STK", "Aktie"));
            }
        });
        SubjectMatch m = desk.decide(new MatchContext("IREN", "t",
                List.of(yq("IREN", "IREN Limited", "EQUITY", 40.0, 300_000)))).orElseThrow();
        assertEquals("IREN", m.symbol());
        assertNull(m.isin(), "an unpriceable stamp is dropped");
        assertFalse(m.venueRuledOut(), "…but the venue question stays open (transient, session-only)");
    }

    // ---- The corpus veto core ----

    private static final java.util.function.ToIntFunction<String> NO_UNIVERSE = t -> 0;

    @Test
    void corpusVetoStrikesANameParasiteWithZeroCoOccurrence() {
        // 'Rockstar' in a basin that only knows the energy drink beside Celsius: an
        // equity candidate riding the name but claiming "Consortium" finds no echo.
        Set<String> subject = NameMatching.tokenize("Rockstar");
        List<Set<String>> docs = List.of(
                Set.of("rockstar", "energy", "founder", "celsius", "stake"),
                Set.of("rockstar", "burger", "king", "wendy", "deal"),
                Set.of("rockstar", "energy", "drink", "sales"));
        assertTrue(IdentityDesk.contradicted(subject, docs, "Rockstar Consortium Inc.", "RSTC",
                null, NO_UNIVERSE));
    }

    @Test
    void corpusVetoNeverStrikesACrossNameIdentity() {
        // Google → Alphabet: co-occurrence cannot verify the nickname edge (the
        // measured basin held 20 Google docs, none naming Alphabet) — a candidate
        // that does NOT ride the subject's surface form is out of the veto's scope.
        Set<String> subject = NameMatching.tokenize("Google");
        List<Set<String>> docs = List.of(
                Set.of("google", "pixel", "phone"), Set.of("google", "gemini", "mail"),
                Set.of("google", "suche", "werbung"));
        assertFalse(IdentityDesk.contradicted(subject, docs, "Alphabet Inc.", "GOOGL",
                null, NO_UNIVERSE));
    }

    @Test
    void corpusVetoTreatsCorporateFillerAsNoIdentityClaim() {
        // "Cisco" vs "Cisco Systems, Inc.": 73 universe names carry "Systems" — the
        // token identifies nothing, so its absence from the basin proves nothing.
        Set<String> subject = NameMatching.tokenize("Cisco");
        List<Set<String>> docs = List.of(Set.of("cisco", "quartal"), Set.of("cisco", "netzwerk"),
                Set.of("cisco", "ki"));
        java.util.function.ToIntFunction<String> df = t -> "systems".equals(t) ? 73 : 0;
        assertFalse(IdentityDesk.contradicted(subject, docs, "Cisco Systems, Inc.", "CSCO", null, df));
    }

    @Test
    void corpusVetoNeverStrikesACandidateTheBasinSupports() {
        Set<String> subject = NameMatching.tokenize("Krispy Kreme");
        List<Set<String>> docs = List.of(
                Set.of("krispy", "kreme", "doughnut", "quartal"),
                Set.of("krispy", "kreme", "doughnut", "aktie"),
                Set.of("krispy", "kreme", "meme", "rally"));
        assertFalse(IdentityDesk.contradicted(subject, docs, "Krispy Kreme Doughnut Corp.", "DNUT",
                null, NO_UNIVERSE), "the extra claim 'doughnut' is echoed by the basin");
    }

    @Test
    void corpusVetoNeverStrikesACandidateThatClaimsNothingBeyondTheName() {
        Set<String> subject = NameMatching.tokenize("Nvidia");
        List<Set<String>> docs = List.of(Set.of("nvidia", "earnings"), Set.of("nvidia", "china"),
                Set.of("nvidia", "chips"));
        assertFalse(IdentityDesk.contradicted(subject, docs, "NVIDIA Corporation", "NVDA",
                null, NO_UNIVERSE), "no extra identity claim — nothing to contradict");
    }

    // ---- The analyst-action gate (Teil 3) ----

    @Test
    void aPriceTargetCutNeverKeepsRed() {
        de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight out =
                HighlightReconciler.reconcileHighlight(
                        de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight.IMPORTANT,
                        "HARD_CATALYST", null,
                        "Hertz fällt weiter, da Jefferies das Kursziel wegen schwacher "
                                + "Gebrauchtwagenpreise senkte.");
        assertEquals(de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight.NORMAL, out,
                "the rubric's exclusion list is enforced in code, not argued with the model");
    }

    @Test
    void aRealCatalystKeepsRed() {
        de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight out =
                HighlightReconciler.reconcileHighlight(
                        de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight.IMPORTANT,
                        "HARD_CATALYST", null,
                        "Kratos Defense erhielt einen Auftrag über rund 100 Millionen Dollar.");
        assertEquals(de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight.IMPORTANT, out);
    }
}
