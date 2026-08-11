package de.bsommerfeld.wsbg.terminal.briefing;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;



import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FRED, live: the keyless CSV export against the real host. What only a live
 * probe can answer is whether the export still needs no key and still carries
 * the observation rows - both of which are the whole reason this leg exists.
 *
 * <pre>FRED_SMOKE=true mvn test -pl briefing -Dtest=FredSeriesSmokeIT -Dtest.excludedGroups=</pre>
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "FRED_SMOKE", matches = "true")
class FredSeriesSmokeIT {

    /**
     * The whole spine. The host was long thought walled - it answers a client
     * that sends a browser's FULL header set and asks for compression, and
     * refuses one that sends either half alone (measured 2026-08-11). A silent
     * series here therefore means the transport regressed, not that FRED is
     * unreachable.
     */
    @Test
    void theKeylessExportCarriesTheWholeSpine() {
        java.util.Map<String, FredSeriesClient.Reihe> spine =
                new FredSeriesClient().spineSeries(400);
        for (var e : spine.entrySet()) {
            FredSeriesClient.Punkt last = e.getValue().letzter();
            System.out.printf("[fred] %-14s %-46s %5d Punkte, zuletzt %s = %s%n",
                    e.getKey(), e.getValue().titel(), e.getValue().punkte().size(),
                    last.datum(), last.wert());
        }
        assertFalse(spine.isEmpty(), "FRED answered nothing at all");
        assertEquals(FredSeriesClient.SPINE.size(), spine.size(),
                "only " + spine.size() + " of " + FredSeriesClient.SPINE.size()
                        + " spine series answered");
        FredSeriesClient.Punkt zehnJahre = spine.get("DGS10").letzter();
        assertTrue(zehnJahre.datum().isAfter(java.time.LocalDate.now().minusMonths(2)),
                "the newest observation is stale: " + zehnJahre.datum());
    }
}
