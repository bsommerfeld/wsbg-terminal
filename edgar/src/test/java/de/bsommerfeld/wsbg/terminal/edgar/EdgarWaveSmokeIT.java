package de.bsommerfeld.wsbg.terminal.edgar;

import de.bsommerfeld.wsbg.terminal.core.price.InsiderDealings;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three new EDGAR legs, live against the real SEC: reported figures,
 * full-text filings and Form 4 insider transactions. Isolated verification is
 * the live probe - a fixture would only ever prove that the parser reads the
 * fixture.
 *
 * <pre>EDGAR_SMOKE=true mvn test -pl edgar -Dtest=EdgarWaveSmokeIT -Dtest.excludedGroups=</pre>
 */
@Tag("integration")
@EnabledIfEnvironmentVariable(named = "EDGAR_SMOKE", matches = "true")
class EdgarWaveSmokeIT {

    @Test
    void theReportedFiguresArriveAsASeries() {
        EdgarFactsClient.Kennzahlen facts = new EdgarFactsClient().kennzahlen("AAPL");
        assertFalse(facts.isEmpty(), "company-facts answered nothing for AAPL");
        System.out.println("[edgar-facts] " + facts.firma() + " · "
                + facts.werte().size() + " figures");
        Set<String> kennzahlen = new LinkedHashSet<>();
        for (EdgarFactsClient.Kennzahlwert w : facts.werte()) kennzahlen.add(w.kennzahl());
        System.out.println("[edgar-facts] quantities: " + kennzahlen);
        for (EdgarFactsClient.Kennzahlwert w : facts.werte().subList(0, 12)) {
            System.out.println("  " + w.ende() + " " + w.fy() + w.fp() + " " + w.kennzahl()
                    + " = " + w.wert() + " " + w.einheit() + " (" + w.form() + ")");
        }
        // A series, not a snapshot: the same quantity across several periods.
        assertTrue(kennzahlen.size() >= 5, "only " + kennzahlen.size() + " quantities read");
        long umsatzPeriods = facts.werte().stream()
                .filter(w -> w.kennzahl().equals("umsatz")).count();
        assertTrue(umsatzPeriods >= 4, "revenue carries only " + umsatzPeriods + " period(s)");
    }

    @Test
    void theFullTextIndexFindsFilingsThatNameTheCompany() {
        List<RawNewsItem> hits = new EdgarFullTextClient().newsForName("Rheinmetall", 10);
        assertFalse(hits.isEmpty(), "the full-text index answered nothing");
        for (RawNewsItem hit : hits) {
            System.out.println("[edgar-fts] " + hit.publishedAt() + "  " + hit.title()
                    + "\n            " + hit.link());
            assertNotNull(hit.link());
            assertTrue(hit.link().startsWith("https://www.sec.gov/Archives/"));
        }
    }

    @Test
    void formFourTransactionsArriveInTheInsiderShape() {
        InsiderDealings deals = new EdgarInsiderClient().insiderDeals("AAPL", "US0378331005");
        assertNotNull(deals, "the Form 4 register could not be read");
        System.out.println("[edgar-insider] " + deals.deals().size() + " transaction(s)");
        for (InsiderDealings.InsiderDeal d : deals.deals()) {
            System.out.println("  " + d.dealDateIso() + "  " + d.person() + " (" + d.positionStatus()
                    + ")  " + d.dealType() + "  " + d.avgPrice() + " " + d.currency()
                    + "  vol " + d.volumeEur());
        }
        assertFalse(deals.deals().isEmpty(), "no Form 4 transaction parsed");
    }
}
