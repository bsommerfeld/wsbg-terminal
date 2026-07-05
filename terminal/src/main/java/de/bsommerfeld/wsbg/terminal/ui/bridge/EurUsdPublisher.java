package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.currency.EurUsdMonitorService;
import de.bsommerfeld.wsbg.terminal.currency.EurUsdQuote;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Forwards every EUR/USD quote produced by {@link EurUsdMonitorService}
 * to connected clients as a typed {@code eurusd} websocket envelope.
 * On client open the most recent cached quote is re-sent so a freshly
 * loaded page renders the rate immediately instead of waiting for the
 * next poll tick.
 */
@Singleton
public final class EurUsdPublisher {

    private final PushHub hub;

    @Inject
    public EurUsdPublisher(EurUsdMonitorService monitor, PushHub hub) {
        this.hub = hub;
        monitor.addListener(this::push);
        hub.onClientOpen(() -> monitor.getCurrent().ifPresent(this::push));
    }

    private void push(EurUsdQuote q) {
        hub.broadcastSafe("eurusd", () -> toJson(q));
    }

    private static Map<String, Object> toJson(EurUsdQuote q) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rate", q.rate());
        m.put("previousRate", q.previousRate());
        m.put("direction", q.direction().name());
        m.put("source", q.source().name());
        m.put("fetchedAt", q.fetchedAt().toString());
        return m;
    }
}
