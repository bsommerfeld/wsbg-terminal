package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.db.MarketEventArchive;
import de.bsommerfeld.wsbg.terminal.db.MarketEventRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Abendausgabe's market-memory block: only days the register saw events
 * produce one, classes without any rate or prior stay silent, the discipline
 * line always rides.
 */
class MarketMemoryBriefingTest {

    @TempDir
    Path dir;

    @Test
    void quietDayYieldsNoBlock() {
        MarketEventArchive archive = new MarketEventArchive(dir.resolve("e.jsonl"));
        archive.append(MarketEventRecord.bare("2026-07-10", "AAPL", null, "DOWNGRADE", "TEST", null));
        assertNull(MarketMemoryBriefing.dayBlock(archive, LocalDate.of(2026, 7, 14), true));
        assertNull(MarketMemoryBriefing.dayBlock(null, LocalDate.of(2026, 7, 14), true));
    }

    @Test
    void todaysClassCarriesPriorAndDiscipline() {
        MarketEventArchive archive = new MarketEventArchive(dir.resolve("e.jsonl"));
        archive.append(MarketEventRecord.bare("2026-07-14", null, "DE0007164600",
                "GEWINNWARNUNG", "EQS", "SAP senkt Prognose"));
        String block = MarketMemoryBriefing.dayBlock(archive, LocalDate.of(2026, 7, 14), true);
        assertTrue(block.contains("MARKT-GEDÄCHTNIS"));
        assertTrue(block.contains("GEWINNWARNUNG heute: DE0007164600"));
        assertTrue(block.contains("Attribuierter Prior"));
        assertTrue(block.contains("Jackson/Madura"));
        assertTrue(block.contains("Disziplin"));
    }

    @Test
    void classWithoutRateOrPriorYieldsNothingTellable() {
        MarketEventArchive archive = new MarketEventArchive(dir.resolve("e.jsonl"));
        archive.append(MarketEventRecord.bare("2026-07-14", "AAPL", null, "INITIATION", "TEST", null));
        assertNull(MarketMemoryBriefing.dayBlock(archive, LocalDate.of(2026, 7, 14), true));
    }

    @Test
    void houseStatisticsAppearOnceTheClassCarriesEnoughEvents() {
        MarketEventArchive archive = new MarketEventArchive(dir.resolve("e.jsonl"));
        for (int i = 0; i < 8; i++) {
            archive.append(new MarketEventRecord("2026-06-" + String.format("%02d", i + 1),
                    "SYM" + i, null, "DOWNGRADE", "TEST", null, null, null, -5.0, -6.0,
                    "^GSPC", false));
        }
        archive.append(MarketEventRecord.bare("2026-07-14", "NVDA", null, "DOWNGRADE", "TEST", null));
        String block = MarketMemoryBriefing.dayBlock(archive, LocalDate.of(2026, 7, 14), false);
        assertTrue(block.contains("house statistics: N=8"));
        assertTrue(block.contains("thin sample"));
        assertTrue(block.contains("attributed prior"));
    }
}
