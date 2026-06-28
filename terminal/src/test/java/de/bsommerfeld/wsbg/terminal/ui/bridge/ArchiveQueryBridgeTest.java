package de.bsommerfeld.wsbg.terminal.ui.bridge;

import de.bsommerfeld.wsbg.terminal.db.AgentRepository.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.db.HeadlineArchive;
import de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight;
import de.bsommerfeld.wsbg.terminal.db.HeadlineSentiment;
import de.bsommerfeld.wsbg.terminal.db.HeadlineSubject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The page's archive query window: search / ticker / recent commands answered
 * with capped, newest-first result payloads in the shared headline-row shape.
 */
class ArchiveQueryBridgeTest {

    @TempDir
    Path dir;

    private HeadlineArchive archive() {
        return new HeadlineArchive(dir.resolve("headlines.jsonl"));
    }

    private static HeadlineRecord rec(String cluster, String text, long createdAt, String ticker) {
        return new HeadlineRecord(cluster, text, "", createdAt, List.of(), List.of(),
                HeadlineHighlight.NORMAL, ticker,
                ticker == null ? List.of() : List.of(new HeadlineSubject("X", ticker)),
                null, List.of(), "stock", HeadlineSentiment.BULLISH, null);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> items(Map<String, Object> response) {
        return (List<Map<String, Object>>) response.get("items");
    }

    @Test
    void searchRespondsWithMatchesAndEchoesTheRequestId() {
        HeadlineArchive a = archive();
        a.append(rec("t3_a", "NVIDIA zieht an", 1000L, "NVDA"));
        a.append(rec("t3_b", "Gold glänzt", 2000L, null));

        Map<String, Object> r = ArchiveQueryBridge.respond(a,
                Map.of("command", "search", "query", "nvidia", "requestId", 7));
        assertEquals("search", r.get("command"));
        assertEquals(7, r.get("requestId"));
        assertEquals(1, r.get("total"));
        assertEquals("NVIDIA zieht an", items(r).get(0).get("headline"));
    }

    @Test
    void tickerCommandUsesTheByTickerIndex() {
        HeadlineArchive a = archive();
        a.append(rec("t3_a", "NVIDIA zieht an", 1000L, "NVDA"));
        a.append(rec("t3_b", "Chips schwach", 2000L, "NVDA"));
        a.append(rec("t3_c", "Gold glänzt", 3000L, null));

        Map<String, Object> r = ArchiveQueryBridge.respond(a,
                Map.of("command", "ticker", "symbol", "nvda"));
        assertEquals(2, r.get("total"));
        assertEquals("Chips schwach", items(r).get(0).get("headline"), "newest first");
        assertEquals("nvda", r.get("query"), "symbol echoed for the UI");
    }

    @Test
    void recentIsNewestFirstAndItemsAreCappedWhileTotalIsNot() {
        HeadlineArchive a = archive();
        long now = System.currentTimeMillis() / 1000;
        for (int i = 0; i < 7; i++) {
            a.append(rec("t3_" + i, "Zeile " + i, now - i, null));
        }
        Map<String, Object> r = ArchiveQueryBridge.respond(a,
                Map.of("command", "recent", "limit", 3));
        assertEquals(7, r.get("total"), "total carries the uncapped hit count");
        assertEquals(3, items(r).size(), "items capped at the requested limit");
        assertEquals("Zeile 0", items(r).get(0).get("headline"), "newest first");
    }

    @Test
    void unknownOrMissingCommandYieldsAnEmptyResult() {
        Map<String, Object> r = ArchiveQueryBridge.respond(archive(), Map.of());
        assertEquals(0, r.get("total"));
        assertTrue(items(r).isEmpty());
    }
}
