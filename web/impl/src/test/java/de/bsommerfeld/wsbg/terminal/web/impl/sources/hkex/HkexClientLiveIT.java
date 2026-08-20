package de.bsommerfeld.wsbg.terminal.web.impl.sources.hkex;

import de.bsommerfeld.wsbg.terminal.web.impl.net.DirectTransport;
import de.bsommerfeld.wsbg.terminal.web.impl.net.HouseFetcher;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live probe against the real service - the only isolated verification this
 * house accepts (no synthetic fixtures for pipeline truth). Exercises the
 * full token dance: page pull, token cache, both widget endpoints. Run with:
 * {@code HKEX_SMOKE=true mvn test -pl web/impl -Dtest=HkexClientLiveIT
 * -Dtest.excludedGroups=}
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "HKEX_SMOKE", matches = "true")
class HkexClientLiveIT {

    @Test
    void tencentAnswersWithQuoteAndTenYearsOfBars() {
        HkexClient client = new HkexClient(
                new HouseFetcher(java.util.Set.of(new DirectTransport())));
        HkexQuote q = client.quote("700").orElseThrow();
        assertEquals("700", q.symbol());
        assertTrue(q.last() > 0, "a live quote has a price");
        assertTrue(q.volume() > 0 && q.turnover() > 0, "and a session behind it");
        assertTrue(q.marketCap() > 1e12, "Tencent weighs trillions of HKD");

        // Same client, cached token - the page is not pulled a second time.
        HkexHistory h = client.history("700").orElseThrow();
        assertEquals("0700.HK", h.ric());
        assertTrue(h.bars().size() > 2000, "roughly ten years of daily bars");
        var last = h.bars().get(h.bars().size() - 1);
        assertTrue(last.volume() > 0, "bars carry venue-native volume");
        assertTrue(h.bars().get(0).date().isBefore(last.date()), "oldest first");
    }
}
