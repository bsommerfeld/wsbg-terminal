package de.bsommerfeld.wsbg.terminal.web.impl.sources.onvista;

import de.bsommerfeld.wsbg.terminal.web.impl.net.DirectTransport;
import de.bsommerfeld.wsbg.terminal.web.impl.net.HouseFetcher;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The European supersector board, live: ONE bulk POST for all catalogued
 * indices. A moved onvista id is exactly the failure this smoke exists for -
 * it costs the board one silent row in production and is invisible otherwise.
 *
 * <pre>STOXX_SMOKE=true mvn test -pl web-impl -Dtest=StoxxSectorSmokeIT -Dtest.excludedGroups=</pre>
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "STOXX_SMOKE", matches = "true")
class StoxxSectorSmokeIT {

    @Test
    void everyCatalogedSupersectorQuotes() {
        StoxxSectorClient.Board board = new StoxxSectorClient(
                new HouseFetcher(Set.of(new DirectTransport()))).board();
        assertFalse(board.isEmpty(), "the bulk endpoint answered no supersector");
        for (StoxxSectorClient.Sektorstand s : board.sektoren()) {
            System.out.printf("[stoxx] #%-2d %-30s %10.2f  Tag %+6.2f%%  Jahr %+7.2f%%  %s%n",
                    s.rang(), s.name(), s.stand(), s.tagPct(), s.jahrPct(), s.isin());
        }
        assertTrue(board.sektoren().size() == StoxxSectorClient.KATALOG.size(),
                "only " + board.sektoren().size() + " of "
                        + StoxxSectorClient.KATALOG.size() + " supersectors quoted");
        for (StoxxSectorClient.Sektorstand s : board.sektoren()) {
            assertTrue(Double.isFinite(s.stand()) && s.stand() > 0,
                    "supersector without a level: " + s.name());
        }
    }
}
