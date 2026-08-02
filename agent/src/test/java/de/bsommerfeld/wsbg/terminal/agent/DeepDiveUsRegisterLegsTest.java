package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.briefing.FinraShortVolumeClient;
import de.bsommerfeld.wsbg.terminal.briefing.NasdaqCalendarClient;
import de.bsommerfeld.wsbg.terminal.edgar.EdgarClient;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Three legs the house already owned but the KI-DD never saw (wired
 * 2026-08-03): SEC EDGAR's dated 8-K events, FINRA's daily short VOLUME and
 * NASDAQ's earnings calendar.
 *
 * <p>What is pinned here is less the rendering than the reasons these legs are
 * shaped the way they are: short volume must never read as short interest, the
 * NASDAQ calendar must not run when the date is already known, and a symbol
 * that is not a US listing must reach neither of the two - a stripped venue
 * suffix would hit a real but unrelated US twin.
 */
class DeepDiveUsRegisterLegsTest {

    private static DeepDiveService.Material us() {
        DeepDiveService.Material m = new DeepDiveService.Material();
        m.canonicalName = "Outlook Therapeutics";
        m.ticker = "OTLK";
        return m;
    }

    // ---- SEC EDGAR: the issuer's own material events -----------------------

    @Test
    void edgarEventsReachTheCatalystShelfWithTheirItemCodes() {
        DeepDiveService.Material m = us();
        m.edgarEvents = List.of(
                new EdgarClient.EdgarEvent(LocalDate.of(2026, 7, 30), "OTLK",
                        "FUEHRUNGSWECHSEL", "5.02,9.01"),
                new EdgarClient.EdgarEvent(LocalDate.of(2026, 6, 11), "OTLK",
                        "VERWAESSERUNG", "3.02"));

        var nums = DeepDiveService.sourceNumbers(m);
        assertTrue(nums.containsKey("edgar"), "a delivered filing history earns a source number");
        String shelf = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_CATALYSTS];

