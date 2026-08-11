package de.bsommerfeld.wsbg.terminal.web.impl.sources.nordic;

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
 * {@code NORDIC_SMOKE=true mvn test -pl web-impl -Dtest=NordicMarketClientLiveIT
 * -Dtest.excludedGroups=}
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "NORDIC_SMOKE", matches = "true")
class NordicMarketClientLiveIT {

    private static NordicMarketClient client() {
        return new NordicMarketClient(new HouseFetcher(Set.of(new DirectTransport())));
    }

    @Test
    void volvoAnswersWithAQuoteInItsHomeCurrency() {
        NordicQuote q = client().quoteByIsin("SE0000115446").orElseThrow();
        assertEquals("SE0000115446", q.isin());
        assertEquals("SEK", q.currency());
        assertTrue(q.last() > 0, "a live quote has a price");
        assertTrue(q.volume() > 0, "and venue-native volume");
    }

    @Test
    void volvoAnswersWithYearsOfBars() {
        NordicHistory h = client()
                .historyByIsin("SE0000115446", LocalDate.of(2016, 8, 1))
                .orElseThrow();
        assertEquals("SE0000115446", h.isin());
        assertEquals("SEK", h.currency());
        assertTrue(h.bars().size() > 2000, "roughly ten years of daily bars");
        var first = h.bars().get(0);
        var last = h.bars().get(h.bars().size() - 1);
        assertTrue(first.date().isBefore(last.date()), "oldest first");
        assertTrue(last.close() > 0 && last.volume() > 0,
                "bars carry venue-native close and volume");
    }
}
