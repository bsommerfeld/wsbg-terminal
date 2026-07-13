package de.bsommerfeld.wsbg.terminal.bafin;

import de.bsommerfeld.wsbg.terminal.core.price.InsiderDealings;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Network-free parse tests against a captured Directors'-Dealings CSV export
 * (probed live 2026-07-13, Rheinmetall DE0007030009). The capture quirks that
 * matter: a BOM up front, 13 unquoted semicolon-separated columns, German
 * numbers with a currency suffix, {@code dd.MM.yyyy} dates, an occasionally
 * EMPTY venue field, and the header-only CSV a dealless/foreign ISIN answers
 * (probed with US67066G1040) — present-with-empty, never a miss.
 */
class InsiderDealingsClientTest {

    private static final String HEADER = "Emittent;BaFin-ID;ISIN;Meldepflichtiger;"
            + "Position / Status;Art des Instruments;Art des Geschäfts;Durchschnittspreis;"
            + "Aggregiertes Volumen;Mitteilungsdatum;Datum des Geschäfts;Ort des Geschäfts;"
            + "Datum der Aktivierung";

    /** Live capture excerpt, 2026-07-13 (BOM included, as served). */
    private static final String CSV = "\uFEFF" + HEADER + "\n"
            + "Rheinmetall Aktiengesellschaft;40002496;DE0007030009;ATP Holding GmbH;"
            + "in enger Beziehung;Aktie;Kauf;954,62 EUR;3.043.314,50 EUR;25.06.2026;25.06.2026;"
            + "Xetra;25.06.2026 11:48:44\n"
            + "Rheinmetall Aktiengesellschaft;40002496;DE0007030009;Roosen-Grillo, Dr. Jutta;"
            + "in enger Beziehung;Aktie;Kauf;951,20 EUR;42.804,00 EUR;25.06.2026;24.06.2026;"
            + "Xetra;25.06.2026 11:27:33\n"
            + "Rheinmetall Aktiengesellschaft;40002496;DE0007030009;Küplemez, Murat;"
            + "Aufsichtsrat;Aktie;Verkauf;1.550,00 EUR;6.200,00 EUR;15.12.2025;05.12.2025;"
            + ";16.12.2025 09:16:52\n"
            + "Rheinmetall Aktiengesellschaft;40002496;DE0007030009;Papperger, Armin Theodor;"
            + "Vorstand;Aktie;Kauf;1.421,00 EUR;298.410,00 EUR;01.12.2025;01.12.2025;"
            + "Quotrix;01.12.2025 15:03:17\n";

    private final InsiderDealingsClient client = new InsiderDealingsClient();

    @Test
    void parsesRealCsvWithBom() {
        List<InsiderDealings.InsiderDeal> deals = client.parseDeals(CSV).orElseThrow();
        assertEquals(4, deals.size());
        InsiderDealings.InsiderDeal top = deals.get(0);
        assertEquals("ATP Holding GmbH", top.person());
        assertEquals("in enger Beziehung", top.positionStatus());
        assertEquals("Aktie", top.instrumentType());
        assertEquals("Kauf", top.dealType());
        assertEquals(954.62, top.avgPrice(), 1e-9);
        assertEquals("EUR", top.currency());
        assertEquals(3_043_314.50, top.volumeEur(), 1e-9);
        assertEquals("2026-06-25", top.dealDateIso());
        assertEquals("2026-06-25", top.notifiedDateIso());
        assertEquals("Xetra", top.venue());
    }

    @Test
    void keepsDealsNewestFirstByNotificationDate() {
        List<InsiderDealings.InsiderDeal> deals = client.parseDeals(CSV).orElseThrow();
        assertEquals(List.of("2026-06-25", "2026-06-25", "2025-12-15", "2025-12-01"),
                deals.stream().map(InsiderDealings.InsiderDeal::notifiedDateIso).toList());
        // Same notification date → the newer trade date leads.
        assertEquals("2026-06-25", deals.get(0).dealDateIso());
        assertEquals("2026-06-24", deals.get(1).dealDateIso());
    }

    @Test
    void emptyVenueFieldIsNull() {
        List<InsiderDealings.InsiderDeal> deals = client.parseDeals(CSV).orElseThrow();
        InsiderDealings.InsiderDeal sale = deals.get(2);
        assertEquals("Verkauf", sale.dealType());
        assertNull(sale.venue());
    }

    @Test
    void headerOnlyCsvIsPresentWithEmptyDeals() {
        // What a dealless/foreign ISIN actually answers (probed: US67066G1040).
        List<InsiderDealings.InsiderDeal> deals =
                client.parseDeals("\uFEFF" + HEADER + "\n").orElseThrow();
        assertTrue(deals.isEmpty()); // "keine Insider-Meldungen" is a finding, not a miss
    }

    @Test
    void htmlAnswerIsUnreadable() {
        assertEquals(Optional.empty(), client.parseDeals(
                "<!DOCTYPE html PUBLIC \"-//W3C//DTD XHTML 1.0 Strict//EN\">\n<html><head>"
                        + "<title>BaFin - Mitteilungen</title></head><body>Meldepflichtiger</body></html>"));
    }

    @Test
    void garbageIsUnreadable() {
        assertEquals(Optional.empty(), client.parseDeals(null));
        assertEquals(Optional.empty(), client.parseDeals(""));
        assertEquals(Optional.empty(), client.parseDeals("   "));
        assertEquals(Optional.empty(), client.parseDeals("kaputt;aber;kein;register"));
    }

    @Test
    void capsAtTwentyDeals() {
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        for (int day = 1; day <= 25; day++) {
            csv.append(String.format(Locale.ROOT,
                    "Testwerk AG;40000001;DE0000000001;Person %02d;Vorstand;Aktie;Kauf;"
                            + "10,00 EUR;1.000,00 EUR;%02d.06.2026;%02d.06.2026;Xetra;"
                            + "%02d.06.2026 10:00:00\n", day, day, day, day));
        }
        List<InsiderDealings.InsiderDeal> deals = client.parseDeals(csv.toString()).orElseThrow();
        assertEquals(20, deals.size());
        assertEquals("2026-06-25", deals.get(0).notifiedDateIso()); // newest survives the cap
        assertEquals("2026-06-06", deals.get(19).notifiedDateIso());
    }

    @Test
    void parsesGermanNumbersAndCurrency() {
        assertEquals(954.62, InsiderDealingsClient.parseGermanNumber("954,62 EUR"), 1e-9);
        assertEquals(3_043_314.50, InsiderDealingsClient.parseGermanNumber("3.043.314,50 EUR"), 1e-9);
        assertEquals(Double.NaN, InsiderDealingsClient.parseGermanNumber(""));
        assertEquals(Double.NaN, InsiderDealingsClient.parseGermanNumber(null));
        assertEquals(Double.NaN, InsiderDealingsClient.parseGermanNumber("kaputt"));
        assertEquals("EUR", InsiderDealingsClient.currencyOf("954,62 EUR"));
        assertNull(InsiderDealingsClient.currencyOf("954,62"));
        assertNull(InsiderDealingsClient.currencyOf(null));
    }

    @Test
    void convertsGermanDates() {
        assertEquals("2026-06-25", InsiderDealingsClient.toIsoDate("25.06.2026"));
        assertNull(InsiderDealingsClient.toIsoDate(""));
        assertNull(InsiderDealingsClient.toIsoDate(null));
        assertNull(InsiderDealingsClient.toIsoDate("kein Datum"));
    }
}
