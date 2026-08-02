package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.onvista.OnvistaEntity;
import de.bsommerfeld.wsbg.terminal.onvista.OnvistaFundamentalsClient.AnalystConsensus;
import de.bsommerfeld.wsbg.terminal.onvista.OnvistaFundamentalsClient.BenchmarkFit;
import de.bsommerfeld.wsbg.terminal.onvista.OnvistaFundamentalsClient.CompanySnapshot;
import de.bsommerfeld.wsbg.terminal.onvista.OnvistaFundamentalsClient.Participation;
import de.bsommerfeld.wsbg.terminal.onvista.OnvistaFundamentalsClient.Shareholder;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * onvista's own API on the KI-DD's shelves (wired 2026-08-03). The house held
 * the resolver and the fundamentals client since the source wave with no
 * caller at all; the bound profile leg only ever delivered the portrait.
 *
 * <p>These three blocks answer what the Consorsbank record does not ask: who
 * owns the company, how the share moves against its index, and which way the
 * street revised since its last count. Each is pinned here together with the
 * reason it is worded the way it is - "relative" must say what it is relative
 * TO, and the upside against our price must be declared house-computed.
 */
class DeepDiveOnvistaLegsTest {

    private static DeepDiveService.Material bare() {
        DeepDiveService.Material m = new DeepDiveService.Material();
        m.canonicalName = "SAP SE";
        m.ticker = "SAP.DE";
        m.isin = "DE0007164600";
        return m;
    }

    private static CompanySnapshot company(List<Shareholder> holders,
            List<Participation> parts) {
        return new CompanySnapshot("SAP SE", "SAP SE", "AG", "Deutschland", "Software",
                "Technologie", "https://www.sap.com", "portrait", 1.17e9, 1.84e11, "EUR",
                78.4, "31.12.", 2025, holders, List.of(), List.of(), parts, List.of());
    }

    // ---- ownership ---------------------------------------------------------

    @Test
    void ownershipReachesTheAboutShelfWithFreeFloatFirst() {
        DeepDiveService.Material m = bare();
        m.onvistaCompany = company(
                List.of(new Shareholder("Streubesitz", 78.4),
                        new Shareholder("Hasso Plattner", 6.2),
                        new Shareholder("BlackRock Inc.", 5.1)),
                List.of(new Participation("Qualtrics International", 71.0,
                        new OnvistaEntity("STOCK", "137712", "Qualtrics", "US7476012015",
                                "A2QG7T", "XM"))));

        var nums = DeepDiveService.sourceNumbers(m);
        assertTrue(nums.containsKey("onvista"));
        String shelf = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_ABOUT];

