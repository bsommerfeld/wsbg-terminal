package de.bsommerfeld.wsbg.terminal.db;

import de.bsommerfeld.wsbg.terminal.db.AgentRepository.HeadlineRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Archiv löschen" semantics: {@link AgentRepository#clearArchiveKeepSession()}
 * wipes the permanent archive AND drops archive-only headlines from the wire,
 * but keeps the current session (live-published + snapshot-restored).
 */
class AgentRepositoryClearArchiveTest {

    @TempDir
    Path dir;

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }

    private static HeadlineRecord rec(String cluster, String text, long createdAt) {
        return new HeadlineRecord(cluster, text, "", createdAt, List.of(), List.of(),
                HeadlineHighlight.NORMAL, null, List.of(), null, List.of(), null,
                HeadlineSentiment.NEUTRAL, null);
    }

    @Test
    void clearArchiveKeepSession_dropsArchiveSeeded_keepsLiveAndRestored() {
        HeadlineArchive archive = new HeadlineArchive(dir.resolve("headlines.jsonl"));
        // An old headline already in the permanent archive → re-seeded into the wire.
        archive.append(rec("t3_old", "Archivierte Zeile von gestern", now() - 3600));

        AgentRepository repo = new AgentRepository(archive); // ctor re-seeds the archived one
        // A headline restored from the short-TTL snapshot = current session.
        repo.restoreHeadlines(List.of(rec("t3_snap", "Aus dem Snapshot", now() - 60)));
        // A headline published live this run = current session.
        repo.saveHeadline("t3_live", "Live publiziert", "");

        assertEquals(3, repo.getAllHeadlines().size(), "archive-seed + snapshot + live");

        repo.clearArchiveKeepSession();

        List<HeadlineRecord> wire = repo.getAllHeadlines();
        assertEquals(2, wire.size(), "archive-only entry dropped, session kept");
        assertTrue(wire.stream().anyMatch(h -> h.headline().equals("Aus dem Snapshot")));
        assertTrue(wire.stream().anyMatch(h -> h.headline().equals("Live publiziert")));
        assertTrue(wire.stream().noneMatch(h -> h.headline().equals("Archivierte Zeile von gestern")));
        assertEquals(0, archive.size(), "permanent archive wiped");
    }
}
