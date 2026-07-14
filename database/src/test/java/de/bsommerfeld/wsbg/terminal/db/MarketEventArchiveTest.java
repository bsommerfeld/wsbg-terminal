package de.bsommerfeld.wsbg.terminal.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The event register: append idempotent on the identity, enrichment replaces
 * in place (atomic rewrite) and survives a reload, pending-work query honours
 * the settle cutoff and the symbol requirement, torn lines skipped.
 */
class MarketEventArchiveTest {

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("market-events.jsonl");
    }

    private static MarketEventRecord bare(String date, String symbol, String clazz) {
        return MarketEventRecord.bare(date, symbol, null, clazz, "TEST", "detail");
    }

    @Test
    void appendsIdempotentlyAndReloads() {
        MarketEventArchive archive = new MarketEventArchive(file());
        assertTrue(archive.append(bare("2026-07-01", "AAPL", "DOWNGRADE")));
        assertFalse(archive.append(bare("2026-07-01", "AAPL", "DOWNGRADE")));
        assertTrue(archive.append(bare("2026-07-01", "AAPL", "UPGRADE")));
        assertTrue(archive.append(MarketEventRecord.bare("2026-07-02", null, "DE0007164600",
                "GEWINNWARNUNG", "EQS", "SAP senkt Prognose")));
        assertEquals(3, archive.size());

        MarketEventArchive reloaded = new MarketEventArchive(file());
        assertEquals(3, reloaded.size());
        assertEquals(2, reloaded.byInstrument("aapl").size());
        assertEquals(1, reloaded.byInstrument("DE0007164600").size());
        assertEquals(1, reloaded.byClass("GEWINNWARNUNG").size());
    }

    @Test
    void enrichReplacesInPlaceAndPersists() {
        MarketEventArchive archive = new MarketEventArchive(file());
        MarketEventRecord base = bare("2026-07-01", "AAPL", "DOWNGRADE");
        archive.append(base);

        MarketEventRecord enriched = new MarketEventRecord("2026-07-01", "AAPL", null,
                "DOWNGRADE", "TEST", "detail", "FEAR", 31.2, -4.7, -6.1, "^GSPC", false);
        assertTrue(archive.enrich(enriched));
        assertEquals(1, archive.size());
        assertEquals(-4.7, archive.all().get(0).carEvent(), 1e-9);

        MarketEventArchive reloaded = new MarketEventArchive(file());
        assertEquals(1, reloaded.size());
        assertEquals("FEAR", reloaded.all().get(0).regimeBand());
        assertEquals(-6.1, reloaded.all().get(0).carShort(), 1e-9);

        // Enriching an unknown identity never creates an event.
        assertFalse(archive.enrich(new MarketEventRecord("2026-07-09", "MSFT", null,
                "UPGRADE", "TEST", null, null, null, 1.0, 1.0, "^GSPC", false)));
    }

    @Test
    void pendingEnrichmentHonoursCutoffSymbolAndCap() {
        MarketEventArchive archive = new MarketEventArchive(file());
        archive.append(bare("2026-07-01", "AAPL", "DOWNGRADE"));            // settled, pending
        archive.append(bare("2026-07-14", "MSFT", "UPGRADE"));              // too young
        archive.append(MarketEventRecord.bare("2026-07-01", null, "DE0007164600",
                "GEWINNWARNUNG", "EQS", "t"));                              // no symbol
        MarketEventRecord done = new MarketEventRecord("2026-06-01", "NVDA", null,
                "UPGRADE", "TEST", null, null, null, 2.0, 3.0, "^GSPC", false);
        archive.append(done);                                               // already enriched

        List<MarketEventRecord> pending = archive.pendingEnrichment("2026-07-05", 10);
        assertEquals(1, pending.size());
        assertEquals("AAPL", pending.get(0).symbol());
        assertNull(pending.get(0).carEvent());
        assertEquals(0, archive.pendingEnrichment("2026-07-05", 0).size());
    }

    @Test
    void tornLineIsSkippedOnLoad() throws Exception {
        MarketEventArchive archive = new MarketEventArchive(file());
        archive.append(bare("2026-07-01", "AAPL", "DOWNGRADE"));
        Files.writeString(file(), "{\"date\":\"2026-0", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        assertEquals(1, new MarketEventArchive(file()).size());
    }

    @Test
    void invalidRecordsAreRejected() {
        MarketEventArchive archive = new MarketEventArchive(file());
        assertFalse(archive.append(null));
        assertFalse(archive.append(MarketEventRecord.bare(null, "AAPL", null, "X", "S", null)));
        assertFalse(archive.append(MarketEventRecord.bare("2026-07-01", null, null, "X", "S", null)));
        assertFalse(archive.append(MarketEventRecord.bare("2026-07-01", "AAPL", null, " ", "S", null)));
        assertEquals(0, archive.size());
    }
}
