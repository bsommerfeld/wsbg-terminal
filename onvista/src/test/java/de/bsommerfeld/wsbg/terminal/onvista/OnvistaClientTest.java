package de.bsommerfeld.wsbg.terminal.onvista;

import de.bsommerfeld.wsbg.terminal.core.price.InstrumentFacts;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Network-free parse tests against trimmed live captures (probed 2026-07-12).
 * The behaviours that matter: STOCK-only entity pick (a FUND ISIN is a definite
 * miss), latest-ACTUAL-first valuation with estimate fallback (labels ride
 * along), and the last actual employees figure.
 */
class OnvistaClientTest {

    private final OnvistaClient client = new OnvistaClient();

    /** Trimmed live query reply: Rheinmetall by ISIN. */
    private static final String QUERY_STOCK = """
            {"searchValue":"DE0007030009","list":[
              {"type":"Instrument","entityType":"STOCK","entityValue":"82811",
               "name":"Rheinmetall","isin":"DE0007030009","wkn":"703000","symbol":"RHM"}]}""";

    /** Trimmed live query reply: an ETF resolves as FUND — definite miss. */
    private static final String QUERY_FUND = """
            {"searchValue":"IE00B4L5Y983","list":[
              {"type":"Instrument","entityType":"FUND","entityValue":"25096683",
               "name":"iShares Core MSCI World UCITS ETF USD Acc.","isin":"IE00B4L5Y983"}]}""";

    /**
     * Trimmed live snapshot (NVIDIA shape): actuals carry cnPer/cnDivYield/employees,
     * estimate rows follow — the LATEST ACTUAL must win, employees from the last
     * actual row that has them.
     */
    private static final String SNAPSHOT_WITH_ACTUALS = """
            {"company":{"name":"NVIDIA CORP.","nameCountry":"USA",
                        "branch":{"name":"Halbleiterindustrie","sector":{"name":"Technologie"}}},
             "stocksFigure":{"marketCapCompany":4466264060040.0,"isoCurrency":"EUR"},
             "cnPerformance":{"averageVolumeD30":160275878.429},
             "stocksCnFundamentalList":{"list":[
               {"label":"24/25","cnPer":63.43,"cnDivYield":0.018,"employees":36000},
               {"label":"25/26","cnPer":41.38,"cnDivYield":0.019,"employees":42000},
               {"label":"26/27e","cnPer":15.71,"cnDivYield":0.387},
               {"label":"27/28e","cnPer":12.93,"cnDivYield":0.411}]}}""";

    /** Estimate-only valuation (Rheinmetall shape): fallback to the NEAREST estimate. */
    private static final String SNAPSHOT_ESTIMATES_ONLY = """
            {"company":{"name":"RHEINMETALL AG","nameCountry":"Deutschland",
                        "branch":{"name":"Maschinenbau","sector":{"name":"Industrie"}}},
             "stocksFigure":{"marketCapCompany":46357099545.6,"isoCurrency":"EUR"},
             "cnPerformance":{"averageVolumeD30":298247.9},
             "stocksCnFundamentalList":{"list":[
               {"label":"2025","employees":32251},
               {"label":"2027e","cnPer":19.35,"cnDivYield":2.19},
               {"label":"2028e","cnPer":16.02,"cnDivYield":2.45}]}}""";

    @Test
    void picksStockEntityByIsin() {
        assertEquals("82811", client.parseStockEntity(QUERY_STOCK, "DE0007030009"));
        assertNull(client.parseStockEntity(QUERY_STOCK, "US0000000000"));
    }

    @Test
    void fundIsADefiniteMiss() {
        assertNull(client.parseStockEntity(QUERY_FUND, "IE00B4L5Y983"));
    }

    @Test
    void latestActualValuationWins() {
        InstrumentFacts f = client.parseSnapshot(SNAPSHOT_WITH_ACTUALS).orElseThrow();
        assertEquals("NVIDIA CORP.", f.companyName());
        assertEquals("USA", f.country());
        assertEquals("Technologie", f.sector());
        assertEquals("Halbleiterindustrie", f.branch());
        assertEquals(41.38, f.peRatio(), 1e-9);
        assertEquals("25/26", f.peLabel());
        assertEquals(0.019, f.divYieldPercent(), 1e-9);
        assertEquals(42000, f.employees());
        assertEquals("25/26", f.employeesLabel());
        assertEquals(4466264060040.0, f.marketCapEur(), 1e-3);
        assertEquals(160275878.429, f.avgVolume30d(), 1e-3);
    }

    @Test
    void fallsBackToNearestEstimateWithLabel() {
        InstrumentFacts f = client.parseSnapshot(SNAPSHOT_ESTIMATES_ONLY).orElseThrow();
        assertEquals(19.35, f.peRatio(), 1e-9);
        assertEquals("2027e", f.peLabel());
        assertEquals(2.19, f.divYieldPercent(), 1e-9);
        assertEquals("2027e", f.divLabel());
        assertEquals(32251, f.employees());
        assertEquals("2025", f.employeesLabel());
    }

    @Test
    void emptyOrProfilelessSnapshotIsEmpty() {
        assertFalse(client.parseSnapshot("{}").isPresent());
        assertFalse(client.parseSnapshot("not json").isPresent());
        assertTrue(client.parseSnapshot(
                "{\"company\":{\"name\":\"X\"},\"stocksCnFundamentalList\":{\"list\":[]}}").isPresent());
    }
}
