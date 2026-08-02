package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.briefing.TradingEconomicsClient.EarningsEntry;
import de.bsommerfeld.wsbg.terminal.briefing.TradingEconomicsClient.Quote;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The macro desk on the KI-DD's shelves (wired 2026-08-03). Trading Economics
 * was built with tests during the source wave and then called by nothing at
 * all - not even the evening report.
 *
 * <p>Two house rules are pinned here. Which board rows appear is decided by
 * ARITHMETIC (the largest absolute daily move), never by a kept list of
 * symbols someone once found relevant. And the earnings figures stay VERBATIM
 * as the page prints them - "96.52B" is not rescaled into a number, because
 * the units vary per row and a rescale would invent one.
 */
class DeepDiveMacroDeskTest {

    private static DeepDiveService.Material bare() {
        DeepDiveService.Material m = new DeepDiveService.Material();
        m.canonicalName = "Rheinmetall AG";
        m.ticker = "RHM.DE";
        m.isin = "DE0007030009";
        return m;
    }

    private static String situation(DeepDiveService.Material m) {
        return DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_SITUATION];
    }

    // ---- the boards --------------------------------------------------------

    @Test
    void theBoardsCarryTheirMoversAndSayHowTheyWerePicked() {
        DeepDiveService.Material m = bare();
        m.teCommodities = List.of(
                new Quote("CL1:COM", "Crude Oil", "USD/Bbl", 71.42, -2.11, -2.87, "Aug/02"),
                new Quote("NG1:COM", "Natural gas", "USD/MMBtu", 3.12, 0.21, 7.19, "Aug/02"));
        m.teBonds = List.of(
                new Quote("GDBR10:GOV", "Germany 10Y", "%", 2.61, 0.06, 2.35, "Aug/02"));

        var nums = DeepDiveService.sourceNumbers(m);
        assertTrue(nums.containsKey("tradingeconomics"));
        String shelf = situation(m);

        assertContains(shelf, "MACRO BOARDS TODAY (verified, Trading Economics");
        assertContains(shelf, "[" + nums.get("tradingeconomics") + "]");
        assertContains(shelf, "commodities: Crude Oil 71.42 USD/Bbl, -2.87% [Aug/02]");
        assertContains(shelf, "commodities: Natural gas 3.12 USD/MMBtu, +7.19%");
        assertContains(shelf, "government bonds: Germany 10Y 2.61 %, +2.35%");
        // The selection rule is stated, so nobody reads the rows as a curated list.
        assertContains(shelf, "picked by size and not by relevance");
        assertContains(DeepDiveService.sourcesSection(m, true),
                "[" + nums.get("tradingeconomics") + "] Trading Economics");
    }

    /**
     * The mover pick is arithmetic on the absolute move - a big DROP is as much
     * a mover as a big rise, and unusable rows drop out entirely.
     */
    @Test
    void moversAreTheLargestAbsoluteMovesInEitherDirection() {
        List<Quote> board = List.of(
                new Quote("A", "Small up", "", 10, 0.1, 0.9, "Aug/02"),
                new Quote("B", "Big down", "", 10, -3.0, -22.5, "Aug/02"),
                new Quote("C", "Big up", "", 10, 3.0, 18.0, "Aug/02"),
                new Quote("D", "Broken", "", 10, 0, Double.NaN, "Aug/02"),
                new Quote("E", null, "", 10, 0, 5.0, "Aug/02"));
        DeepDiveService.Material m = bare();
        m.teCommodities = DeepDiveService.topMovers(board);

        String shelf = situation(m);
        int down = shelf.indexOf("Big down");
        int up = shelf.indexOf("Big up");
        int small = shelf.indexOf("Small up");
        assertTrue(down > 0 && up > down && small > up, shelf);
        assertFalse(shelf.contains("Broken"), shelf);
        assertTrue(DeepDiveService.topMovers(List.of()).isEmpty());
        assertTrue(DeepDiveService.topMovers(null).isEmpty());
    }

    /** Six rows per board - a board, not a market data feed. */
    @Test
    void theBoardCapHolds() {
        List<Quote> many = new java.util.ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(new Quote("S" + i, "Row " + i, "", 10, 1, 20.0 - i, "Aug/02"));
        }
        assertTrue(DeepDiveService.topMovers(many).size() == 6);
    }

    // ---- the earnings docket row -------------------------------------------

    @Test
    void theEarningsRowKeepsThePagesOwnLiterals() {
        DeepDiveService.Material m = bare();
        m.ticker = "NVDA";
        m.teEarnings = new EarningsEntry("2026-08-27", "NVDA", "NVIDIA Corp", "United States",
                "1.05", "0.98", "0.68", "$46.7B", "$45.9B", "$30.0B", "$4.3T", "Q2 2027",
                "After market", 3);

        var nums = DeepDiveService.sourceNumbers(m);
        String shelf = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_FUNDAMENTALS];

        assertContains(shelf, "REPORTED AGAINST CONSENSUS (verified, Trading Economics "
                + "earnings docket, Q2 2027, 2026-08-27");
        assertContains(shelf, "[" + nums.get("tradingeconomics") + "]");
        assertContains(shelf, "earnings per share 1.05 against a consensus of 0.98, "
                + "previously 0.68");
        // Verbatim, with the page's own scale letter - never rescaled by us.
        assertContains(shelf, "revenue $46.7B against a consensus of $45.9B, previously $30.0B");
        assertContains(shelf, "figures VERBATIM as the page prints them");
    }

    /** An unreported side is a dash - a zero would be a claim. */
    @Test
    void anUnreportedSideIsADashNotAZero() {
        DeepDiveService.Material m = bare();
        m.teEarnings = new EarningsEntry("2026-08-27", "RHM", "Rheinmetall", "Germany",
                "", "3.20", "2.90", "", "", "", "", "Q2 2026", "Before market", 2);
        String shelf = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_FUNDAMENTALS];
        assertContains(shelf, "earnings per share - against a consensus of 3.20");
        assertFalse(shelf.contains("revenue"), shelf);
    }

    /** A row with neither side is not a row. */
    @Test
    void anEmptyRowProducesNoBlock() {
        DeepDiveService.Material m = bare();
        m.teEarnings = new EarningsEntry("2026-08-27", "RHM", "Rheinmetall", "Germany",
                "", "", "", "", "", "", "", "Q2 2026", "", 0);
        String shelf = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_FUNDAMENTALS];
        assertTrue(shelf == null || !shelf.contains("REPORTED AGAINST CONSENSUS"),
                String.valueOf(shelf));
    }

    @Test
    void withoutTheLegNothingChanges() {
        DeepDiveService.Material m = bare();
        assertFalse(DeepDiveService.sourceNumbers(m).containsKey("tradingeconomics"));
        for (String shelf : DeepDiveService.sectionMaterials(m)) {
            if (shelf == null) continue;
            assertFalse(shelf.contains("MACRO BOARDS"), shelf);
            assertFalse(shelf.contains("REPORTED AGAINST CONSENSUS"), shelf);
        }
    }

    private static void assertContains(String haystack, String needle) {
        assertTrue(haystack != null && haystack.contains(needle),
                "expected to find:\n  " + needle + "\nin:\n" + haystack);
    }
}
