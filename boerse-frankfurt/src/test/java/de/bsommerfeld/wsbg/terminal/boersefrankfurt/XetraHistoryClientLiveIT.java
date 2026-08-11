package de.bsommerfeld.wsbg.terminal.boersefrankfurt;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Live probe against the real bridge. Run with:
 * {@code BF_SMOKE=true mvn test -pl boerse-frankfurt
 * -Dtest=XetraHistoryClientLiveIT -Dtest.excludedGroups=}
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "BF_SMOKE", matches = "true")
class XetraHistoryClientLiveIT {

    @Test
    void sapAnswersTenYearsOfXetraBars() {
        var h = new XetraHistoryClient()
                .history("DE0007164600", LocalDate.now().minusYears(10)).orElseThrow();
        assertTrue(h.bars().size() > 2000, "ten years of daily bars");
        var last = h.bars().get(h.bars().size() - 1);
        assertTrue(last.close() > 0 && last.turnoverEur() > 0);
        assertTrue(last.date().isAfter(LocalDate.now().minusDays(7)), "a fresh tape");
    }

    @Test
    void aUsNameListedOnXetraAnswersToo() {
        var h = new XetraHistoryClient()
                .history("US0378331005", LocalDate.now().minusYears(2)).orElseThrow();
        assertTrue(h.bars().size() > 400);
    }
}
