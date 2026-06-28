package de.bsommerfeld.wsbg.terminal.ui.bridge;

import de.bsommerfeld.wsbg.terminal.db.AgentRepository.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.db.HeadlineArchive;
import de.bsommerfeld.wsbg.terminal.db.HeadlineHighlight;
import de.bsommerfeld.wsbg.terminal.db.HeadlineSentiment;
import de.bsommerfeld.wsbg.terminal.db.WatchlistStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The watchlist payload: per watched symbol the newest archived headlines + the full count. */
class WatchlistBridgeTest {

    @TempDir
    Path dir;

    private static HeadlineRecord rec(String text, long createdAt, String ticker) {
        return new HeadlineRecord("t3_x", text, "", createdAt, List.of(), List.of(),
                HeadlineHighlight.NORMAL, ticker, List.of(), null, List.of(),
                "stock", HeadlineSentiment.BULLISH, null);
    }

    @Test
    @SuppressWarnings("unchecked")
    void snapshotShipsNewestRowsPerTickerWithTheFullCount() {
        HeadlineArchive archive = new HeadlineArchive(dir.resolve("headlines.jsonl"));
        for (int i = 1; i <= WatchlistBridge.HEADLINES_PER_TICKER + 2; i++) {
            archive.append(rec("NVDA Zeile " + i, 1000L + i, "NVDA"));
        }
        WatchlistStore store = new WatchlistStore(dir.resolve("watchlist.json"));
        store.add("NVDA");
        store.add("TSLA"); // watched but never headlined

        Map<String, Object> snap = WatchlistBridge.snapshot(store, archive);
        assertEquals(List.of("NVDA", "TSLA"), snap.get("tickers"));

        List<Map<String, Object>> entries = (List<Map<String, Object>>) snap.get("entries");
        Map<String, Object> nvda = entries.get(0);
        assertEquals("NVDA", nvda.get("ticker"));
        assertEquals(WatchlistBridge.HEADLINES_PER_TICKER + 2, nvda.get("total"));
        List<Map<String, Object>> rows = (List<Map<String, Object>>) nvda.get("headlines");
        assertEquals(WatchlistBridge.HEADLINES_PER_TICKER, rows.size(), "push is capped");
        assertEquals("NVDA Zeile 7", rows.get(0).get("headline"), "newest first");

        Map<String, Object> tsla = entries.get(1);
        assertEquals(0, tsla.get("total"), "watched-but-quiet symbol still listed");
    }
}
