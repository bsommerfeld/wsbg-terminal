package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.google.common.eventbus.Subscribe;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.agent.event.AgentStreamEndEvent;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Pushes AI-generated headlines from {@link AgentRepository} to every
 * connected page. Subscribes to {@link AgentStreamEndEvent} so new
 * headlines appear instantly (no polling), and also sends a snapshot
 * whenever a fresh client connects.
 *
 * <p>
 * A 60-second safety poll catches any edge cases where a headline is
 * recorded without an accompanying stream-end event.
 */
@Singleton
public final class HeadlinePublisher {

    private static final Logger LOG = LoggerFactory.getLogger(HeadlinePublisher.class);

    private final AgentRepository repo;
    private final PushHub hub;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "headline-publisher");
                t.setDaemon(true);
                return t;
            });

    /** Fingerprint of the last pushed snapshot — skip re-broadcasting identical state. */
    private volatile String lastSnapshotKey = "";

    @Inject
    public HeadlinePublisher(AgentRepository repo, PushHub hub, ApplicationEventBus bus) {
        this.repo = repo;
        this.hub = hub;
        bus.register(this);
        hub.onClientOpen(() -> pushAll(true));
        scheduler.scheduleAtFixedRate(() -> pushAll(false), 60, 60, TimeUnit.SECONDS);
    }

    @Subscribe
    public void onAgentStreamEnd(AgentStreamEndEvent event) {
        String msg = event.fullMessage();
        if (msg == null || !msg.startsWith("||PASSIVE||")) return;
        scheduler.submit(() -> pushAll(false));
    }

    /**
     * @param force if {@code true}, broadcast even when the snapshot
     *              hash matches the last push — for fresh client opens
     *              where the receiver has no prior state.
     */
    private void pushAll(boolean force) {
        try {
            List<HeadlineRecord> records = repo.getRecentHeadlines();
            // Compact key: count + ids + last createdAt. Cheap to compute,
            // changes whenever anything meaningful changes.
            String key = records.size() + ":" + records.stream()
                    .map(r -> r.clusterId() + "@" + r.createdAt())
                    .reduce((a, b) -> a + "," + b).orElse("");
            if (!force && key.equals(lastSnapshotKey)) {
                return;     // nothing changed since last broadcast
            }
            lastSnapshotKey = key;
            List<Map<String, Object>> payload = records.stream().map(HeadlinePublisher::toJson).toList();
            hub.broadcast("headlines", payload);
        } catch (Exception e) {
            LOG.warn("headline push failed: {}", e.getMessage());
        }
    }

    private static Map<String, Object> toJson(HeadlineRecord r) {
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
    private static Map<String, Object> snapshotJson(MarketSnapshot s) {
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
