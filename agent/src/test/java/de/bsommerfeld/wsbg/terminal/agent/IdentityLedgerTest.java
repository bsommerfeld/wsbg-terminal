package de.bsommerfeld.wsbg.terminal.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The identity desk's persistent verdict book: replay, supersede-by-append, expiry, torn lines. */
class IdentityLedgerTest {

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("identity-ledger.jsonl");
    }

    private static IdentityLedger.Entry entry(String q, String symbol, long decidedAt) {
        return new IdentityLedger.Entry(q, symbol, "Canonical Name", "DE0000000001", 42L, "STK", decidedAt);
    }

    @Test
    void verdictsReplayAcrossInstances() {
        long now = Instant.now().getEpochSecond();
        new IdentityLedger(file()).put("Rheinmetall", entry("rheinmetall", "RHM.DE", now));

        IdentityLedger reloaded = new IdentityLedger(file());
        IdentityLedger.Entry e = reloaded.get("RHEINMETALL");
        assertNotNull(e, "verdict must survive a restart, key lookup case-insensitive");
        assertEquals("RHM.DE", e.symbol());
        assertEquals(42L, e.venueId());
        assertEquals("STK", e.category());
    }

    @Test
    void laterAppendSupersedesOnReload() {
        long now = Instant.now().getEpochSecond();
        IdentityLedger ledger = new IdentityLedger(file());
        ledger.put("meta", entry("meta", "WRONG.T", now));
        ledger.put("meta", entry("meta", "META", now + 1));

        assertEquals("META", new IdentityLedger(file()).get("meta").symbol(),
                "the LAST line per key wins — supersede is a plain append");
    }

    @Test
    void expiredVerdictIsReJudgedNotReplayed() {
        long old = Instant.now().minusSeconds(40L * 24 * 3600).getEpochSecond();
        new IdentityLedger(file()).put("stale", entry("stale", "OLD.X", old));

        assertNull(new IdentityLedger(file()).get("stale"),
                "a verdict past max age must not replay — bounded error lifetime");
    }

    @Test
    void tornLineIsSkippedOnLoad() throws Exception {
        long now = Instant.now().getEpochSecond();
        new IdentityLedger(file()).put("good", entry("good", "GOOD.DE", now));
        Files.writeString(file(), "{\"q\":\"torn", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        IdentityLedger reloaded = new IdentityLedger(file());
        assertEquals(1, reloaded.size(), "the torn line is skipped, the good one survives");
        assertNotNull(reloaded.get("good"));
    }

    @Test
    void nullPathIsMemoryOnly() {
        IdentityLedger ledger = new IdentityLedger(null);
        ledger.put("x", entry("x", "X", Instant.now().getEpochSecond()));
        assertNotNull(ledger.get("x"), "in-memory works without a file");
    }
}
