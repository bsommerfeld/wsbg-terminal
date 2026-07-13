package de.bsommerfeld.wsbg.terminal.db;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The KI-DD archive: append-idempotent like its siblings, plus the ONE
 * mutation beyond append — the user's explicit {@link DeepDiveArchive#delete}
 * (UI trash button, 2026-07-13). Deletion must survive a restart: the file is
 * rewritten, a reload never resurrects the record.
 */
class DeepDiveArchiveTest {

    @TempDir
    Path dir;

    private static DeepDiveRecord record(String id, String subject) {
        return new DeepDiveRecord(id, subject, subject + " AG", subject, null,
                1_752_000_000L, "## Worum es geht\nInhalt zu " + subject + ".",
                12.34, "EUR", 3, 5, 1000L, List.of());
    }

    @Test
    void deleteRemovesFromIndexAndFileAndSurvivesReload() {
        Path file = dir.resolve("deepdive-reports.jsonl");
        DeepDiveArchive archive = new DeepDiveArchive(file);
        archive.append(record("dd-1", "RHM.DE"));
        archive.append(record("dd-2", "OTLK"));
        archive.append(record("dd-3", "SAP"));
        assertEquals(3, archive.size());

        assertTrue(archive.delete("dd-2"));
        assertEquals(2, archive.size());
        assertTrue(archive.byId("dd-2").isEmpty());
        assertTrue(archive.byId("dd-1").isPresent() && archive.byId("dd-3").isPresent(),
                "the neighbors must survive the rewrite");

        // A reload from disk never resurrects the deleted record.
        DeepDiveArchive reloaded = new DeepDiveArchive(file);
        assertEquals(2, reloaded.size());
        assertTrue(reloaded.byId("dd-2").isEmpty());
        assertEquals(List.of("dd-3", "dd-1"),
                reloaded.recent(10).stream().map(DeepDiveRecord::id).toList(),
                "order (newest first) survives");

        // Unknown/already-deleted ids are a clean no-op.
        assertFalse(archive.delete("dd-2"));
        assertFalse(archive.delete("nope"));
        assertFalse(archive.delete(null));
        assertEquals(2, archive.size());
    }

    @Test
    void appendStaysIdempotentById() {
        DeepDiveArchive archive = new DeepDiveArchive(dir.resolve("dd.jsonl"));
        archive.append(record("dd-1", "RHM.DE"));
        archive.append(record("dd-1", "RHM.DE"));
        assertEquals(1, archive.size());
    }
}
