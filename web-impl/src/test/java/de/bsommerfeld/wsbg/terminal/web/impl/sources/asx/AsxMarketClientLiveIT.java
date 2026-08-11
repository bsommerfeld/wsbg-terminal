package de.bsommerfeld.wsbg.terminal.web.impl.sources.asx;

import de.bsommerfeld.wsbg.terminal.web.impl.net.DirectTransport;
import de.bsommerfeld.wsbg.terminal.web.impl.net.HouseFetcher;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live probe against the real Markit backends - the only isolated
 * verification this house accepts (no synthetic fixtures for pipeline
 * truth). Run with:
 * {@code ASX_SMOKE=true mvn test -pl web-impl -Dtest=AsxMarketClientLiveIT
 * -Dtest.excludedGroups=}
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "ASX_SMOKE", matches = "true")
class AsxMarketClientLiveIT {

    private static AsxMarketClient client() {
        return new AsxMarketClient(new HouseFetcher(Set.of(new DirectTransport())));
    }

    @Test
    void bhpAnswersWithQuoteAndXid() {
        AsxQuote q = client().quote("BHP").orElseThrow();
        assertEquals("BHP", q.symbol());
        assertTrue(q.priceLast() > 0, "a live quote has a price");
        assertTrue(q.xid() != null && !q.xid().isBlank(), "the xid keys the chart backend");
        assertTrue(q.marketCap() > 0, "a major miner has a market cap");
    }

    @Test
    void bhpCarriesTwentyYearsOfBarsWithVolume() {
        AsxHistory h = client().history("BHP", 7300).orElseThrow();
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
