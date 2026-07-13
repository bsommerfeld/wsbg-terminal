package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.feargreed.CryptoFearGreedIndex;
import de.bsommerfeld.wsbg.terminal.feargreed.CryptoFearGreedMonitorService;
import de.bsommerfeld.wsbg.terminal.feargreed.FearGreedIndex;
import de.bsommerfeld.wsbg.terminal.feargreed.FearGreedMonitorService;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Forwards every Fear &amp; Greed reading from {@link FearGreedMonitorService} to
 * connected clients as a typed {@code fear-greed} websocket envelope, and re-sends
 * the cached reading on client open so a fresh page renders the gauge at once.
 * Mirrors {@link EurUsdPublisher}.
 *
 * <p>One payload, one renderer: the crypto counterpart from
 * {@link CryptoFearGreedMonitorService} rides along as the optional
 * {@code crypto} block (fetched on its own lazy cadence), so the detail widget
 * never has to join two topics. A crypto update re-pushes the latest CNN
 * reading; without a CNN reading nothing is pushed — the composite is the
 * widget's backbone, crypto only complements it.
 */
@Singleton
public final class FearGreedPublisher {

    private final PushHub hub;
    private final FearGreedMonitorService monitor;
    private final CryptoFearGreedMonitorService cryptoMonitor;

    @Inject
    public FearGreedPublisher(FearGreedMonitorService monitor,
            CryptoFearGreedMonitorService cryptoMonitor, PushHub hub) {
        this.hub = hub;
        this.monitor = monitor;
        this.cryptoMonitor = cryptoMonitor;
        monitor.addListener(this::push);
        cryptoMonitor.addListener(c -> monitor.getCurrent().ifPresent(this::push));
        hub.onClientOpen(() -> monitor.getCurrent().ifPresent(this::push));
    }

    private void push(FearGreedIndex idx) {
        CryptoFearGreedIndex crypto = cryptoMonitor.getCurrent().orElse(null);
        hub.broadcastSafe("fear-greed", () -> toJson(idx, crypto));
    }

    private static Map<String, Object> toJson(FearGreedIndex idx, CryptoFearGreedIndex crypto) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("score", Math.round(idx.score()));
        m.put("rating", idx.rating());
        m.put("band", idx.band().name());
        m.put("previousClose", Math.round(idx.previousClose()));
        // The longer look-backs CNN ships beside the close (absent when it didn't).
        putRounded(m, "previousWeek", idx.previousWeek());
        putRounded(m, "previousMonth", idx.previousMonth());
        putRounded(m, "previousYear", idx.previousYear());
        m.put("fetchedAt", idx.fetchedAt().toString());
        // ~1y daily series for the detail widget's chart: [[epochMs, score], ...]
        // (compact pairs, already capped client-side in FearGreedClient).
        m.put("history", pairs(idx.history()));
        // The seven sub-indicators behind the composite (absent when CNN dropped them).
        if (!idx.components().isEmpty()) {
            m.put("components", idx.components().stream()
                    .map(c -> Map.of(
                            "key", c.key(),
                            "score", Math.round(c.score()),
                            "rating", c.rating(),
                            "band", c.band().name()))
                    .toList());
        }
        // The crypto counterpart (own source, own cadence) — optional rider.
        if (crypto != null) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("score", Math.round(crypto.score()));
            c.put("rating", crypto.rating());
            c.put("band", crypto.band().name());
            putRounded(c, "previousClose", crypto.previousClose());
            c.put("fetchedAt", crypto.fetchedAt().toString());
            c.put("history", pairs(crypto.history()));
            m.put("crypto", c);
        }
        return m;
    }

    private static void putRounded(Map<String, Object> m, String key, Double v) {
        if (v != null) m.put(key, Math.round(v));
    }

    private static List<List<Object>> pairs(List<FearGreedIndex.Point> points) {
        return points.stream()
                .map(p -> List.<Object>of(p.epochMs(), Math.round(p.score() * 10) / 10.0))
                .toList();
    }
}
