package de.bsommerfeld.wsbg.terminal.web.impl.sources.tmx;

import de.bsommerfeld.wsbg.terminal.web.impl.net.DirectTransport;
import de.bsommerfeld.wsbg.terminal.web.impl.net.HouseFetcher;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.LocalDate;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live probe against the real service - the only isolated verification this
 * house accepts (no synthetic fixtures for pipeline truth). Run with:
 * {@code TMX_SMOKE=true mvn test -pl web-impl -Dtest=TmxMarketClientLiveIT
 * -Dtest.excludedGroups=}
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "TMX_SMOKE", matches = "true")
class TmxMarketClientLiveIT {

    private static TmxMarketClient client() {
        return new TmxMarketClient(new HouseFetcher(Set.of(new DirectTransport())));
    }

    @Test
    void shopifyAnswersWithALiveCadQuote() {
        TmxQuote q = client().quote("SHOP").orElseThrow();
        assertEquals("SHOP", q.symbol());
        assertEquals("CAD", q.currency(), "the Toronto tape quotes in CAD");
        assertTrue(q.price() > 0 && q.volume() > 0, "a live quote has price and volume");
        assertTrue(q.yearHigh() >= q.yearLow() && q.yearLow() > 0, "a real 52-week range");
        assertTrue(q.marketCap() > 0, "a listed company has a cap");
    }

    @Test
    void shopifyAnswersWithADecadeOfBars() {
        TmxHistory h = client()
                .history("SHOP", LocalDate.of(2016, 8, 1)).orElseThrow();
        assertEquals("SHOP", h.symbol());
        assertTrue(h.bars().size() > 2000, "roughly a decade of daily bars");
        var first = h.bars().get(0);
        var last = h.bars().get(h.bars().size() - 1);
        assertTrue(first.date().isBefore(last.date()), "oldest first");
        assertTrue(last.close() > 0 && last.volume() > 0, "bars carry venue-native volume");
    }
}
