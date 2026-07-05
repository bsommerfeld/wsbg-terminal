package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.db.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.db.HeadlineArchive;
import de.bsommerfeld.wsbg.terminal.db.WatchlistStore;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Watchlist over the permanent {@link HeadlineArchive}. The user watches
 * ticker symbols ({@link WatchlistStore}, persisted); for every watched
 * symbol the page receives the archived headline history — "everything the
 * wire ever said about NVDA".
 *
 * <p>Inbound: {@code {type: "watchlist", payload: {command: "add"|"remove"|"list",
 * symbol?}}}. Outbound (after every mutation, on {@code list}, and on client
 * open): one {@code watchlist} broadcast of shape
 * {@code {tickers: [...], entries: [{ticker, total, headlines: [row JSON…]}]}}
 * — per symbol the newest {@link #HEADLINES_PER_TICKER} rows in the shared
 * {@link HeadlineJson} shape, {@code total} carrying the full archive count.
 */
@Singleton
public final class WatchlistBridge {

    private static final Logger LOG = LoggerFactory.getLogger(WatchlistBridge.class);

    /** Rows per watched symbol in the push — history beyond this is one archive "ticker" query away. */
    static final int HEADLINES_PER_TICKER = 5;

    private final WatchlistStore store;
    private final HeadlineArchive archive;
    private final PushHub hub;

    @Inject
    public WatchlistBridge(WatchlistStore store, HeadlineArchive archive, PushHub hub) {
        this.store = store;
        this.archive = archive;
        this.hub = hub;
        hub.on("watchlist", this::onCommand);
        hub.onClientOpen(this::push);
    }

    private void onCommand(Map<String, Object> payload) {
        try {
            Object cmd = payload.get("command");
            String sym = Payloads.str(payload.get("symbol"));
            if ("add".equals(cmd) && sym != null) {
                store.add(sym);
            } else if ("remove".equals(cmd) && sym != null) {
                store.remove(sym);
            }
            // "list" (and any mutation) answers with the full snapshot.
            push();
        } catch (Exception e) {
            LOG.warn("watchlist command failed: {}", e.getMessage());
        }
    }

    private void push() {
        hub.broadcast("watchlist", snapshot(store, archive));
    }

    /** Builds the full watchlist payload. Package-private for testing (no socket needed). */
    static Map<String, Object> snapshot(WatchlistStore store, HeadlineArchive archive) {
        List<String> tickers = store.all();
        List<Map<String, Object>> entries = new ArrayList<>(tickers.size());
        for (String ticker : tickers) {
            List<HeadlineRecord> history = archive.byTicker(ticker); // newest first
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("ticker", ticker);
            e.put("total", history.size());
            e.put("headlines", HeadlineJson.toJson(
                    history.size() <= HEADLINES_PER_TICKER
                            ? history : history.subList(0, HEADLINES_PER_TICKER)));
            entries.add(e);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tickers", tickers);
        out.put("entries", entries);
        return out;
    }
}
