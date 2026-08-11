package de.bsommerfeld.wsbg.terminal.euronext;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live probe against the real service - the only isolated verification this
 * house accepts (no synthetic fixtures for pipeline truth). Run with:
 * {@code EURONEXT_SMOKE=true mvn test -pl euronext -Dtest=EuronextClientLiveIT
 * -Dtest.excludedGroups=}
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "EURONEXT_SMOKE", matches = "true")
class EuronextClientLiveIT {

    @Test
    void asmlAnswersWithDecadesOfBars() {
        EuronextHistory h = new EuronextClient()
                .history("NL0010273215", "XAMS", "max").orElseThrow();
        assertEquals("NL0010273215", h.isin());
        assertEquals("XAMS", h.mic());
        assertTrue(h.bars().size() > 3000, "max reaches back to roughly 2001");
        var last = h.bars().get(h.bars().size() - 1);
        assertTrue(last.close() > 0 && last.volume() > 0,
                "bars carry venue-native close and volume");
        assertTrue(last.date().isAfter(LocalDate.now().minusDays(7)),
                "the newest bar is fresh");
        assertTrue(h.bars().get(0).date().isBefore(last.date()), "oldest first");
    }
}
