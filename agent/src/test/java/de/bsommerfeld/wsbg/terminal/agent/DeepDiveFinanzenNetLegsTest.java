package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.finanzennet.FinanzenNetMarketClient.ConsensusTrend;
import de.bsommerfeld.wsbg.terminal.finanzennet.FinanzenNetMarketClient.EstimateRow;
import de.bsommerfeld.wsbg.terminal.finanzennet.FinanzenNetMarketClient.PeerConsensus;
import de.bsommerfeld.wsbg.terminal.finanzennet.FinanzenNetMarketClient.PriceTarget;
import de.bsommerfeld.wsbg.terminal.finanzennet.FinanzenNetMarketClient.VenueQuote;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * finanzen.net's MARKET surfaces on the KI-DD's shelves (wired 2026-08-03).
 * Its news leg has ridden the aggregator since the source wave; the market
 * client and the resolver behind it had no caller at all.
 *
 * <p>Only what nothing else carries is read, and this test pins that boundary
 * as much as the rendering: the year-long consensus arc, the peer group's
 * targets, the estimate history with its own deviations, and the venue board.
 * The per-house targets are a STAND-IN and must stay silent while the house's
 * own street view speaks.
 */
class DeepDiveFinanzenNetLegsTest {

    private static DeepDiveService.Material bare() {
        DeepDiveService.Material m = new DeepDiveService.Material();
        m.canonicalName = "SAP SE";
        m.ticker = "SAP.DE";
        m.isin = "DE0007164600";
        return m;
    }

    // ---- the consensus arc -------------------------------------------------

    @Test
    void theConsensusArcShowsTheSameTallyAtThreePointsInTime() {
        DeepDiveService.Material m = bare();
        m.fnTrend = List.of(
                new ConsensusTrend("Aktuell", 21, 8, 1),
                new ConsensusTrend("vor 1/2 Jahr", 18, 10, 2),
                new ConsensusTrend("vor 1 Jahr", 14, 13, 3));

        var nums = DeepDiveService.sourceNumbers(m);
        assertTrue(nums.containsKey("finanzennet"));
        String shelf = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_VALUATION];

