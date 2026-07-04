package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.db.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.db.HeadlineArchive;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;

import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The page's query window into the permanent {@link HeadlineArchive}. Inbound
 * messages have shape {@code {type: "archive", payload: {command, …}}}:
 * <ul>
 *   <li>{@code {command: "search", query: "nvidia", limit?: 100}} — full-text
 *       over headline text / tickers / subjects / sectors.</li>
 *   <li>{@code {command: "ticker", symbol: "NVDA", limit?: 100}} — everything
 *       the wire ever said about one instrument (primary or subject mention).</li>
 *   <li>{@code {command: "recent", hours?: 24, limit?: 100}} — the newest slice
 *       of the archive.</li>
 * </ul>
 * Every request is answered with one {@code archive-results} broadcast:
 * {@code {command, query, total, items: [headline-row JSON…]}} — items capped
 * at {@code limit} (newest first), {@code total} carries the uncapped hit
 * count, and the rows use the same {@link HeadlineJson} shape as the live
 * wire, so the page renders archive hits with the existing row renderer.
 * An optional {@code requestId} is echoed back so the JS can match
 * request → response.
 */
@Singleton
public final class ArchiveQueryBridge {

    /** Default/maximum rows shipped per response — the socket is not a bulk-export channel. */
    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 500;
    static final int DEFAULT_RECENT_HOURS = 24;

    private final HeadlineArchive archive;
    private final PushHub hub;

    @Inject
    public ArchiveQueryBridge(HeadlineArchive archive, PushHub hub) {
        this.archive = archive;
        this.hub = hub;
        hub.on("archive", this::onQuery);
    }

    private void onQuery(Map<String, Object> payload) {
        hub.broadcastSafe("archive-results", () -> respond(archive, payload));
    }

    /** Builds the full response for one query payload. Package-private for testing (no socket needed). */
    static Map<String, Object> respond(HeadlineArchive archive, Map<String, Object> payload) {
        String command = Payloads.str(payload.get("command"));
        int limit = clampLimit(payload.get("limit"));

        List<HeadlineRecord> hits = switch (command == null ? "" : command) {
            case "search" -> archive.search(Payloads.str(payload.get("query")));
            case "ticker" -> archive.byTicker(Payloads.str(payload.get("symbol")));
            case "recent" -> newestFirst(archive.recent(
                    Duration.ofHours(Payloads.intOr(payload.get("hours"), DEFAULT_RECENT_HOURS))));
            // scroll-back: older headlines before a cursor (the lowest createdAt shown so far).
            case "page" -> archive.page(Payloads.longOr(payload.get("before"), 0L), limit);
            default -> List.of();
        };

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("command", command);
        Object requestId = payload.get("requestId");
        if (requestId != null) out.put("requestId", requestId);
        out.put("query", Payloads.str(payload.get("query")) != null ? Payloads.str(payload.get("query"))
                : Payloads.str(payload.get("symbol")));
        out.put("total", hits.size());
        out.put("items", HeadlineJson.toJson(hits.size() <= limit ? hits : hits.subList(0, limit)));
        return out;
    }

    /** recent() returns oldest first (the wire seed order) — responses always ship newest first. */
    private static List<HeadlineRecord> newestFirst(List<HeadlineRecord> records) {
        return records.stream()
                .sorted(Comparator.comparingLong(HeadlineRecord::createdAt).reversed())
                .toList();
    }

    private static int clampLimit(Object raw) {
        int limit = Payloads.intOr(raw, DEFAULT_LIMIT);
        return Math.max(1, Math.min(limit, MAX_LIMIT));
    }
}
