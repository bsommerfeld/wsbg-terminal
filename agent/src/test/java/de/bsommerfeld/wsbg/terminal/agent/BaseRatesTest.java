package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.db.MarketEventRecord;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The base-rate statistics and their license gates: confounded events
 * excluded, thin samples carry no mean, regime slicing filters by band.
 */
class BaseRatesTest {

    private static MarketEventRecord event(String eventClass, Double car, String band,
            Boolean confounded, int i) {
        return new MarketEventRecord("2026-01-" + String.format("%02d", (i % 27) + 1),
                "SYM" + i, null, eventClass, "TEST", null, band, null, car, car, "^GSPC",
                confounded);
    }

    @Test
    void statsExcludeConfoundedAndUnmeasuredEvents() {
        List<MarketEventRecord> events = new ArrayList<>();
        events.add(event("GEWINNWARNUNG", -8.0, null, null, 1));
        events.add(event("GEWINNWARNUNG", -12.0, null, false, 2));
        events.add(event("GEWINNWARNUNG", -4.0, null, null, 3));
        events.add(event("GEWINNWARNUNG", -6.0, null, null, 4));
        events.add(event("GEWINNWARNUNG", 2.0, null, null, 5));
        events.add(event("GEWINNWARNUNG", -99.0, null, true, 6));   // confounded — out
        events.add(event("GEWINNWARNUNG", null, null, null, 7));    // unmeasured — out
        events.add(event("UPGRADE", 3.0, null, null, 8));           // other class — out

        BaseRates.Stats s = BaseRates.forClass("GEWINNWARNUNG", events, null).orElseThrow();
        assertEquals(5, s.n());
        assertEquals(-6.0, s.medianPct(), 1e-9);
        assertEquals(0.8, s.negativeShare(), 1e-9);
        assertEquals(-12.0, s.minPct(), 1e-9);
        assertEquals(2.0, s.maxPct(), 1e-9);
        assertFalse(s.licensesMean());
        assertTrue(BaseRates.describe(s).contains("thin sample"));
        assertFalse(BaseRates.describe(s).contains("mean"));
    }

    @Test
    void bigSampleLicensesTheMean() {
        List<MarketEventRecord> events = new ArrayList<>();
        for (int i = 0; i < 40; i++) events.add(event("DOWNGRADE", -5.0, null, null, i));
        BaseRates.Stats s = BaseRates.forClass("DOWNGRADE", events, null).orElseThrow();
        assertTrue(s.licensesMean());
        String line = BaseRates.describe(s);
        assertTrue(line.contains("N=40"));
        assertTrue(line.contains("mean -5.0 %"));
        assertFalse(line.contains("thin sample"));
    }

    @Test
    void regimeSliceFiltersByBand() {
        List<MarketEventRecord> events = new ArrayList<>();
        for (int i = 0; i < 10; i++) events.add(event("DOWNGRADE", -8.0, "FEAR", null, i));
        for (int i = 10; i < 20; i++) events.add(event("DOWNGRADE", -2.0, "GREED", null, i));

        BaseRates.Stats fear = BaseRates.forClass("DOWNGRADE", events, "FEAR").orElseThrow();
        assertEquals(10, fear.n());
        assertEquals(-8.0, fear.medianPct(), 1e-9);
        assertTrue(BaseRates.describe(fear).contains("Regime FEAR"));

        // Too few events in a band that barely occurs → empty, caller falls
        // back to the unconditioned rate.
        assertTrue(BaseRates.forClass("DOWNGRADE", events, "EXTREME_FEAR").isEmpty());
    }

    @Test
    void tinySamplesYieldNothingAtAll() {
        List<MarketEventRecord> events = new ArrayList<>();
        for (int i = 0; i < BaseRates.MIN_N_FOR_ANY - 1; i++) {
            events.add(event("INSOLVENZ", -30.0, null, null, i));
        }
        assertTrue(BaseRates.forClass("INSOLVENZ", events, null).isEmpty());
    }
}
