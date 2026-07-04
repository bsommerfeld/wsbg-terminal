package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooQuote;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Exchange-preference (#8) of {@link TickerResolver#strongMatch}: among the
 * quotes that match the subject name, the primary/home listing must win over
 * foreign secondary lines, OTC ADRs and same-name ETFs — without a brittle
 * exchange whitelist. Load-neutral: this only re-ranks quotes already in one
 * search response.
 */
class TickerResolverTest {

    /** A quote with just the fields the matcher/ranker reads (no relevance score). */
    private static YahooQuote q(String symbol, String shortName, String exchange, String quoteType) {
        return q(symbol, shortName, exchange, quoteType, 0.0);
    }

    /** A quote carrying a Yahoo relevance score (tier-1 confidence signal). */
    private static YahooQuote q(String symbol, String shortName, String exchange, String quoteType, double score) {
        return new YahooQuote(symbol, shortName, null, quoteType, exchange, exchange, null, null, 0.0, 0.0, score);
    }

    @Test
    void rejectsAFuzzyMemecoinNamesakeButKeepsAMajorAndACashtag() {
        // "Starlink" (the SpaceX product) collides with an obscure same-named coin sitting at
        // Yahoo's base relevance — must NOT resolve to it (→ tickerless, line stays news-only).
        assertNull(StrongTokenMatcher.strongMatch("Starlink",
                List.of(q("STARL-USD", "Starlink USD", "CCC", "CRYPTOCURRENCY", 20002))),
                "obscure memecoin namesake is dropped");
        // Bitcoin: the coin the room genuinely means, far higher relevance → kept.
        assertEquals("BTC-USD", StrongTokenMatcher.strongMatch("Bitcoin",
                List.of(q("BTC-USD", "Bitcoin USD", "CCC", "CRYPTOCURRENCY", 37112))).symbol());
        // A cashtag the user wrote (exact symbol) is faithful — passes even at a low score.
        assertEquals("STARL-USD", StrongTokenMatcher.strongMatch("STARL-USD",
                List.of(q("STARL-USD", "Starlink USD", "CCC", "CRYPTOCURRENCY", 20002))).symbol());
    }

    @Test
    void prefersHomeListingOverNumericPrefixedSecondary() {
        // 1MUV2.MI is the Borsa Italiana secondary line of Munich Re; MUV2.DE is home.
        List<YahooQuote> quotes = List.of(
                q("1MUV2.MI", "MUNICH RE", "MIL", "EQUITY"),
                q("MUV2.DE", "MUNICH RE", "GER", "EQUITY"));
        assertEquals("MUV2.DE", StrongTokenMatcher.strongMatch("Munich", quotes).symbol());

        // Order must not matter — the numeric-prefixed line loses either way.
        List<YahooQuote> reversed = List.of(
                q("MUV2.DE", "MUNICH RE", "GER", "EQUITY"),
                q("1MUV2.MI", "MUNICH RE", "MIL", "EQUITY"));
        assertEquals("MUV2.DE", StrongTokenMatcher.strongMatch("Munich", reversed).symbol());
    }

    @Test
    void exactSymbolWinsOutright() {
        List<YahooQuote> quotes = List.of(
                q("1MUV2.MI", "MUNICH RE", "MIL", "EQUITY"),
                q("MUV2.DE", "MUNICH RE", "GER", "EQUITY"));
        assertEquals("MUV2.DE", StrongTokenMatcher.strongMatch("MUV2.DE", quotes).symbol());
    }

    @Test
    void demotesOtcAdr() {
        List<YahooQuote> quotes = List.of(
                q("ALIZY", "ALLIANZ", "PNK", "EQUITY"),   // US pink-sheet ADR
                q("ALV.DE", "ALLIANZ", "GER", "EQUITY"));
        assertEquals("ALV.DE", StrongTokenMatcher.strongMatch("Allianz", quotes).symbol());
    }

    @Test
    void prefersEquityOverSameNameEtf() {
        List<YahooQuote> quotes = List.of(
                q("XALV", "ALLIANZ", "GER", "ETF"),
                q("ALV.DE", "ALLIANZ", "GER", "EQUITY"));
        assertEquals("ALV.DE", StrongTokenMatcher.strongMatch("Allianz", quotes).symbol());
    }

    @Test
    void prefersUsPrimaryOverObscureForeignSecondary() {
        // The live regression: Take-Two resolved to TTWO.WA (Warsaw) instead of the
        // Nasdaq primary. The US primary (no suffix) must beat the obscure foreign line.
        List<YahooQuote> quotes = List.of(
                q("TTWO.WA", "TAKE-TWO INTERACTIVE", "WSE", "EQUITY"),
                q("TTWO", "TAKE-TWO INTERACTIVE", "NMS", "EQUITY"));
        assertEquals("TTWO", StrongTokenMatcher.strongMatch("Take-Two", quotes).symbol());
    }

    @Test
    void prefersXetraPrimaryOverFrankfurtFallback() {
        // For a German name the Xetra primary (.DE) outranks the Frankfurt venue (.F).
        List<YahooQuote> quotes = List.of(
                q("RHM.F", "RHEINMETALL", "FRA", "EQUITY"),
                q("RHM.DE", "RHEINMETALL", "GER", "EQUITY"));
        assertEquals("RHM.DE", StrongTokenMatcher.strongMatch("Rheinmetall", quotes).symbol());
    }

    @Test
    void prefersFrankfurtOverObscureForeignWhenNoPrimary() {
        // No home/primary listing in the set: Frankfurt (.F) beats Warsaw (.WA).
        List<YahooQuote> quotes = List.of(
                q("BYA.WA", "QUANTUM BLOCKCHAIN", "WSE", "EQUITY"),
                q("BYA.F", "QUANTUM BLOCKCHAIN", "FRA", "EQUITY"));
        assertEquals("BYA.F", StrongTokenMatcher.strongMatch("Quantum Blockchain", quotes).symbol());
    }

    @Test
    void soleMatchIsReturnedEvenIfPenalised() {
        List<YahooQuote> quotes = List.of(q("1MUV2.MI", "MUNICH RE", "MIL", "EQUITY"));
        assertEquals("1MUV2.MI", StrongTokenMatcher.strongMatch("Munich", quotes).symbol());
    }

    @Test
    void noNameMatchReturnsNull() {
        List<YahooQuote> quotes = List.of(q("ALV.DE", "ALLIANZ", "GER", "EQUITY"));
        assertNull(StrongTokenMatcher.strongMatch("Tesla", quotes));
    }

    @Test
    void singleTokenQueryMatchesDotComSuffixName() {
        // "Amazon" → "Amazon.com, Inc.": the glued ".com" must not break the strict
        // single-token match — "com" is a generic suffix like inc/corp. Regression:
        // Amazon used to fall through to a name-only unit with no ticker.
        List<YahooQuote> quotes = List.of(q("AMZN", "Amazon.com, Inc.", "NMS", "EQUITY"));
        assertEquals("AMZN", StrongTokenMatcher.strongMatch("Amazon", quotes).symbol());
    }

    @Test
    void prefersTheCashIndexOverItsFuture() {
        // "NASDAQ 100" matches both the future and the index; prefer the index (^NDX).
        List<YahooQuote> quotes = List.of(
                q("NQ=F", "Nasdaq 100 Futures", "CME", "FUTURE"),
                q("^NDX", "NASDAQ 100", "NIM", "INDEX"));
        assertEquals("^NDX", StrongTokenMatcher.strongMatch("NASDAQ 100", quotes).symbol());
    }

    @Test
    void genericThemeAcronymDoesNotExactMatchItsTicker() {
        // "AI" is the theme, not C3.ai — the exact-symbol fast-path is gated for theme words.
        assertNull(StrongTokenMatcher.strongMatch("AI", List.of(q("AI", "C3.ai, Inc.", "NYQ", "EQUITY"))));
        assertNull(StrongTokenMatcher.strongMatch("IT", List.of(q("IT", "Gartner, Inc.", "NYQ", "EQUITY"))));
    }

    // ---- Tier 2: LLM judge fallback when token/score matching can't decide ----

    @Test
    void tier2PicksTheJudgedCandidateWhenTokensDontMatch() {
        // "Google" shares NO token with "Alphabet Inc." — strongMatch can't link them,
        // but the judge knows they're the same entity. Tier 2 rescues it.
        TickerResolver r = new TickerResolver(null);
        r.setMatchJudge((subject, context, names) -> {
            // candidate lines are fact-enriched ("Name — TYPE, Exchange") — match by prefix
            for (int i = 0; i < names.size(); i++) {
                if (names.get(i).startsWith("Alphabet Inc.")) return i;
            }
            return -1;
        });
        List<YahooQuote> quotes = List.of(
                q("AAPL", "Apple Inc.", "NMS", "EQUITY"),
                q("GOOGL", "Alphabet Inc.", "NMS", "EQUITY"));
        assertEquals("GOOGL", r.judgeMatch("Google", "", quotes).symbol());
    }

    @Test
    void tier2RejectsNonEquityEvenWhenJudgedAMatch() {
        // A pure theme ("Biotech") has no token match; even if the judge picks an
        // ETF, the EQUITY-only Tier-2 gate rejects it — themes must not be promoted
        // to an ETF/index ticker with a bogus live price.
        TickerResolver r = new TickerResolver(null);
        r.setMatchJudge((subject, context, names) -> 0);
        assertNull(r.judgeMatch("Biotech", "", List.of(q("IBB", "iShares Biotechnology ETF", "NMS", "ETF"))));
    }

    @Test
    void tier2RejectsWhenTheJudgeSaysNone() {
        // The guard: the judge picking none → stays unresolved, never a guess.
        TickerResolver r = new TickerResolver(null);
        r.setMatchJudge((subject, context, names) -> -1);
        assertNull(r.judgeMatch("Rheinmetall", "", List.of(q("AAPL", "Apple Inc.", "NMS", "EQUITY"))));
    }

    @Test
    void vetoStrikesASpellingMatchThatIsNotTheEntity() {
        // Tier-1 matched on spelling (SPD the party vs the same-lettered US ETF) —
        // the judge says none of these IS the subject → ticker struck, news-only.
        TickerResolver r = new TickerResolver(null);
        r.setMatchJudge((subject, context, names) -> -1);
        YahooQuote picked = q("SPD", "Simplify US Equity PLUS Downside Convexity ETF", "PCX", "ETF");
        assertNull(r.vetoMatch("SPD", "Koalition beschließt Reformpaket", picked, List.of(picked)));
    }

    @Test
    void vetoConfirmsAndCachesPerSubject() {
        int[] calls = {0};
        TickerResolver r = new TickerResolver(null);
        r.setMatchJudge((subject, context, names) -> { calls[0]++; return 0; });
        YahooQuote picked = q("NVDA", "NVIDIA Corporation", "NMS", "EQUITY");
        assertEquals("NVDA", r.vetoMatch("NVIDIA", "", picked, List.of(picked)).symbol());
        assertEquals("NVDA", r.vetoMatch("NVIDIA", "", picked, List.of(picked)).symbol());
        assertEquals(1, calls[0], "verdict cached — one judge call per unique subject");
    }

    @Test
    void vetoRedirectsToTheCandidateTheJudgePicked() {
        TickerResolver r = new TickerResolver(null);
        r.setMatchJudge((subject, context, names) -> 1);
        YahooQuote wrong = q("MULN", "Mullen Automotive", "NMS", "EQUITY");
        YahooQuote right = q("MTL.TO", "Mullen Group Ltd.", "TOR", "EQUITY");
        assertEquals("MTL.TO", r.vetoMatch("Mullen Group", "", wrong, List.of(wrong, right)).symbol());
    }

    @Test
    void vetoRedirectWithoutSharedWordFallsBackOrStrikes() {
        // The 4B sometimes redirects to the least-wrong candidate ('KO' → Kohl's) —
        // an implausible redirect is never trusted. With a name-plausible tier-1
        // pick the pick survives (KO keeps Coca-Cola)…
        TickerResolver r = new TickerResolver(null);
        r.setMatchJudge((subject, context, names) -> 1);
        YahooQuote picked = q("KO", "The Coca-Cola Company", "NYQ", "EQUITY");
        YahooQuote wrong = q("KSS", "Kohl's Corporation", "NYQ", "EQUITY");
        assertEquals("KO", r.vetoMatch("KO", "", picked, List.of(picked, wrong)).symbol());

        // …but when even tier-1's pick shares no word with the subject, it strikes.
        TickerResolver r2 = new TickerResolver(null);
        r2.setMatchJudge((subject, context, names) -> 1);
        YahooQuote junkPick = q("XYZ", "Some Random Corp", "NYQ", "EQUITY");
        assertNull(r2.vetoMatch("Foo", "", junkPick, List.of(junkPick, wrong)));
    }

    @Test
    void sharesSignificantWordMatchesNameOrSymbol() {
        org.junit.jupiter.api.Assertions.assertTrue(
                NameMatching.sharesSignificantWord("BYD", "BYD Company Limited", "1211.HK"));
        org.junit.jupiter.api.Assertions.assertTrue(
                NameMatching.sharesSignificantWord("Brent crude oil", "Brent Crude Oil Last Day", "BRNTN.MX"));
        org.junit.jupiter.api.Assertions.assertFalse(
                NameMatching.sharesSignificantWord("KO", "Kohl's Corporation", "KSS"));
    }

    @Test
    void tier3AsksTheCorpusWhenYahooYieldsNothing() {
        // Yahoo missed a known instrument (the MicroStrategy→name-unit class) — the
        // local corpus proposes, the judge picks, the resolver gets a real symbol.
        TickerResolver r = new TickerResolver(null);
        r.setMatchJudge((subject, context, names) -> 0);
        var real = new de.bsommerfeld.wsbg.terminal.instruments.InstrumentCorpus(
                tmpFile(), List.of(new de.bsommerfeld.wsbg.terminal.instruments.CorpusSource() {
                    @Override
                    public String name() { return "fixture"; }

                    @Override
                    public List<de.bsommerfeld.wsbg.terminal.instruments.InstrumentEntry> fetch() {
                        return List.of(new de.bsommerfeld.wsbg.terminal.instruments.InstrumentEntry(
                                "MSTR", "MicroStrategy Incorporated", null, "US", "EQUITY", "sec"));
                    }
                }));
        real.refresh();
        r.setInstrumentCorpus(real);
        YahooQuote got = r.corpusMatch("MicroStrategy", "Bitcoin-Hebel über MicroStrategy");
        assertEquals("MSTR", got.symbol());
        assertEquals("MicroStrategy Incorporated", got.displayName());
    }

    @Test
    void tier3WordGuardBlocksLeastWrongCorpusPicks() {
        TickerResolver r = new TickerResolver(null);
        r.setMatchJudge((subject, context, names) -> 0); // judge picks the only candidate
        var real = new de.bsommerfeld.wsbg.terminal.instruments.InstrumentCorpus(
                tmpFile(), List.of(new de.bsommerfeld.wsbg.terminal.instruments.CorpusSource() {
                    @Override
                    public String name() { return "fixture"; }

                    @Override
                    public List<de.bsommerfeld.wsbg.terminal.instruments.InstrumentEntry> fetch() {
                        return List.of(new de.bsommerfeld.wsbg.terminal.instruments.InstrumentEntry(
                                "KSS", "Kohl's Corporation", null, "US", "EQUITY", "sec"));
                    }
                }));
        real.refresh();
        r.setInstrumentCorpus(real);
        assertNull(r.corpusMatch("Kohls", ""),
                "prefix retrieval finds the candidate, but the exact-word guard blocks the pick");
    }

    private static java.nio.file.Path tmpFile() {
        try {
            java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("corpus-test");
            dir.toFile().deleteOnExit();
            return dir.resolve("instruments.jsonl");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void vetoPreConfirmsNameEquivalentPicksWithoutTheJudge() {
        // "The name IS the name": a near-identical multi-word name never reaches the
        // judge (whose false strike on 'Lithium Americas' cost a legit quote, live 2x).
        TickerResolver r = new TickerResolver(null);
        r.setMatchJudge((subject, context, names) -> -1); // judge would strike — must not be asked
        YahooQuote lac = q("LAC", "Lithium Americas Corp.", "NYQ", "EQUITY");
        assertEquals("LAC", r.vetoMatch("Lithium Americas", "", lac, List.of(lac)).symbol());
        // …but an extra significant word still faces the judge (and here: gets struck).
        YahooQuote uamy = q("UAMY", "United States Antimony Corporation", "ASE", "EQUITY");
        assertNull(r.vetoMatch("United States", "", uamy, List.of(uamy)));
        // …and single-word subjects always face the judge (Kakao/SPD/KO class).
        YahooQuote kakao = q("035720.KS", "Kakao Corp", "KSC", "EQUITY");
        assertNull(r.vetoMatch("Kakao", "Kakao und Kaffee als Rohstoffe", kakao, List.of(kakao)));
    }

    @Test
    void tier3IgnoresShortSlangSubjects() {
        // 'OP' (Reddit slang) lexically hits "Empire State Realty OP, L.P." — a 1-2
        // char subject Yahoo couldn't place never reaches the corpus.
        TickerResolver r = new TickerResolver(null);
        r.setMatchJudge((subject, context, names) -> 0);
        var real = new de.bsommerfeld.wsbg.terminal.instruments.InstrumentCorpus(
                tmpFile(), List.of(new de.bsommerfeld.wsbg.terminal.instruments.CorpusSource() {
                    @Override
                    public String name() { return "fixture"; }

                    @Override
                    public List<de.bsommerfeld.wsbg.terminal.instruments.InstrumentEntry> fetch() {
                        return List.of(new de.bsommerfeld.wsbg.terminal.instruments.InstrumentEntry(
                                "FISK", "Empire State Realty OP, L.P.", null, "US", "EQUITY", "sec"));
                    }
                }));
        real.refresh();
        r.setInstrumentCorpus(real);
        assertNull(r.corpusMatch("OP", ""));
    }

    @Test
    void vetoRejectedRedirectKeepsANamePlausibleTier1Pick() {
        // The judge redirects to a word-implausible target ('IBM' → some IBM0.F line)
        // — with a name-plausible tier-1 pick, the pick survives instead of striking.
        TickerResolver r = new TickerResolver(null);
        r.setMatchJudge((subject, context, names) -> 1);
        YahooQuote ibm = q("IBM", "International Business Machines Corporation", "NYQ", "EQUITY");
        YahooQuote junk = q("IBM0.F", "Faktor-Zertifikat auf Big Blue", "FRA", "EQUITY");
        assertEquals("IBM", r.vetoMatch("IBM", "", ibm, List.of(ibm, junk)).symbol());
    }

    @Test
    void sameCompanyNameCollapsesListingChurnButNotWrongTwins() {
        org.junit.jupiter.api.Assertions.assertTrue(
                NameMatching.sameCompanyName("Infineon Technologies AG", "Infineon Technologies ADR"));
        org.junit.jupiter.api.Assertions.assertTrue(
                NameMatching.sameCompanyName("Nokia Oyj", "Nokia"));
        org.junit.jupiter.api.Assertions.assertFalse(
                NameMatching.sameCompanyName("Boyd Gaming Corporation", "BYD Company Limited"));
    }

    @Test
    void vetoKeepsTier1WhenTheJudgeRedirectsToAnotherListingOfTheSameCompany() {
        TickerResolver r = new TickerResolver(null);
        r.setMatchJudge((subject, context, names) -> 1); // judge prefers the ADR
        YahooQuote primary = q("IFX.DE", "Infineon Technologies AG", "GER", "EQUITY");
        YahooQuote adr = q("IFNNY", "Infineon Technologies ADR", "PNK", "EQUITY");
        assertEquals("IFX.DE", r.vetoMatch("Infineon", "", primary, List.of(primary, adr)).symbol());
    }

    @Test
    void isPrefixTrapCatchesSpellingBleedButNotIdentity() {
        org.junit.jupiter.api.Assertions.assertTrue(
                NameMatching.isPrefixTrap("Polen", "Polenergia S.A"), "Polen ⊂ Polenergia");
        org.junit.jupiter.api.Assertions.assertTrue(
                NameMatching.isPrefixTrap("Meta", "Metaplanet Inc."), "Meta ⊂ Metaplanet");
        org.junit.jupiter.api.Assertions.assertFalse(
                NameMatching.isPrefixTrap("Google", "Alphabet Inc."), "disjoint names = real cross-name identity");
        org.junit.jupiter.api.Assertions.assertFalse(
                NameMatching.isPrefixTrap("Meta", "Meta Platforms, Inc."), "shares the full word 'meta'");
        org.junit.jupiter.api.Assertions.assertFalse(
                NameMatching.isPrefixTrap("Nvidia", "NVIDIA Corporation"), "shares 'nvidia'");
    }

    @Test
    void tier2RejectsThePrefixTrap() {
        // 'Polen' (the country, in a war thread) must not become Polenergia just
        // because it spells the start of it — the judge picks it, the guard strikes.
        TickerResolver r = new TickerResolver(null);
        r.setMatchJudge((subject, context, names) -> 0);
        assertNull(r.judgeMatch("Polen", "Polen vs Russland: False-Flag-Angst",
                List.of(q("06Y.F", "Polenergia S.A", "FRA", "EQUITY"))));
        // …but a genuine disjoint cross-name pick still resolves.
        TickerResolver r2 = new TickerResolver(null);
        r2.setMatchJudge((subject, context, names) -> 0);
        assertEquals("GOOGL", r2.judgeMatch("Google", "",
                List.of(q("GOOGL", "Alphabet Inc.", "NMS", "EQUITY"))).symbol());
    }

    @Test
    void tier2IsNoOpWithoutAJudge() {
        TickerResolver r = new TickerResolver(null); // no judge → Tier 2 disabled
        assertNull(r.judgeMatch("Google", "", List.of(q("GOOGL", "Alphabet Inc.", "NMS", "EQUITY"))));
    }

    @Test
    void strictSingleTokenStillRejectsFuzzyExtraWord() {
        // The guard the strict mode exists for must survive the "com" relaxation:
        // a single-token query must NOT match a firm whose name merely contains the
        // token among real, distinguishing words (no score signal → stays rejected).
        List<YahooQuote> quotes = List.of(q("RMO", "Rheiner Management AG", "GER", "EQUITY"));
        assertNull(StrongTokenMatcher.strongMatch("Rheiner", quotes));
    }

    @Test
    void highYahooScoreRescuesSingleTokenWithExtraWord() {
        // "Meta" vs "Meta Platforms, Inc." — "platforms" is NOT (and shouldn't need
        // to be) a stop-word. A high Yahoo relevance score confirms the megacap, so
        // no stop-list growth is needed.
        List<YahooQuote> quotes = List.of(q("META", "Meta Platforms, Inc.", "NMS", "EQUITY", 800_000.0));
        assertEquals("META", StrongTokenMatcher.strongMatch("Meta", quotes).symbol());
    }

    @Test
    void lowYahooScoreDoesNotRescueFuzzyExtraWord() {
        // Same structural shape, but an obscure low-score hit must stay rejected —
        // the score, not a token list, is what separates Amazon-legit from Rheiner-fuzzy.
        List<YahooQuote> quotes = List.of(q("RMO", "Rheiner Management AG", "GER", "EQUITY", 1_200.0));
        assertNull(StrongTokenMatcher.strongMatch("Rheiner", quotes));
    }

    @Test
    void guardTowerStagesAreWiredInTheLoadBearingOrder() {
        // The cascade order encodes real fixes (DAX-ETF trap, „Gold" mining-stock trap,
        // SPD/Kakao veto, „OP"/Polen prefix-trap, tier-3 rescue) and must not drift:
        // Index → Commodity → Strong⊕Veto → Judge → Corpus.
        java.util.List<SubjectMatcher> stages = new TickerResolver(null).matchTower().stages();
        java.util.List<Class<?>> order = stages.stream().map(Object::getClass).toList();
        assertEquals(
                List.of(IndexMatcher.class, CommodityMatcher.class, IdentityVeto.class,
                        JudgeMatcher.class, CorpusMatcher.class),
                order);
    }
}
