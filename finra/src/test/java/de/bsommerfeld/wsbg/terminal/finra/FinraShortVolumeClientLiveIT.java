package de.bsommerfeld.wsbg.terminal.finra;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The live files, against the real CDN - format, backfill and the guards. */
@Tag("integration")
class FinraShortVolumeClientLiveIT {

    @Test
    void theDailyFilesBackfillASymbolsOwnHistory() {
        if (!"true".equals(System.getenv("FINRA_SMOKE"))) return;
        var client = new FinraShortVolumeClient();
        List<ShortVolumeDay> rows = client.recent("GME", LocalDate.now(), 30);

        System.out.printf("[FINRA] GME: %d sessions %s .. %s%n", rows.size(),
                rows.isEmpty() ? "-" : rows.get(0).date(),
                rows.isEmpty() ? "-" : rows.get(rows.size() - 1).date());
        assertTrue(rows.size() >= 25, "history must arrive on the FIRST request");
        for (ShortVolumeDay r : rows) {
            assertTrue(r.shortShare() > 0 && r.shortShare() <= 1,
                    r.date() + " share " + r.shortShare());
        }
        for (int i = 1; i < rows.size(); i++) {
            assertTrue(rows.get(i).date().isAfter(rows.get(i - 1).date()), "oldest first");
        }
        double[] shares = rows.stream().mapToDouble(ShortVolumeDay::shortShare).toArray();
        var reading = de.bsommerfeld.wsbg.terminal.signals.flow.ShortSideCrowding
                .measure(shares);
        System.out.println("[FINRA] " + reading.map(r -> r.toContextLine()).orElse("(none)"));
        assertTrue(reading.isPresent(), "30 real sessions must carry a reading");
    }

    @Test
    void aWeekendCostsNoRequestAndAnUnknownSymbolStopsEarly() {
        if (!"true".equals(System.getenv("FINRA_SMOKE"))) return;
        var client = new FinraShortVolumeClient();

        assertTrue(client.day(LocalDate.of(2026, 8, 8), "GME").isEmpty(), "Saturday");
        assertTrue(client.day(LocalDate.of(2026, 8, 9), "GME").isEmpty(), "Sunday");
        assertTrue(client.day(LocalDate.of(2026, 8, 6), "RHM.DE").isEmpty(),
                "a suffixed symbol never reaches the network");
        assertFalse(client.day(LocalDate.of(2026, 8, 6), "GME").isEmpty(),
                "a real trading day answers");
    }
}