        assertContains(shelf, "CONSENSUS ARC (verified, finanzen.net");
        assertContains(shelf, "[" + nums.get("finanzennet") + "]");
        assertContains(shelf, "Aktuell: 21 buy, 8 hold, 1 sell");
        assertContains(shelf, "vor 1 Jahr: 14 buy, 13 hold, 3 sell");
        assertContains(DeepDiveService.sourcesSection(m, true),
                "[" + nums.get("finanzennet") + "] finanzen.net");
    }

    // ---- peer targets ------------------------------------------------------

    @Test
    void peerTargetsGiveTheSectorYardstickAndStayCapped() {
        DeepDiveService.Material m = bare();
        List<PeerConsensus> peers = new ArrayList<>();
        peers.add(new PeerConsensus("Oracle", 12, 9, 2, 210.0, "USD", 8.4));
        peers.add(new PeerConsensus("Microsoft", 40, 3, 0, 520.0, "USD", 11.2));
        for (int i = 0; i < 9; i++) {
            peers.add(new PeerConsensus("Peer " + i, 1, 1, 1, 10.0, "EUR", 1.0));
        }
        m.fnPeers = List.copyOf(peers);

        String shelf = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_VALUATION];
        assertContains(shelf, "PEER TARGETS (verified, finanzen.net sector comparison)");
        assertContains(shelf, "Oracle: 12/9/2 buy/hold/sell, average target 210.00 USD "
                + "(+8.40% to its own price)");
        assertContains(shelf, "Microsoft: 40/3/0 buy/hold/sell");
        assertEquals5(shelf);
    }

    private static void assertEquals5(String shelf) {
        int n = 0;
        for (String line : shelf.split("\n")) {
            if (line.contains("buy/hold/sell")) n++;
        }
        assertTrue(n == 5, "expected the peer cap to hold, got " + n + " rows in:\n" + shelf);
    }

    // ---- the estimate track ------------------------------------------------

    @Test
    void reportedAndForwardPeriodsAreDistinguishableOnTheEstimateTrack() {
        DeepDiveService.Material m = bare();
        m.fnEstimates = List.of(
                new EstimateRow("eps", "2025", LocalDate.of(2025, 12, 31), 28, 5.10, 4.55,
                        5.24, 0.14, 2.75, LocalDate.of(2026, 1, 28), "EUR"),
                new EstimateRow("eps", "2026e", LocalDate.of(2026, 12, 31), 31, 6.02, 5.24,
                        null, null, null, null, "EUR"));

        String shelf = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_FUNDAMENTALS];
        assertContains(shelf, "ESTIMATE TRACK (verified, finanzen.net");
        // A period that already happened carries its actual and the page's own
        // deviation - attributed, never recomputed into a new number.
        assertContains(shelf, "eps 2025: consensus 5.10 (28 analysts), reported 5.24 "
                + "(+2.75%, as the page prints it), prior year 4.55 [EUR]");
        // One still ahead carries no actual at all.
        assertContains(shelf, "eps 2026e: consensus 6.02 (31 analysts), prior year 5.24 [EUR]");
        assertContains(shelf, "a period with a reported figure already happened");
    }

    // ---- the venue board ---------------------------------------------------

    @Test
    void theVenueBoardSaysWhyTwoRowsMayDisagree() {
        DeepDiveService.Material m = bare();
        m.fnVenues = List.of(
                new VenueQuote("XETRA", "https://x", 152.30, 150.0, 2.3, 1.53, 149.5, 153.0,
                        1_240_000L, LocalTime.of(17, 35), LocalDate.of(2026, 8, 1), "EUR",
                        "Regulierter Markt"),
                new VenueQuote("Tradegate", "https://y", 152.44, 150.1, 2.34, 1.56, 149.6,
                        153.2, 88_000L, LocalTime.of(22, 0), LocalDate.of(2026, 8, 1), "EUR",
                        "Freiverkehr"),
                // A venue with no price is not a venue row.
                new VenueQuote("Berlin", "https://z", null, null, null, null, null, null,
                        null, null, null, "EUR", null));

        String shelf = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_SITUATION];
        assertContains(shelf, "VENUE BOARD (verified, finanzen.net");
        assertContains(shelf, "XETRA: 152.30 EUR, +1.53%, volume 1240000 [2026-08-01]");
        assertContains(shelf, "Tradegate: 152.44 EUR");
        assertFalse(shelf.contains("Berlin"), shelf);
        // The reason a report may show two different "last" prices, in the block.
        assertContains(shelf, "they close at different times");
    }

    // ---- the stand-in stays a stand-in --------------------------------------

    /**
     * The per-house targets exist only for the case where the house's own
     * street view is empty. The collector enforces that; here we pin that the
     * block declares its role, so nobody later promotes it to a fourth analyst
     * source.
     */
    @Test
    void theTargetBlockDeclaresItselfAStandIn() {
        DeepDiveService.Material m = bare();
        m.fnTargets = List.of(
                new PriceTarget("Deutsche Bank", 185.0, "EUR", 12.4, LocalDate.of(2026, 7, 30)),
                new PriceTarget("UBS", 172.0, "EUR", 4.5, LocalDate.of(2026, 7, 22)));
        String shelf = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_VALUATION];
        assertContains(shelf, "stands in where the house's own street view had nothing");
        assertContains(shelf, "[2026-07-30] Deutsche Bank: 185.00 EUR (+12.40%)");
    }

    // ---- absence -----------------------------------------------------------

    @Test
    void withoutTheLegNothingChanges() {
        DeepDiveService.Material m = bare();
        assertFalse(DeepDiveService.sourceNumbers(m).containsKey("finanzennet"));
        for (String shelf : DeepDiveService.sectionMaterials(m)) {
            if (shelf == null) continue;
            assertFalse(shelf.contains("CONSENSUS ARC"), shelf);
            assertFalse(shelf.contains("PEER TARGETS"), shelf);
            assertFalse(shelf.contains("ESTIMATE TRACK"), shelf);
            assertFalse(shelf.contains("VENUE BOARD"), shelf);
        }
    }

    private static void assertContains(String haystack, String needle) {
        assertTrue(haystack != null && haystack.contains(needle),
                "expected to find:\n  " + needle + "\nin:\n" + haystack);
    }
}
