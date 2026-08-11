package de.bsommerfeld.wsbg.terminal.six;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live probe against the real services - the only isolated verification this
 * house accepts (no synthetic fixtures for pipeline truth). Run with:
 * {@code SIX_SMOKE=true mvn test -pl six -Dtest=SixMarketClientLiveIT
 * -Dtest.excludedGroups=}
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "SIX_SMOKE", matches = "true")
class SixMarketClientLiveIT {

    @Test
    void nestleAnswersWithAQuote() {
        SixQuote q = new SixMarketClient().quote("CH0038863350").orElseThrow();
        assertEquals("CH0038863350", q.isin());
        assertEquals("NESN", q.symbol());
        assertTrue(q.last() > 0, "a live quote has a price");
        assertTrue(q.volume() > 0, "a live quote has shares traded");
    }

    @Test
    void nestleAnswersWithADecadeOfBars() {
        SixHistory h = new SixMarketClient()
                .history("CH0038863350", LocalDate.of(2016, 1, 1)).orElseThrow();
        assertEquals("CH0038863350", h.isin());
        assertTrue(h.bars().size() > 1000, "a decade of daily bars");
        var first = h.bars().get(0);
        var last = h.bars().get(h.bars().size() - 1);
        assertTrue(first.date().isBefore(last.date()), "oldest first");
        assertTrue(last.close() > 0 && last.volume() > 0,
                "bars carry venue-native price and volume");
    }
}
