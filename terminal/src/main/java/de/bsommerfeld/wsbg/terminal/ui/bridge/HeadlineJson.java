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
        return m;
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
        m.put("spark", s.spark());
        return m;
    }

    /** NaN/Inf → null so the value serialises as valid JSON. */
    private static Double finite(double d) {
        return Double.isFinite(d) ? d : null;
    }
}
