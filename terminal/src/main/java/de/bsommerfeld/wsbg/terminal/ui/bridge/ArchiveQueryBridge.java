package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.db.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.db.HeadlineArchive;
import de.bsommerfeld.wsbg.terminal.db.HeadlineSubjectStat;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;

import java.time.Duration;
import java.util.ArrayList;
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
 *   <li>{@code {command: "subject", query: "Rheinmetall", symbol?: "RHM.DE"}} —
 *       one subject by name AND ticker together (union, deduped): a suggestion
 *       click must also find lines that name the subject without a resolved
 *       ticker. Either field may be absent.</li>
 *   <li>{@code {command: "recent", hours?: 24, limit?: 100}} — the newest slice
 *       of the archive.</li>
 *   <li>{@code {command: "subjects"}} — the subject vocabulary (every subject
 *       the wire ever named, aggregated), answered with items of shape
 *       {@code {name, ticker?, count}} instead of headline rows. The page's
 *       search-suggestion resolver runs on this mapping client-side.</li>
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
    /** Vocabulary entries are tiny (~50 bytes) — a generous own cap, most-named first. */
    static final int SUBJECTS_LIMIT = 1000;

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
        if ("subjects".equals(command)) return respondSubjects(archive, payload);
        int limit = clampLimit(payload.get("limit"));

        List<HeadlineRecord> hits = switch (command == null ? "" : command) {
            case "search" -> archive.search(Payloads.str(payload.get("query")));
            case "ticker" -> archive.byTicker(Payloads.str(payload.get("symbol")));
            case "subject" -> archive.searchSubject(Payloads.str(payload.get("query")),
                    Payloads.str(payload.get("symbol")));
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

    /** The subject vocabulary in its own item shape ({@code {name, ticker?, count}}), most-named first. */
    private static Map<String, Object> respondSubjects(HeadlineArchive archive, Map<String, Object> payload) {
        List<HeadlineSubjectStat> stats = archive.subjectStats();
        List<Map<String, Object>> items = new ArrayList<>();
        for (HeadlineSubjectStat s : stats.size() <= SUBJECTS_LIMIT ? stats : stats.subList(0, SUBJECTS_LIMIT)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s.name());
            if (s.ticker() != null) m.put("ticker", s.ticker());
            m.put("count", s.count());
            items.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("command", "subjects");
        Object requestId = payload.get("requestId");
        if (requestId != null) out.put("requestId", requestId);
        out.put("total", stats.size());
        out.put("items", items);
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
