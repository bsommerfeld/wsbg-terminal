package de.bsommerfeld.wsbg.terminal.web.impl.sources.briefing;

import de.bsommerfeld.wsbg.terminal.web.impl.net.DirectTransport;
import de.bsommerfeld.wsbg.terminal.web.impl.net.HouseFetcher;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Eurostat, live: the whole catalogued spine against the real API. The failure
 * this guards is the quiet one - a dataset whose dimension codes are renamed
 * answers 200 with an empty series, which in production is a macro quantity
 * that simply stops appearing in the report.
 *
 * <pre>EUROSTAT_SMOKE=true mvn test -pl web/impl -Dtest=EurostatSmokeIT -Dtest.excludedGroups=</pre>
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "EUROSTAT_SMOKE", matches = "true")
class EurostatSmokeIT {

    @Test
    void everyCatalogedSeriesCarriesObservations() {
        Map<String, EurostatClient.Reihe> spine =
                new EurostatClient(new HouseFetcher(Set.of(new DirectTransport())))
                        .spineSeries(24);
        for (EurostatClient.Kennzahl k : EurostatClient.SPINE) {
            EurostatClient.Reihe reihe = spine.get(k.dataset());
            System.out.printf("[eurostat] %-14s %-46s %s%n", k.dataset(), k.titel(),
                    reihe == null ? "→ EMPTY  " + k.filter()
                            : reihe.punkte().size() + " Punkte, zuletzt "
                                    + reihe.letzter().periode() + " = " + reihe.letzter().wert());
        }
        assertFalse(spine.isEmpty(), "Eurostat answered no series at all");
        assertEquals(EurostatClient.SPINE.size(), spine.size(),
                "not every catalogued series carries data - see the EMPTY lines above");
    }
}
