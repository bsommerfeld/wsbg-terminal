package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.boersede.BoerseDeMarketClient;
import de.bsommerfeld.wsbg.terminal.cnbc.CnbcQuoteClient;
import de.bsommerfeld.wsbg.terminal.handelsblatt.HandelsblattInstrument;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The 2026-08-02 source wave's MARKET/FACT legs on the KI-DD's shelves - the
 * clients that carry no {@code NewsSource} and therefore never arrive through
 * the aggregator: CNBC (quarterly estimate-vs-actual plus quote fundamentals),
 * boerse.de (directors' dealings, the dpa-AFX analyst archive, eight fiscal
 * years) and Handelsblatt (ISIN master data).
 *
 * <p>Three contracts are pinned here: every new fact line carries ITS source
 * marker and a matching register entry (an unsourced figure in the DD is a
 * regression), a missing source costs exactly its own block and nothing else,
 * and the two gap fillers stay silent while the house's own chain delivers.
 */
class DeepDiveMarketLegsTest {

    private static DeepDiveService.Material bare() {
        DeepDiveService.Material m = new DeepDiveService.Material();
        m.canonicalName = "SAP SE";
        m.ticker = "SAP.DE";
        m.isin = "DE0007164600";
        return m;
    }

    private static CnbcQuoteClient.EarningsQuarter quarter(int year, int q, String day,
            Double epsEstimate, Double epsActual, String verdict) {
        return new CnbcQuoteClient.EarningsQuarter("SAP-DE", year, q,
                Instant.parse(day + "T10:00:00Z"), epsEstimate, epsActual, null,
                7_100.0, 7_240.0, 1_800.0, 1_650.0, verdict, 12.0, true);
    }

    private static CnbcQuoteClient.Quote quote(Double peTrailing, LocalDate nextEarnings,
            LocalDate exDividend) {
        return new CnbcQuoteClient.Quote("SAP-DE", "SAP SE", "SAP", 157.68, "EUR",
                1.2, 0.8, 156.48, 156.9, 158.2, 156.1, 1_200_000.0, "XETRA", "STOCK",
                "REGULAR", 1.84e11, peTrailing, 28.4, 5.12, 6.01, 1.08, 1.35, 2.20,
                1.17e9, 3.4e10, 180.0, 130.0, 17.6, 15.2, 73.1, 0.42, null,
                nextEarnings, exDividend, false);
    }

    // ---- CNBC: the delivery record ----------------------------------------

    /**
     * The highest-value leg of the wave: estimate BESIDE actual over eight
     * quarters answers "does this company deliver what it promises" with
     * citable numbers - and the deviation is house-computed from the pair,
     * CNBC's own verdict word rides along attributed.
     */
    @Test
    void cnbcSurpriseHistoryReachesTheFundamentalsShelfWithItsMarker() {
        DeepDiveService.Material m = bare();
        m.cnbcEarnings = List.of(
                quarter(2026, 2, "2026-07-22", 1.55, 1.65, "UP"),
                quarter(2026, 1, "2026-04-22", 1.40, 1.31, "DOWN"));

        var nums = DeepDiveService.sourceNumbers(m);
        assertTrue(nums.containsKey("cnbc"), "the delivered CNBC leg earns a source number");
        String shelf = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_FUNDAMENTALS];

        assertContains(shelf, "EARNINGS DELIVERY RECORD (verified, CNBC");
        assertContains(shelf, "[" + nums.get("cnbc") + "]");
        assertContains(shelf, "FY2026 Q2 [2026-07-22, before the bell]");
        // Both sides of the comparison, and the deviation computed by us.
        assertContains(shelf, "earnings per share estimate 1.55 vs actual 1.65 (+6.5%)");
        assertContains(shelf, "earnings per share estimate 1.40 vs actual 1.31 (-6.4%)");
        // Revenue and income keep CNBC's scale and SAY so - the material never
        // asserts a magnitude it did not verify.
        assertContains(shelf, "revenue estimate 7 100 vs actual 7 240 (+2.0%) (CNBC scale)");
        assertContains(shelf, "net income estimate 1 800 vs actual 1 650 (-8.3%) (CNBC scale)");
        assertContains(shelf, "CNBC's own verdict: UP");

