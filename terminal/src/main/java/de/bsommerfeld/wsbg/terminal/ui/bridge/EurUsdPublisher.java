package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.currency.EurUsdMonitorService;
import de.bsommerfeld.wsbg.terminal.currency.EurUsdQuote;
import de.bsommerfeld.wsbg.terminal.currency.FxDetails;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Forwards every EUR/USD quote produced by {@link EurUsdMonitorService}
 * to connected clients as a typed {@code eurusd} websocket envelope.
 * On client open the most recent cached quote is re-sent so a freshly
 * loaded page renders the rate immediately instead of waiting for the
 * next poll tick.
 *
 * <p>One payload, two views (mirrors the fear-greed pattern): the FJ header
 * badge reads only {@code rate}/{@code direction}; the EUR/USD detail widget
 * additionally consumes the {@link FxDetails} block (day range, 52-week band,
 * intraday spark, the ~1y ECB series and the latest ECB fix). Detail fields
 * are simply absent when a tick didn't carry them — the JS guards.
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
        FxDetails d = q.details();
        if (d != null) {
            putIf(m, "previousClose", d.previousClose());
            putIf(m, "dayHigh", d.dayHigh());
            putIf(m, "dayLow", d.dayLow());
            putIf(m, "week52High", d.week52High());
            putIf(m, "week52Low", d.week52Low());
            putIf(m, "ecbRate", d.ecbRate());
            putIf(m, "ecbDate", d.ecbDate());
            if (!d.spark().isEmpty()) m.put("spark", pairs(d.spark()));
            if (!d.history().isEmpty()) m.put("history", pairs(d.history()));
        }
        return m;
    }

    private static void putIf(Map<String, Object> m, String key, Object v) {
        if (v != null) m.put(key, v);
    }

    /** [epochMs, rate] pairs → JSON-friendly nested lists (rates rounded to 5 dp). */
    private static List<List<Object>> pairs(List<double[]> points) {
        return points.stream()
                .map(p -> List.<Object>of((long) p[0], Math.round(p[1] * 1e5) / 1e5))
                .toList();
    }
}
