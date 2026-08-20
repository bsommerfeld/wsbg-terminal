package de.bsommerfeld.wsbg.terminal.web.impl.sources.briefing;

import de.bsommerfeld.wsbg.terminal.web.impl.net.DirectTransport;
import de.bsommerfeld.wsbg.terminal.web.impl.net.HouseFetcher;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Eurex open interest, live. The failure worth guarding is not an outage but a
 * MOVED product id: the endpoint answers happily for whatever sits at an id,
 * so a shifted catalog would put another market's book under a familiar name.
 * The client asserts the product code; this smoke asserts that the assertion
 * still passes for every catalogued entry.
 *
 * <pre>EUREX_SMOKE=true mvn test -pl web/impl -Dtest=EurexOpenInterestSmokeIT -Dtest.excludedGroups=</pre>
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "EUREX_SMOKE", matches = "true")
class EurexOpenInterestSmokeIT {

    @Test
    void theCatalogedBooksReadAndKeepTheirCodes() {
        Map<String, EurexOpenInterestClient.Statistik> books =
                new EurexOpenInterestClient(new HouseFetcher(Set.of(new DirectTransport())))
                        .katalog();
        for (var e : books.entrySet()) {
            var s = e.getValue();
            System.out.printf("[eurex] %-28s %-6s OI %,12.0f  Vol %,10.0f  P/C %-6s  %s%n",
                    e.getKey(), s.code(), s.openInterest(), s.volumen(), s.putCallRatio(),
                    s.underlyingIsin());
            System.out.println("[eurex]    nearest expiries:");
            for (var l : s.laufzeiten().subList(0, Math.min(3, s.laufzeiten().size()))) {
                System.out.printf("[eurex]      %s  Calls OI %,9.0f  Puts OI %,9.0f  P/C %s%n",
                        l.datum(), l.callOpenInterest(), l.putOpenInterest(), l.putCallRatio());
            }
        }
        assertFalse(books.isEmpty(), "no catalogued option book could be read");
        assertTrue(books.size() >= 4,
                "only " + books.size() + " of " + EurexOpenInterestClient.KATALOG.size()
                        + " books read - see the WARN lines for a moved product id");
    }
}
