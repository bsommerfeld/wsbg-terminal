package de.bsommerfeld.wsbg.terminal.asx;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live probe against the real Markit backends - the only isolated
 * verification this house accepts (no synthetic fixtures for pipeline
 * truth). Run with:
 * {@code ASX_SMOKE=true mvn test -pl asx -Dtest=AsxMarketClientLiveIT
 * -Dtest.excludedGroups=}
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "ASX_SMOKE", matches = "true")
class AsxMarketClientLiveIT {

    @Test
    void bhpAnswersWithQuoteAndXid() {
        AsxQuote q = new AsxMarketClient().quote("BHP").orElseThrow();
        assertEquals("BHP", q.symbol());
        assertTrue(q.priceLast() > 0, "a live quote has a price");
        assertTrue(q.xid() != null && !q.xid().isBlank(), "the xid keys the chart backend");
        assertTrue(q.marketCap() > 0, "a major miner has a market cap");
    }

    @Test
    void bhpCarriesTwentyYearsOfBarsWithVolume() {
        AsxHistory h = new AsxMarketClient().history("BHP", 7300).orElseThrow();
        assertEquals("BHP", h.symbol());
        assertEquals("AUD", h.currency());
        assertTrue(h.bars().size() > 4000, "roughly twenty years of daily bars");
        var first = h.bars().get(0);
        var last = h.bars().get(h.bars().size() - 1);
        assertTrue(first.date().isBefore(last.date()), "oldest first");
        assertTrue(last.close() > 0 && last.volume() > 0,
                "bars carry venue-native volume");
    }
}
