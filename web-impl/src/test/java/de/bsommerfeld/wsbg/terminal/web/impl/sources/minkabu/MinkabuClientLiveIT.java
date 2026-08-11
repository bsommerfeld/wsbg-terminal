package de.bsommerfeld.wsbg.terminal.web.impl.sources.minkabu;

import de.bsommerfeld.wsbg.terminal.web.impl.net.DirectTransport;
import de.bsommerfeld.wsbg.terminal.web.impl.net.HouseFetcher;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live probe against the real backend - the only isolated verification this
 * house accepts (no synthetic fixtures for pipeline truth). Run with:
 * {@code MINKABU_SMOKE=true mvn test -pl web-impl -Dtest=MinkabuClientLiveIT
 * -Dtest.excludedGroups=}
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "MINKABU_SMOKE", matches = "true")
class MinkabuClientLiveIT {

    private static MinkabuClient client() {
        return new MinkabuClient(new HouseFetcher(Set.of(new DirectTransport())));
    }

    @Test
    void sonyAnswersWithDecadesOfBars() {
        MinkabuHistory h = client().history("6758.T", 6000).orElseThrow();
        assertEquals("6758.T", h.ric());
        assertTrue(h.bars().size() > 4000, "roughly two decades of daily bars");
        var last = h.bars().get(h.bars().size() - 1);
        assertTrue(last.volume() > 0, "bars carry venue-native volume");
        assertTrue(h.latestClose() > 0, "the running session prices the tape");
        LocalDate tokyoToday = LocalDate.now(ZoneId.of("Asia/Tokyo"));
        assertTrue(!h.latestDate().isBefore(tokyoToday.minusDays(7)),
                "the youngest bar is at most a week old, was " + h.latestDate());
    }

    @Test
    void toyotaAnswersToo() {
        MinkabuHistory h = client().history("7203.T", 100).orElseThrow();
        assertEquals("7203.T", h.ric());
        assertTrue(h.bars().size() > 50 && h.latestClose() > 0);
    }
}
