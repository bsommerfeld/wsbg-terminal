package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.briefing.CoinGeckoClient;
import de.bsommerfeld.wsbg.terminal.onvista.OnvistaMarketClient.YearPerformance;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The last three dormant surfaces (wired 2026-08-03): onvista's calendar-year
 * performance, the total crypto market value, and the flight leg - which needs
 * a position the DD has no business inventing and therefore asks about the
 * active hazard instead.
 *
 * <p>The calendar years are the point of this batch. Every other performance
 * reading in the report is a window ending today; this is the only one that
 * can answer "and what did it do in 2008?", which is the question a long-arc
 * thesis actually rests on.
 */
class DeepDiveLongArcTest {

    private static DeepDiveService.Material bare() {
        DeepDiveService.Material m = new DeepDiveService.Material();
        m.canonicalName = "SAP SE";
        m.ticker = "SAP.DE";
        m.isin = "DE0007164600";
        return m;
    }

    @Test
    void calendarYearsRunOldestFirstAndSayTheyAreNotAWindow() {
        DeepDiveService.Material m = bare();
        m.calendarYears = List.of(
                new YearPerformance(2024, 138.0, 172.0, 34.0, 24.64),
                new YearPerformance(2008, 33.0, 25.0, -8.0, -24.24),
                new YearPerformance(2025, 172.0, 151.0, -21.0, -12.21));

        var nums = DeepDiveService.sourceNumbers(m);
        assertTrue(nums.containsKey("onvista"));
        String shelf = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_SITUATION];

        assertContains(shelf, "CALENDAR-YEAR PERFORMANCE (verified, onvista");
        assertContains(shelf, "[" + nums.get("onvista") + "]");
        assertContains(shelf, "2008: -24.24% (33.00 to 25.00)");
        assertContains(shelf, "2025: -12.21% (172.00 to 151.00)");
        // Oldest first, so the rows read as an arc.
        assertTrue(shelf.indexOf("2008:") < shelf.indexOf("2024:"), shelf);
        assertTrue(shelf.indexOf("2024:") < shelf.indexOf("2025:"), shelf);
        // And the block distinguishes itself from every other performance line.
        assertContains(shelf, "not a window ending today");
    }

    /** A long venue history is trimmed to an arc, keeping the RECENT years. */
    @Test
    void theYearCapKeepsTheRecentEnd() {
        DeepDiveService.Material m = bare();
        List<YearPerformance> years = new ArrayList<>();
        for (int y = 1995; y <= 2025; y++) {
            years.add(new YearPerformance(y, 10, 11, 1, 10.0));
        }
        m.calendarYears = List.copyOf(years);
        String shelf = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_SITUATION];
        assertFalse(shelf.contains("1995:"), shelf);
        assertContains(shelf, "2025:");
        assertContains(shelf, "2014:");
    }

    @Test
    void theCryptoTideJoinsTheRegimeBlock() {
        DeepDiveService.Material m = bare();
        m.cryptoGlobal = new CoinGeckoClient.CryptoGlobal(3.42e12, -2.35, 54.2);

        var nums = DeepDiveService.sourceNumbers(m);
        assertTrue(nums.containsKey("regime"));
        String shelf = DeepDiveService.sectionMaterials(m)[DeepDiveService.SEC_SITUATION];
        assertContains(shelf, "the speculative tide (total crypto market value)");
        assertContains(shelf, "-2.35% in 24 h");
    }

    @Test
    void withoutTheLegsNothingChanges() {
        DeepDiveService.Material m = bare();
        for (String shelf : DeepDiveService.sectionMaterials(m)) {
            if (shelf == null) continue;
            assertFalse(shelf.contains("CALENDAR-YEAR PERFORMANCE"), shelf);
            assertFalse(shelf.contains("speculative tide"), shelf);
        }
    }

    private static void assertContains(String haystack, String needle) {
        assertTrue(haystack != null && haystack.contains(needle),
                "expected to find:\n  " + needle + "\nin:\n" + haystack);
    }
}