        assertContains(shelf, "MATERIAL FILINGS (verified, SEC EDGAR form 8-K");
        assertContains(shelf, "[" + nums.get("edgar") + "]");
        assertContains(shelf, "[2026-07-30] FUEHRUNGSWECHSEL (item 5.02,9.01)");
        assertContains(shelf, "[2026-06-11] VERWAESSERUNG (item 3.02)");
        // The register must resolve the marker, or the figure is unsourced.
        assertContains(DeepDiveService.sourcesSection(m, true),
                "[" + nums.get("edgar") + "] SEC EDGAR");
    }

    /**
     * The block says whose mapping the class name is. Without that the model
     * would quote "IMPAIRMENT" as if the SEC had written it.
     */
    @Test
    void theEventClassIsDeclaredAsThisHousesMapping() {
        DeepDiveService.Material m = us();
        m.edgarEvents = List.of(new EdgarClient.EdgarEvent(
                LocalDate.of(2026, 5, 4), "OTLK", "IMPAIRMENT", "2.06"));
        String shelf = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_CATALYSTS];
        assertContains(shelf, "this house's mapping of the filing's own item codes");
    }

    // ---- FINRA: short VOLUME, and the caveat that travels with it ----------

    @Test
    void shortVolumeLandsBesideTheShortInterestReadingsWithItsCaveat() {
        DeepDiveService.Material m = us();
        m.shortVolume = new FinraShortVolumeClient.ShortVolume("OTLK", 62.37, 4_820_115L,
                "2026-08-01");

        var nums = DeepDiveService.sourceNumbers(m);
        String shelf = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_CATALYSTS];

        assertContains(shelf, "DAILY SHORT VOLUME (verified, FINRA consolidated file, "
                + "session 2026-08-01)");
        assertContains(shelf, "[" + nums.get("finra") + "]");
        assertContains(shelf, "62.37% of the session's consolidated volume ran over the "
                + "short side");
        assertContains(shelf, "(total 4820115 shares)");
        // The whole point of the separate block: it must not read as a position.
        assertContains(shelf, "This is short VOLUME, not short INTEREST");
        assertContains(shelf, "must not be read as one");
        assertContains(DeepDiveService.sourcesSection(m, false),
                "[" + nums.get("finra") + "] FINRA");
    }

    // ---- NASDAQ calendar: the third-in-line report date --------------------

    @Test
    void nasdaqReportDateAnchorsTheOutlookWithSlotAndEstimate() {
        DeepDiveService.Material m = us();
        m.nasdaqEarnings = new NasdaqCalendarClient.EarningsEntry("OTLK",
                "Outlook Therapeutics Inc", "time-after-hours", "$-0.21", "$412,000,000");
        m.nasdaqEarningsDateIso = "2026-08-12";

        var nums = DeepDiveService.sourceNumbers(m);
        String shelf = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_OUTLOOK];

        assertContains(shelf, "NEXT REPORT (verified, NASDAQ earnings calendar)");
        assertContains(shelf, "[" + nums.get("nasdaqcal") + "]");
        assertContains(shelf, "2026-08-12, after the bell");
        assertContains(shelf, "street estimate per share $-0.21");
    }

    /** An unknown slot token stays silent rather than guessing a bell side. */
    @Test
    void anUnsuppliedSlotIsNotInvented() {
        DeepDiveService.Material m = us();
        m.nasdaqEarnings = new NasdaqCalendarClient.EarningsEntry("OTLK", "Outlook",
                "time-not-supplied", "", "");
        m.nasdaqEarningsDateIso = "2026-08-12";
        String shelf = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_OUTLOOK];
        assertContains(shelf, "NEXT REPORT (verified, NASDAQ earnings calendar)");
        assertFalse(shelf.contains("before the bell"), shelf);
        assertFalse(shelf.contains("after the bell"), shelf);
        assertFalse(shelf.contains("street estimate per share"), shelf);
    }

    // ---- the US-shape gate -------------------------------------------------

    /**
     * FINRA's file and NASDAQ's calendar are keyed on the US listing. A venue
     * suffix, an index, a future or a crypto pair is not one - and stripping
     * the suffix would address a real but unrelated US symbol, which is worse
     * than no data at all.
     */
    @Test
    void onlyBareUsSymbolsReachTheUsKeyedLegs() {
        assertEquals("OTLK", DeepDiveService.usTicker("otlk"));
        assertEquals("BRK.A", DeepDiveService.usTicker("BRK.A"));
        for (String notUs : List.of("RHM.DE", "SAP.DE", "^GSPC", "CL=F", "BTC-USD",
                "TOOLONGSYM", "", " ")) {
            assertNull(DeepDiveService.usTicker(notUs), notUs);
        }
        assertNull(DeepDiveService.usTicker(null));
    }

    // ---- absence stays cheap ----------------------------------------------

    /** No leg, no block, no source number - and nothing else changes. */
    @Test
    void absentLegsCostOnlyTheirOwnBlock() {
        DeepDiveService.Material m = us();
        var nums = DeepDiveService.sourceNumbers(m);
        assertFalse(nums.containsKey("edgar"));
        assertFalse(nums.containsKey("finra"));
        assertFalse(nums.containsKey("nasdaqcal"));
        String catalysts = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_CATALYSTS];
        String outlook = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_OUTLOOK];
        assertTrue(catalysts == null || !catalysts.contains("MATERIAL FILINGS"));
        assertTrue(catalysts == null || !catalysts.contains("DAILY SHORT VOLUME"));
        assertTrue(outlook == null || !outlook.contains("NEXT REPORT (verified, NASDAQ"));
    }

    private static void assertContains(String haystack, String needle) {
        assertTrue(haystack != null && haystack.contains(needle),
                "expected to find:\n  " + needle + "\nin:\n" + haystack);
    }
}
