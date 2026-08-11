package de.bsommerfeld.wsbg.terminal.onvista;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The EUWAX Sentiment, live. The index is read through onvista because the
 * exchange's own pages sit behind a JavaScript challenge - so what this smoke
 * really guards is that the SUBSTITUTE door still carries the same instrument
 * under the same id.
 *
 * <pre>EUWAX_SMOKE=true mvn test -pl onvista -Dtest=EuwaxSentimentSmokeIT -Dtest.excludedGroups=</pre>
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "EUWAX_SMOKE", matches = "true")
class EuwaxSentimentSmokeIT {

    @Test
    void theRetailGaugeQuotes() {
        EuwaxSentimentClient.Stimmung s = new EuwaxSentimentClient().stimmung();
        assertNotNull(s, "the EUWAX Sentiment did not quote");
        System.out.printf("[euwax] Stand %.2f (Vortag %.2f, %+.2f), Jahresspanne %.1f … %.1f, "
                        + "gemessen %s%n",
                s.stand(), s.vortag(), s.veraenderung(), s.tief1J(), s.hoch1J(), s.gemessen());
        System.out.println("[euwax] Lesart: " + (s.stand() >= 0
                ? "Privatanleger sind netto LONG positioniert"
                : "Privatanleger sind netto SHORT positioniert"));
        // The gauge runs roughly -100 … +100; a level outside that is not this
        // instrument any more.
        assertTrue(s.stand() > -150 && s.stand() < 150,
                "level " + s.stand() + " is outside the gauge's range - wrong instrument?");
        assertNotNull(s.gemessen(), "the quote carried no timestamp");
    }
}