        assertContains(DeepDiveService.sourcesSection(m, true),
                "- " + "[" + nums.get("cnbc") + "] CNBC");
    }

    /** The quarter cap holds even when the endpoint over-delivers. */
    @Test
    void surpriseHistoryStaysWithinItsQuarterCap() {
        DeepDiveService.Material m = bare();
        List<CnbcQuoteClient.EarningsQuarter> many = new java.util.ArrayList<>();
        for (int i = 0; i < 14; i++) {
            many.add(quarter(2026, 1 + (i % 4), "2026-07-22", 1.0, 1.1, "UP"));
        }
        m.cnbcEarnings = List.copyOf(many);
        String shelf = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_FUNDAMENTALS];
        assertEquals(8, occurrences(shelf, "earnings per share estimate"), shelf);
    }

    /** The dated anchors ride the Ausblick shelf - dates, never a forecast. */
    @Test
    void cnbcDatesAnchorTheOutlookShelf() {
        DeepDiveService.Material m = bare();
        m.cnbcQuote = quote(31.4, LocalDate.of(2026, 10, 21), LocalDate.of(2026, 5, 14));
        var nums = DeepDiveService.sourceNumbers(m);
        String shelf = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_OUTLOOK];
        assertContains(shelf, "DATED ANCHORS (verified, CNBC) [" + nums.get("cnbc") + "]");
        assertContains(shelf, "next earnings 2026-10-21");
        assertContains(shelf, "ex-dividend 2026-05-14");
    }

    /**
     * The quote fundamentals are a GAP FILLER - they step in only where the
     * house's own chain (Consorsbank key figures, onvista facts) carried
     * nothing, never as a second opinion against verified figures.
     */
    @Test
    void cnbcQuoteFundamentalsFillAGapAndStaySilentOtherwise() {
        DeepDiveService.Material gap = bare();
        gap.cnbcQuote = quote(31.4, null, null);
        String valuation = DeepDiveService.sectionMaterials(gap)[DeepDiveService.SEC_VALUATION];
        assertContains(valuation, "QUOTE FUNDAMENTALS (verified, CNBC");
        assertContains(valuation, "P/E trailing 31.40");
        assertContains(valuation, "return on equity +17.6%");
        assertContains(valuation, "debt/equity 0.42");

        DeepDiveService.Material covered = bare();
        covered.cnbcQuote = quote(31.4, null, null);
        covered.deepDive = DeepDiveChartsTest.deepDive(); // the house chain delivered
        String[] shelves = DeepDiveService.sectionMaterials(covered);
        assertFalse(String.join("\n", java.util.Arrays.stream(shelves)
                        .map(s -> s == null ? "" : s).toList())
                        .contains("QUOTE FUNDAMENTALS"),
                "the gap filler must not double the verified key figures");
    }

    /** CNBC spells venue suffixes with a hyphen; ambiguous shapes are never guessed. */
    @Test
    void cnbcSymbolTranslatesVenueSuffixesAndRefusesGuesses() {
        assertEquals("SAP-DE", DeepDiveService.cnbcSymbol("SAP.DE"));
        assertEquals("AAPL", DeepDiveService.cnbcSymbol("aapl"));
        assertNull(DeepDiveService.cnbcSymbol("^GDAXI"));
        assertNull(DeepDiveService.cnbcSymbol("EURUSD=X"));
        assertNull(DeepDiveService.cnbcSymbol(null));
    }

    // ---- boerse.de --------------------------------------------------------

    /**
     * Directors' dealings with real names, standing and volume land on the
     * Katalysatoren shelf BESIDE the BaFin block - two registers, two source
     * numbers, so a disagreement stays visible.
     */
    @Test
    void boerseInsiderDealsRideBesideTheBafinBlock() {
        DeepDiveService.Material m = bare();
        m.insiderDealings = DeepDiveChartsTest.insider();
        m.boerseInsiderDeals = List.of(new BoerseDeMarketClient.InsiderDeal(
                LocalDate.of(2026, 7, 30), "SAP SE", "DE0007164600", "Klein, Christian",
                "Kauf", "Vorstand", 480_000.0, "EUR"));

        var nums = DeepDiveService.sourceNumbers(m);
        assertTrue(nums.containsKey("insider") && nums.containsKey("boersede"),
                "each register keeps its own number: " + nums);
        assertFalse(nums.get("insider").equals(nums.get("boersede")));

        String shelf = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_CATALYSTS];
        assertContains(shelf, "INSIDER DEALINGS (verified, BaFin");
        assertContains(shelf, "DIRECTORS' DEALINGS (verified, boerse.de");
        assertContains(shelf, "[" + nums.get("boersede") + "]");
        assertContains(shelf, "[2026-07-30] Klein, Christian (Vorstand): Kauf 480 000 EUR");
    }

    /** The dpa-AFX archive lands on the valuation shelf, capped, newest first. */
    @Test
    void boerseAnalystArchiveReachesTheValuationShelf() {
        DeepDiveService.Material m = bare();
        List<BoerseDeMarketClient.AnalystCall> calls = new java.util.ArrayList<>();
        for (int i = 0; i < 15; i++) {
            calls.add(new BoerseDeMarketClient.AnalystCall(LocalDate.of(2026, 7, 20 - i % 19),
                    "JPMORGAN", "overweight", "JPMORGAN: SAP \"overweight\"", "https://x/" + i));
        }
        m.boerseAnalystCalls = List.copyOf(calls);

        var nums = DeepDiveService.sourceNumbers(m);
        String shelf = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_VALUATION];
        assertContains(shelf, "ANALYST CALL ARCHIVE (verified, boerse.de/dpa-AFX");
        assertContains(shelf, "[" + nums.get("boersede") + "]");
        assertContains(shelf, "JPMORGAN: overweight");
        assertEquals(10, occurrences(shelf, "JPMORGAN: overweight"), shelf);
    }

    /**
     * Eight fiscal years with their units spelled out - the long arc beside
     * the Consorsbank leg's shorter window. A year that reported nothing gets
     * no line rather than a row of blanks.
     */
    @Test
    void boerseFundamentalYearsCarryTheirUnits() {
        DeepDiveService.Material m = bare();
        m.boerseFundamentals = List.of(
                new BoerseDeMarketClient.FundamentalYear(2024, Map.of(
                        "Umsatz", 34_176.0,
                        "Jahresüberschuss", 3_060.0,
                        "Eigenkapitalquote", 56.4,
                        "Gewinn je Aktie (verwässert)", 2.62,
                        "Dividende je Aktie", 2.20,
                        "KGV (Kurs-Gewinn-Verhältnis)", 60.2)),
                new BoerseDeMarketClient.FundamentalYear(2026, Map.of()));

        var nums = DeepDiveService.sourceNumbers(m);
        String shelf = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_FUNDAMENTALS];
        assertContains(shelf, "FUNDAMENTAL HISTORY BY FISCAL YEAR (verified, boerse.de");
        assertContains(shelf, "[" + nums.get("boersede") + "]");
        assertContains(shelf, "2024: revenue 34 176 (millions)");
        assertContains(shelf, "equity ratio 56.40%");
        assertContains(shelf, "earnings per share (diluted) 2.62");
        assertFalse(shelf.contains("2026:"), "a year that reported nothing earns no line");
    }

    // ---- Handelsblatt ------------------------------------------------------

    /**
     * Master data lands on "Worum es geht"; the performance ladder only steps
     * in where the Consorsbank leg carried none - two performance blocks on
     * one shelf are a duplicate, not a second opinion.
     */
    @Test
    void handelsblattMasterDataLandsOnTheAboutShelfAndFillsThePerformanceGap() {
        DeepDiveService.Material m = bare();
        m.hbInstrument = handelsblatt();

        var nums = DeepDiveService.sourceNumbers(m);
        assertTrue(nums.containsKey("handelsblatt"), nums.toString());
        String[] shelves = DeepDiveService.sectionMaterials(m);
        String about = shelves[DeepDiveService.SEC_ABOUT];
        assertContains(about, "MASTER DATA (verified, Handelsblatt) [" + nums.get("handelsblatt") + "]");
        assertContains(about, "WKN 716460");
        assertContains(about, "sector Software");
        assertContains(about, "PERFORMANCE (verified, Handelsblatt)");
        assertContains(about, "1y +12.4%");
        assertContains(about, "PER-SHARE FIGURES (verified, Handelsblatt)");

        assertContains(DeepDiveService.sourcesSection(m, true),
                "[" + nums.get("handelsblatt") + "] Handelsblatt");

        DeepDiveService.Material covered = bare();
        covered.hbInstrument = handelsblatt();
        covered.deepDive = DeepDiveChartsTest.deepDive();
        String coveredAbout = DeepDiveService.sectionMaterials(covered)[DeepDiveService.SEC_ABOUT];
        assertContains(coveredAbout, "MASTER DATA (verified, Handelsblatt)");
        assertFalse(coveredAbout.contains("PERFORMANCE (verified, Handelsblatt)"),
                "the Consorsbank performance block already stands");
    }

    private static HandelsblattInstrument handelsblatt() {
        return new HandelsblattInstrument("SAP", "SAP SE", "DE0007164600", "716460",
                "SAP", "EUR", "Aktien", "Software", "Deutschland", "Xetra",
                "Profil", 157.68, 1.1, 2.2, 3.3, 4.4, 12.4, 88.8, 5.5, 5.12, 2.20, 1.35);
    }

    // ---- failure behaviour -------------------------------------------------

    /**
     * Every new leg is OPTIONAL: an absent source costs exactly its own block -
     * no source number, no heading, no empty chapter, no exception.
     */
    @Test
    void absentMarketLegsCostOnlyTheirOwnBlock() {
        DeepDiveService.Material m = bare();
        var nums = DeepDiveService.sourceNumbers(m);
        assertFalse(nums.containsKey("cnbc"));
        assertFalse(nums.containsKey("boersede"));
        assertFalse(nums.containsKey("handelsblatt"));

        String material = DeepDiveService.buildMaterial("SAP", m);
        for (String heading : List.of("EARNINGS DELIVERY RECORD", "DATED ANCHORS",
                "QUOTE FUNDAMENTALS", "DIRECTORS' DEALINGS", "ANALYST CALL ARCHIVE",
                "FUNDAMENTAL HISTORY BY FISCAL YEAR", "MASTER DATA")) {
            assertFalse(material.contains(heading),
                    "an absent leg must leave no heading behind: " + heading);
        }
        String register = DeepDiveService.sourcesSection(m, true);
        assertFalse(register.contains("CNBC"), register);
        assertFalse(register.contains("boerse.de"), register);
        assertFalse(register.contains("Handelsblatt"), register);
    }

    /**
     * A source that answers with NOTHING (empty lists, a figure-less year) is
     * the same case as an absent one - no number, no heading.
     */
    @Test
    void emptyAnswersAreTreatedLikeAnAbsentSource() {
        DeepDiveService.Material m = bare();
        m.cnbcEarnings = List.of();
        m.boerseInsiderDeals = List.of();
        m.boerseAnalystCalls = List.of();
        m.boerseFundamentals = List.of(new BoerseDeMarketClient.FundamentalYear(2026, Map.of()));

        var nums = DeepDiveService.sourceNumbers(m);
        assertFalse(nums.containsKey("cnbc"));
        // The leg DID answer, so it keeps its number - but it prints no block
        // with a heading and no figures it does not have.
        assertTrue(nums.containsKey("boersede"));
        String material = DeepDiveService.buildMaterial("SAP", m);
        assertFalse(material.contains("FUNDAMENTAL HISTORY BY FISCAL YEAR"), material);
        assertFalse(material.contains("DIRECTORS' DEALINGS"), material);
    }

    /**
     * The blanket contract: every fact line the new legs contribute carries a
     * source marker, and every marker used resolves in the register. An
     * unsourced figure in the DD is a regression, not a cosmetic flaw.
     */
    @Test
    void everyNewFactBlockCarriesAMarkerThatResolvesInTheRegister() {
        DeepDiveService.Material m = bare();
        m.cnbcEarnings = List.of(quarter(2026, 2, "2026-07-22", 1.55, 1.65, "UP"));
        m.cnbcQuote = quote(31.4, LocalDate.of(2026, 10, 21), null);
        m.boerseInsiderDeals = List.of(new BoerseDeMarketClient.InsiderDeal(
                LocalDate.of(2026, 7, 30), "SAP SE", "DE0007164600", "Klein, Christian",
                "Kauf", "Vorstand", 480_000.0, "EUR"));
        m.boerseAnalystCalls = List.of(new BoerseDeMarketClient.AnalystCall(
                LocalDate.of(2026, 7, 2), "GOLDMAN SACHS", "buy",
                "GOLDMAN SACHS: SAP \"buy\"", "https://x"));
        m.boerseFundamentals = List.of(new BoerseDeMarketClient.FundamentalYear(2024,
                Map.of("Umsatz", 34_176.0)));
        m.hbInstrument = handelsblatt();

        var nums = DeepDiveService.sourceNumbers(m);
        String material = DeepDiveService.buildMaterial("SAP", m);
        String register = DeepDiveService.sourcesSection(m, true);

        for (String heading : List.of("EARNINGS DELIVERY RECORD", "DATED ANCHORS",
                "QUOTE FUNDAMENTALS", "DIRECTORS' DEALINGS", "ANALYST CALL ARCHIVE",
                "FUNDAMENTAL HISTORY BY FISCAL YEAR", "MASTER DATA")) {
            int at = material.indexOf(heading);
            assertTrue(at >= 0, "block missing: " + heading + "\n" + material);
            String line = material.substring(at, material.indexOf('\n', at));
            assertTrue(line.matches(".*\\[\\d+\\].*"),
                    "an unmarked fact block: " + line);
        }
        for (String key : List.of("cnbc", "boersede", "handelsblatt")) {
            assertTrue(register.contains("- [" + nums.get(key) + "] "),
                    "register entry missing for " + key + ":\n" + register);
        }
    }

    private static int occurrences(String haystack, String needle) {
        int n = 0;
        for (int at = haystack.indexOf(needle); at >= 0;
                at = haystack.indexOf(needle, at + needle.length())) {
            n++;
        }
        return n;
    }

    private static void assertContains(String haystack, String needle) {
        assertTrue(haystack != null && haystack.contains(needle),
                "missing: \"" + needle + "\"\n---\n" + haystack);
    }
}
