package de.bsommerfeld.wsbg.terminal.web.impl.sources.briefing;

import de.bsommerfeld.wsbg.terminal.web.facts.ShortInterest;
import de.bsommerfeld.wsbg.terminal.web.impl.net.DirectTransport;
import de.bsommerfeld.wsbg.terminal.web.impl.net.HouseFetcher;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The UK short register, live: the real daily file, read without a
 * spreadsheet library. The failure this guards is a changed column order or a
 * renamed header - the parser refuses to bind in that case, and the register
 * would otherwise silently answer "nobody is short" for every issuer.
 *
 * <pre>FCA_SMOKE=true mvn test -pl web/impl -Dtest=FcaShortPositionsSmokeIT -Dtest.excludedGroups=</pre>
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "FCA_SMOKE", matches = "true")
class FcaShortPositionsSmokeIT {

    /** A UK large cap that is reliably in the register. */
    private static final String BARCLAYS = "GB0031348658";

    @Test
    void theRegisterReadsAndSeatsAKnownIssuer() {
        FcaShortPositionsClient client = new FcaShortPositionsClient(
                new HouseFetcher(Set.of(new DirectTransport())));
        ShortInterest barclays = client.byIsin(BARCLAYS);
        assertNotNull(barclays, "the register could not be read at all");
        System.out.printf("[fca] %s → %d open position(s), %.2f%% disclosed%n",
                BARCLAYS, barclays.positions().size(), barclays.totalDisclosedPercent());
        for (ShortInterest.ShortPosition p : barclays.positions()) {
            System.out.printf("[fca]   %-52s %5.2f%%  %s%n", p.holder(), p.percent(),
                    p.dateIso());
        }

        // A German ISIN is simply not this regulator's business - a present
        // record with nothing in it, never a null and never an invented zero.
        ShortInterest sap = client.byIsin("DE0007164600");
        assertNotNull(sap, "a non-UK ISIN must answer with an empty record, not null");
        System.out.println("[fca] DE0007164600 → " + sap.positions().size()
                + " position(s) (expected: none - not a UK-supervised issuer)");

        // The register as a whole must have parsed - one issuer answering is
        // not proof, since an unparsed file answers empty for everyone.
        ShortInterest probe = client.byIsin(BARCLAYS);
        assertFalse(probe.positions().isEmpty() && sap.positions().isEmpty()
                        && probe.totalDisclosedPercent() == 0,
                "no issuer carried a single position - the file did not parse");
        assertTrue(probe.totalDisclosedPercent() >= 0);
    }
}
