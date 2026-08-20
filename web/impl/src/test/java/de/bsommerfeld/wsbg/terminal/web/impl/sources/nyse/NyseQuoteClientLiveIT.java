package de.bsommerfeld.wsbg.terminal.web.impl.sources.nyse;

import de.bsommerfeld.wsbg.terminal.web.impl.net.DirectTransport;
import de.bsommerfeld.wsbg.terminal.web.impl.net.HouseFetcher;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live probe against the real service - the only isolated verification this
 * house accepts (no synthetic fixtures for pipeline truth). Run with:
 * {@code NYSE_SMOKE=true mvn test -pl web/impl -Dtest=NyseQuoteClientLiveIT
 * -Dtest.excludedGroups=}
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "NYSE_SMOKE", matches = "true")
class NyseQuoteClientLiveIT {

    private static NyseQuoteClient client() {
        return new NyseQuoteClient(new HouseFetcher(java.util.Set.of(new DirectTransport())));
    }

    @Test
    void appleAnswersWithQuoteAndYearsOfBars() {
        NyseTape t = client().tape("AAPL").orElseThrow();
        assertEquals("AAPL", t.symbol());
        assertTrue(t.last() > 0 && t.volume() > 0, "a live tape has price and volume");
        assertTrue(t.bid() > 0 && t.ask() > 0 && t.ask() >= t.bid(), "a real book");
        assertTrue(t.history().size() > 1000, "roughly five years of daily bars");
        var last = t.history().get(t.history().size() - 1);
        assertTrue(last.volume() > 0, "bars carry venue-native volume");
    }

    @Test
    void aTrueNyseListingAnswersToo() {
        NyseTape t = client().tape("KO").orElseThrow();
        assertEquals("NYSE", t.exchange());
        assertTrue(t.history().size() > 1000);
    }
}