        assertContains(shelf, "OWNERSHIP (verified, onvista)");
        assertContains(shelf, "[" + nums.get("onvista") + "]");
        assertContains(shelf, "free float: 78.40%");
        assertContains(shelf, "Hasso Plattner: 6.20%");
        assertContains(shelf, "BlackRock Inc.: 5.10%");
        assertContains(shelf, "holds a stake in Qualtrics International: 71.00%");
        assertContains(DeepDiveService.sourcesSection(m, true),
                "[" + nums.get("onvista") + "] onvista");
    }

    /** The free-float row must not be counted twice - it leads, it is not a holder. */
    @Test
    void theFreeFloatRowIsNotAlsoListedAsAHolder() {
        DeepDiveService.Material m = bare();
        m.onvistaCompany = company(
                List.of(new Shareholder("Streubesitz", 78.4),
                        new Shareholder("Hasso Plattner", 6.2)),
                List.of());
        String shelf = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_ABOUT];
        assertTrue(shelf.indexOf("Streubesitz") < 0, shelf);
        assertContains(shelf, "free float: 78.40%");
    }

    /** An answer with nothing usable in it earns no heading at all. */
    @Test
    void anEmptyOwnershipTableProducesNoBlock() {
        DeepDiveService.Material m = bare();
        m.onvistaCompany = company(List.of(), List.of());
        String shelf = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_ABOUT];
        assertTrue(shelf == null || !shelf.contains("OWNERSHIP"), String.valueOf(shelf));
    }

    // ---- benchmark fit -----------------------------------------------------

    @Test
    void benchmarkFitLandsOnTheSituationShelfAndNamesItsIndex() {
        DeepDiveService.Material m = bare();
        m.onvistaBenchmark = new BenchmarkFit(
                new OnvistaEntity("INDEX", "20735", "DAX", "DE0008469008", "846900", "DAX"),
                true, 1.08, 0.94, 0.71, 0.83, -1.42, 3.60, 12.05);

        var nums = DeepDiveService.sourceNumbers(m);
        String shelf = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_SITUATION];

        assertContains(shelf, "BEHAVIOUR AGAINST DAX (verified, onvista");
        assertContains(shelf, "[" + nums.get("onvista") + "]");
        assertContains(shelf, "beta 250d 0.94");
        assertContains(shelf, "correlation 30d 0.71");
        // The direction IS the statement, so the sign is always written out.
        assertContains(shelf, "relative 1W -1.42%");
        assertContains(shelf, "relative 1Y +12.05%");
        // And the reading instruction travels with the number.
        assertContains(shelf, "performance MINUS the index over that window");
    }

    // ---- street drift ------------------------------------------------------

    @Test
    void consensusDriftCarriesTheRevisionsAndAHouseComputedUpside() {
        DeepDiveService.Material m = bare();
        m.snapshot = new MarketSnapshot("DE0007164600", 150.0, 148.0, 1.35, 151.0, 149.0,
                1_000_000L, 220.0, 130.0, "EUR", "Lang & Schwarz", 0L, List.of(), List.of());
        m.onvistaConsensus = new AnalystConsensus(1.8, 30, 9, 8, 10, 2, 1, 17, 3,
                4, 1, 25, 5.67, 180.0, 140.0, 230.0, "EUR");

        var nums = DeepDiveService.sourceNumbers(m);
        String shelf = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_VALUATION];

        assertContains(shelf, "STREET DRIFT (verified, onvista)");
        assertContains(shelf, "[" + nums.get("onvista") + "]");
        assertContains(shelf, "30 analysts (17 positive, 10 hold, 3 negative)");
        assertContains(shelf, "since the previous count 4 raised and 1 cut (net +3)");
        assertContains(shelf, "average target 180.00 EUR (band 140.00 to 230.00)");
        assertContains(shelf, "+20.00% against the price on this shelf (house-computed)");
    }

    /** Without a price on the shelf there is no upside to state - and none is stated. */
    @Test
    void theUpsideIsSilentWithoutAPrice() {
        DeepDiveService.Material m = bare();
        m.onvistaConsensus = new AnalystConsensus(1.8, 12, 3, 3, 5, 1, 0, 6, 1,
                0, 0, 12, 6.0, 180.0, 140.0, 230.0, "EUR");
        String shelf = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_VALUATION];
        assertContains(shelf, "STREET DRIFT (verified, onvista)");
        assertFalse(shelf.contains("house-computed"), shelf);
        // No revisions since the last count means no drift clause either.
        assertFalse(shelf.contains("since the previous count"), shelf);
    }

    /** A tally with no analysts behind it is not a tally. */
    @Test
    void anEmptyTallyProducesNoBlock() {
        DeepDiveService.Material m = bare();
        m.onvistaConsensus = new AnalystConsensus(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, Double.NaN, Double.NaN, Double.NaN, null);
        String shelf = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_VALUATION];
        assertTrue(shelf == null || !shelf.contains("STREET DRIFT"), String.valueOf(shelf));
    }

    // ---- absence -----------------------------------------------------------

    @Test
    void withoutTheLegNothingChanges() {
        DeepDiveService.Material m = bare();
        assertFalse(DeepDiveService.sourceNumbers(m).containsKey("onvista"));
        for (String shelf : DeepDiveService.sectionMaterials(m)) {
            if (shelf == null) continue;
            assertFalse(shelf.contains("OWNERSHIP (verified, onvista)"), shelf);
            assertFalse(shelf.contains("BEHAVIOUR AGAINST"), shelf);
            assertFalse(shelf.contains("STREET DRIFT"), shelf);
        }
    }

    private static void assertContains(String haystack, String needle) {
        assertTrue(haystack != null && haystack.contains(needle),
                "expected to find:\n  " + needle + "\nin:\n" + haystack);
    }
}
