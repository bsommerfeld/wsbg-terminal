package de.bsommerfeld.wsbg.terminal.db;

import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository.HeadlineRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The permanent headline archive: append-only JSONL that survives restarts,
 * idempotent on identity, tolerant of a torn line, and indexed for the future
 * search/watchlist UI.
 */
class HeadlineArchiveTest {

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("headlines.jsonl");
    }

    private static HeadlineRecord rec(String cluster, String text, long createdAt,
            String ticker, List<HeadlineSubject> subjects, MarketSnapshot snapshot) {
        return new HeadlineRecord(cluster, text, "", createdAt, List.of(), List.of(),
                HeadlineHighlight.NORMAL, ticker, subjects, null,
                List.of("Semiconductors"), "stock", HeadlineSentiment.BULLISH, snapshot);
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }

    @Test
    void appendedHeadlinesSurviveAReload() {
        HeadlineArchive a = new HeadlineArchive(file());
        a.append(rec("t3_a", "NVIDIA +5% — der Käfig feiert", now(), "NVDA", List.of(), null));
        a.append(rec("t3_b", "Gold glänzt nach Fed-Statement", now(), "GC=F", List.of(), null));

        HeadlineArchive reloaded = new HeadlineArchive(file());
        assertEquals(2, reloaded.size(), "everything written must come back");
        assertEquals("NVIDIA +5% — der Käfig feiert", reloaded.all().get(0).headline());
    }

    @Test
    void appendIsIdempotentOnIdentity() {
        HeadlineArchive a = new HeadlineArchive(file());
        HeadlineRecord r = rec("t3_a", "NVIDIA +5%", 1000L, "NVDA", List.of(), null);
        a.append(r);
        a.append(r); // snapshot-restore replay must not duplicate history
        assertEquals(1, a.size());
        assertEquals(1, new HeadlineArchive(file()).size(), "file holds one line too");
    }

    @Test
    void aTornFinalLineIsSkippedNotFatal() throws Exception {
        HeadlineArchive a = new HeadlineArchive(file());
        a.append(rec("t3_a", "Zeile eins", now(), null, List.of(), null));
        // Simulate a crash mid-append: a torn, unparseable trailing line.
        Files.writeString(file(), "{\"clusterId\": \"t3_b\", \"headl",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        HeadlineArchive reloaded = new HeadlineArchive(file());
        assertEquals(1, reloaded.size(), "intact records load, the torn line is dropped");
    }

    @Test
    void byTickerFindsPrimaryAndSubjectTickers() {
        HeadlineArchive a = new HeadlineArchive(file());
        a.append(rec("t3_a", "NVIDIA zieht an", 1000L, "NVDA", List.of(), null));
        a.append(rec("t3_b", "Chips gemischt — AMD schwach, NVIDIA stark", 2000L, "AMD",
                List.of(new HeadlineSubject("NVIDIA", "NVDA")), null));
        a.append(rec("t3_c", "Gold glänzt", 3000L, "GC=F", List.of(), null));

        List<HeadlineRecord> nvda = a.byTicker("nvda");
        assertEquals(2, nvda.size(), "primary + subject mentions");
        assertEquals(2000L, nvda.get(0).createdAt(), "newest first");
        assertEquals(0, a.byTicker("TSLA").size());
    }

    @Test
    void searchCoversTextTickerAndSectors() {
        HeadlineArchive a = new HeadlineArchive(file());
        a.append(rec("t3_a", "NVIDIA zieht an", 1000L, "NVDA", List.of(), null));
        a.append(rec("t3_b", "Gold glänzt", 2000L, "GC=F", List.of(), null));

        assertEquals(1, a.search("nvidia").size(), "headline text");
        assertEquals(1, a.search("gc=f").size(), "ticker");
        assertEquals(2, a.search("semiconductors").size(), "sector chip (both records carry it)");
        assertEquals(0, a.search("bitcoin").size());
    }

    @Test
    void recentFiltersByAge() {
        HeadlineArchive a = new HeadlineArchive(file());
        a.append(rec("t3_old", "Alte Zeile", now() - 90_000, null, List.of(), null)); // > 24h
        a.append(rec("t3_new", "Frische Zeile", now(), null, List.of(), null));

        List<HeadlineRecord> recent = a.recent(Duration.ofHours(24));
        assertEquals(1, recent.size());
        assertEquals("Frische Zeile", recent.get(0).headline());
        assertEquals(2, a.size(), "nothing is ever deleted");
    }

    @Test
    void sparkSeriesIsStrippedFromTheArchivedSnapshot() {
        MarketSnapshot withSpark = new MarketSnapshot("NVDA", 100.0, 99.0, 1.0,
                Double.NaN, Double.NaN, -1, Double.NaN, Double.NaN, "USD", "", 0,
                List.of(99.0, 99.5, 100.0));
        HeadlineArchive a = new HeadlineArchive(file());
        a.append(rec("t3_a", "NVIDIA +1%", now(), "NVDA", List.of(), withSpark));

        HeadlineRecord stored = new HeadlineArchive(file()).all().get(0);
        assertEquals(100.0, stored.snapshot().price(), 1e-9, "scalar facts kept");
        assertTrue(stored.snapshot().spark().isEmpty(), "render-only spark bulk dropped");
    }

    @Test
    void agentRepositorySeedsItsWireFromTheArchiveAndArchivesNewLines() {
        HeadlineArchive a = new HeadlineArchive(file());
        a.append(rec("t3_old", "Uralte Zeile", now() - 90_000, null, List.of(), null));
        a.append(rec("t3_a", "Gestern Abend publiziert", now() - 3600, "NVDA", List.of(), null));

        AgentRepository repo = new AgentRepository(a);
        List<HeadlineRecord> wire = repo.getRecentHeadlines();
        assertEquals(1, wire.size(), "wire re-seeds only the last 24h");
        assertEquals("Gestern Abend publiziert", wire.get(0).headline());

        repo.saveHeadline("t3_b", "Neue Zeile", "");
        assertEquals(3, a.size(), "every accepted publish lands in the archive");
        assertEquals(3, new HeadlineArchive(file()).size(), "…and on disk");

        repo.clear(); // lab Reset wipes the session…
        assertEquals(3, a.size(), "…but never history");
    }
}
