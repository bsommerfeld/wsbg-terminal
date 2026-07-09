package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.feargreed.FearGreedIndex;
import de.bsommerfeld.wsbg.terminal.feargreed.FearGreedMonitorService;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Forwards every Fear &amp; Greed reading from {@link FearGreedMonitorService} to
 * connected clients as a typed {@code fear-greed} websocket envelope, and re-sends
 * the cached reading on client open so a fresh page renders the gauge at once.
 * Mirrors {@link EurUsdPublisher}.
 */
@Singleton
public final class FearGreedPublisher {

    private final PushHub hub;

    @Inject
    public FearGreedPublisher(FearGreedMonitorService monitor, PushHub hub) {
        this.hub = hub;
        monitor.addListener(this::push);
        hub.onClientOpen(() -> monitor.getCurrent().ifPresent(this::push));
    }

    private void push(FearGreedIndex idx) {
        hub.broadcastSafe("fear-greed", () -> toJson(idx));
    }

    private static Map<String, Object> toJson(FearGreedIndex idx) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("score", Math.round(idx.score()));
        m.put("rating", idx.rating());
        m.put("band", idx.band().name());
        m.put("previousClose", Math.round(idx.previousClose()));
        m.put("fetchedAt", idx.fetchedAt().toString());
        // ~1y daily series for the detail widget's chart: [[epochMs, score], ...]
        // (compact pairs, already capped client-side in FearGreedClient).
        m.put("history", idx.history().stream()
                .map(p -> java.util.List.of(p.epochMs(), Math.round(p.score() * 10) / 10.0))
                .toList());
        return m;
    }
}
