package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.fool.FoolQuote;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The screener's sector standings and the Fool quote page on the KI-DD's
 * shelves (wired 2026-08-03). Both were built and then left without a caller:
 * the heatmap service polls for a widget that does not exist, and the quote
 * page's profile figures were only ever a by-product of the news leg.
 *
 * <p>Two things are pinned beyond the rendering. The sector move is
 * CAP-WEIGHTED and the block has to say so - an unweighted average of tickers
 * is a different number and would be read as the same one. And when the
 * subject is not on the board at all, the block says that instead of leaving
 * the reader to assume the unmarked rows include it.
 */
class DeepDiveSectorBoardTest {

    private static DeepDiveService.Material german() {
        DeepDiveService.Material m = new DeepDiveService.Material();
        m.canonicalName = "Rheinmetall AG";
        m.ticker = "RHM.DE";
        m.isin = "DE0007030009";
        return m;
    }

    private static HeatmapService.Node sector(String name, double perf) {
        return new HeatmapService.Node(name, name, null, null, 1_000d, perf, null, null);
    }

    private static HeatmapService.Node leaf(String symbol, String parent) {
        return new HeatmapService.Node(parent + ":" + symbol, symbol, parent, symbol,
                100d, 1.0, 10.0, 1e9);
    }

    // ---- the sector standings ----------------------------------------------

    @Test
    void theBoardIsSortedByMoveAndMarksTheSubjectsOwnSector() {
        DeepDiveService.Material m = german();
        m.sectorBoardUniverse = "Deutschland";
        m.sectorBoard = List.of(
                sector("Technology Services", 0.42),
                sector("Producer Manufacturing", 2.31),
                sector("Finance", -1.07),
                leaf("RHM", "Producer Manufacturing"));
        m.subjectSector = "Producer Manufacturing";

        var nums = DeepDiveService.sourceNumbers(m);
        assertTrue(nums.containsKey("sectorboard"));
        String shelf = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_SITUATION];

        assertContains(shelf, "SECTOR STANDINGS TODAY (verified, market screener, Deutschland");
        assertContains(shelf, "[" + nums.get("sectorboard") + "]");
        // Best first, so the standings read as standings.
        int best = shelf.indexOf("Producer Manufacturing: +2.31%");
        int mid = shelf.indexOf("Technology Services: +0.42%");
        int worst = shelf.indexOf("Finance: -1.07%");
        assertTrue(best > 0 && mid > best && worst > mid, shelf);
        assertContains(shelf, "Producer Manufacturing: +2.31%  <- this subject's sector");
        // The weighting is the whole point of the number.
        assertContains(shelf, "WEIGHTED BY MARKET CAPITALISATION");
        assertContains(shelf, "not the average of its tickers");
        assertContains(DeepDiveService.sourcesSection(m, false),
                "[" + nums.get("sectorboard") + "] Market screener");
    }

    /**
     * The board carries the market's largest issuers. A small cap is simply
     * not on it - and the block says so rather than letting the reader assume
     * one of the unmarked rows is his.
     */
    @Test
    void aSubjectMissingFromTheBoardIsDeclaredMissing() {
        DeepDiveService.Material m = german();
        m.sectorBoardUniverse = "Deutschland";
        m.sectorBoard = List.of(sector("Finance", 0.4), sector("Health Technology", -0.2));
        m.subjectSector = null;
        String shelf = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_SITUATION];
        assertContains(shelf, "this subject does not appear on the board");
        assertFalse(shelf.contains("<- this subject's sector"), shelf);
    }

    /** Leaves alone are not standings - without sector containers there is no block. */
    @Test
    void leavesWithoutSectorContainersProduceNoBlock() {
        DeepDiveService.Material m = german();
        m.sectorBoard = List.of(leaf("RHM", "Producer Manufacturing"));
        String shelf = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_SITUATION];
        assertTrue(shelf == null || !shelf.contains("SECTOR STANDINGS"), String.valueOf(shelf));
    }

    // ---- which market gets asked -------------------------------------------

    /**
     * Asking the wrong market puts a German small cap beside the S&P's giants.
     * The ISIN's country prefix decides, with the venue suffix behind it.
     */
    @Test
    void theListingCountryDecidesWhichBoardIsBuilt() {
        DeepDiveService.Material de = german();
        assertTrue(DeepDiveService.germanListing(de));

        DeepDiveService.Material suffixOnly = new DeepDiveService.Material();
        suffixOnly.ticker = "SAP.DE";
        assertTrue(DeepDiveService.germanListing(suffixOnly));

        DeepDiveService.Material us = new DeepDiveService.Material();
        us.ticker = "NVDA";
        us.isin = "US67066G1040";
        assertFalse(DeepDiveService.germanListing(us));
    }

    /** The subject is found by its bare symbol too - the board has no venue suffix. */
    @Test
    void theSubjectIsFoundByItsBareSymbol() {
        List<HeatmapService.Node> nodes = List.of(
                sector("Producer Manufacturing", 1.0),
                leaf("RHM", "Producer Manufacturing"));
        assertTrue("Producer Manufacturing".equals(
                DeepDiveService.sectorOfSubject(nodes, "RHM.DE")));
        assertTrue(DeepDiveService.sectorOfSubject(nodes, "NVDA") == null);
        assertTrue(DeepDiveService.sectorOfSubject(nodes, null) == null);
    }

    // ---- the Fool quote as the second gap filler ---------------------------

    @Test
    void theFoolProfileFiguresDeclareThemselvesAStandIn() {
        DeepDiveService.Material m = new DeepDiveService.Material();
        m.canonicalName = "Outlook Therapeutics";
        m.ticker = "OTLK";
        m.foolQuote = new FoolQuote("OTLK", "nasdaq", "Outlook Therapeutics Inc",
                "Healthcare", "Biotechnology", "desc", "USD", 1.42, -3.1, 4.12e8,
                null, -0.21, null, 0.88, 12.40, 3_100_000L);

        var nums = DeepDiveService.sourceNumbers(m);
        String shelf = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_FUNDAMENTALS];

        assertContains(shelf, "PROFILE FIGURES (verified, Motley Fool quote page");
        assertContains(shelf, "stands in where the house's own chain had nothing");
        assertContains(shelf, "[" + nums.get("foolquote") + "]");
        assertContains(shelf, "sector Healthcare, industry Biotechnology");
        assertContains(shelf, "market capitalisation 412 000 000 USD");
        assertContains(shelf, "earnings per share -0.21");
        assertContains(shelf, "52-week range 0.88 to 12.40");
        // Absent figures are absent, not zero.
        assertFalse(shelf.contains("P/E"), shelf);
        assertFalse(shelf.contains("dividend yield"), shelf);
    }

    // ---- absence -----------------------------------------------------------

    @Test
    void withoutTheLegsNothingChanges() {
        DeepDiveService.Material m = german();
        var nums = DeepDiveService.sourceNumbers(m);
        assertFalse(nums.containsKey("sectorboard"));
        assertFalse(nums.containsKey("foolquote"));
        for (String shelf : DeepDiveService.sectionMaterials(m)) {
            if (shelf == null) continue;
            assertFalse(shelf.contains("SECTOR STANDINGS"), shelf);
            assertFalse(shelf.contains("PROFILE FIGURES"), shelf);
        }
    }

    private static void assertContains(String haystack, String needle) {
        assertTrue(haystack != null && haystack.contains(needle),
                "expected to find:\n  " + needle + "\nin:\n" + haystack);
    }
}
