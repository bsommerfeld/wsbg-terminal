package de.bsommerfeld.wsbg.terminal.ui.bridge;

import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository.HeadlineRecord;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one headline-record → socket-JSON serialisation, shared by every
 * publisher/bridge that ships {@link HeadlineRecord}s to the page (live wire,
 * archive search results, watchlist history). One shape on the socket means
 * the JS renderer for a headline row works everywhere.
 */
final class HeadlineJson {

    private HeadlineJson() {
    }

    static List<Map<String, Object>> toJson(List<HeadlineRecord> records) {
        return records.stream().map(HeadlineJson::toJson).toList();
    }

    static Map<String, Object> toJson(HeadlineRecord r) {
        // Linked map preserves insertion order in the JSON output —
        // simpler to inspect during debugging than a HashMap.
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("clusterId", r.clusterId());
        m.put("headline", r.headline());
        m.put("createdAt", r.createdAt());
        m.put("highlight", r.highlight() == null ? "NORMAL" : r.highlight().name());
        m.put("tickerSymbol", r.tickerSymbol());
        m.put("subjects", r.subjects().stream().map(s -> {
            Map<String, Object> sm = new LinkedHashMap<>();
            sm.put("name", s.name());
            sm.put("ticker", s.ticker());
            return sm;
        }).toList());
        m.put("priceMovePercent", r.priceMovePercent());
        m.put("sectors", r.sectors());
        m.put("assetClass", r.assetClass());
        m.put("sentiment", r.sentiment() == null ? "NEUTRAL" : r.sentiment().name());
        m.put("snapshot", snapshotJson(r.snapshot()));
        // Provenance: the line carries a concrete Yahoo datum (a validated
        // ticker, a live snapshot, or a price move) → it was enriched with
        // Yahoo Finance data, which the UI flags with a subtle mark. Derived
        // from persisted fields, so it needs no schema/snapshot change.
        m.put("usedYahoo", r.tickerSymbol() != null || r.snapshot() != null
                || r.priceMovePercent() != null);
        // Provenance: the compose stage leaned on ≥1 external news item. A quiet
        // bottom-right "News" tag in the UI; defaults false for old archive lines.
        m.put("newsEnriched", r.newsEnriched());
        // Source thread → a permalink the UI offers as an "open in browser" button.
        String threadId = primaryThreadId(r);
        if (threadId != null) {
            m.put("threadUrl", "https://www.reddit.com/comments/" + threadId.substring(3));
        }
        return m;
    }

    /** The Reddit thread id the line rests on: a cited source thread, else the cluster id when it is one. */
    private static String primaryThreadId(HeadlineRecord r) {
        if (r.sourceThreadIds() != null) {
            for (String id : r.sourceThreadIds()) {
                if (id != null && id.startsWith("t3_")) return id;
            }
        }
        String c = r.clusterId();
        return c != null && c.startsWith("t3_") ? c : null;
    }

    /**
     * Serialises the live market snapshot for the frontend quote strip.
     * Returns {@code null} when there's no snapshot or it carries no price,
     * so the renderer can cheaply skip the strip. NaN scalars are mapped
     * to {@code null} (JSON has no NaN); the sparkline series is sent raw
     * for the JS to normalise into an SVG path.
     */
    static Map<String, Object> snapshotJson(MarketSnapshot s) {
        if (s == null || !s.hasPrice()) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("symbol", s.symbol());
        m.put("price", s.price());
        m.put("changePercent", finite(s.dayChangePercent()));
        m.put("dayHigh", finite(s.dayHigh()));
        m.put("dayLow", finite(s.dayLow()));
        m.put("fiftyTwoWeekHigh", finite(s.fiftyTwoWeekHigh()));
        m.put("fiftyTwoWeekLow", finite(s.fiftyTwoWeekLow()));
        m.put("volume", s.volume() < 0 ? null : s.volume());
        m.put("currency", s.currency());
        // Provenance + freshness: which venue priced it (L&S / Deutsche Börse / NASDAQ /
        // Yahoo exchange) and when, so the UI can label the source and dim the quote
        // once the market has closed (no live venue → the figure is a last close).
        m.put("source", s.exchangeName());
        m.put("marketTime", s.marketTimeEpochSeconds() > 0 ? s.marketTimeEpochSeconds() : null);
        m.put("spark", s.spark());
        return m;
    }

    /** NaN/Inf → null so the value serialises as valid JSON. */
    private static Double finite(double d) {
        return Double.isFinite(d) ? d : null;
    }
}
