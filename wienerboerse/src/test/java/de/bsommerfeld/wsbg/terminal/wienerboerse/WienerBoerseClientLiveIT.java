package de.bsommerfeld.wsbg.terminal.wienerboerse;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live probe against the real venue - the only isolated verification this
 * house accepts (no synthetic fixtures for pipeline truth). Run with:
 * {@code WIEN_SMOKE=true mvn test -pl wienerboerse
 * -Dtest=WienerBoerseClientLiveIT -Dtest.excludedGroups=}
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "WIEN_SMOKE", matches = "true")
class WienerBoerseClientLiveIT {

    @Test
    void voestalpineAnswersWithADecadeOfBars() {
        WienerBoerseHistory h = new WienerBoerseClient()
                .history("AT0000937503", LocalDate.of(2016, 1, 1)).orElseThrow();
        assertEquals("AT0000937503", h.isin());
        assertTrue(h.pagePath().contains("-AT0000937503/"),
                "the site's redirect resolved the real slug");
        assertTrue(h.bars().size() > 2000, "a decade of daily bars");
        var first = h.bars().get(0);
        var last = h.bars().get(h.bars().size() - 1);
        assertTrue(first.date().isBefore(last.date()), "oldest first");
        assertTrue(first.date().getYear() == 2016, "the range start held");
        assertTrue(last.close() > 0, "a live tape has a price");
        assertTrue(last.volumeShares() > 0 && last.turnoverEur() > 0,
                "bars carry venue-native Stueck- and Geldumsatz");
    }

    @Test
    void omvAnswersToo() {
        WienerBoerseHistory h = new WienerBoerseClient()
                .history("AT0000743059", LocalDate.of(2016, 1, 1)).orElseThrow();
        assertTrue(h.bars().size() > 2000);
        assertTrue(h.bars().get(h.bars().size() - 1).close() > 0);
    }
}
