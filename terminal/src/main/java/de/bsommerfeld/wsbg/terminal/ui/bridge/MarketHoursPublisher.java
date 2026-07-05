package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.ui.market.MarketHoursService;
import de.bsommerfeld.wsbg.terminal.ui.market.MarketRegion;
import de.bsommerfeld.wsbg.terminal.ui.market.MarketSession;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Ships the full trading calendar (next 14 days, per region) to the
 * page on connect and once an hour thereafter. The page derives the
 * live state (pre / main / post / closed) and countdown purely
 * client-side from that data — no per-second WebSocket traffic.
 */
@Singleton
public final class MarketHoursPublisher {

    private static final List<MarketRegion> REGIONS = List.of(
            MarketRegion.DE, MarketRegion.US, MarketRegion.ASIA, MarketRegion.AUSTRALIA);

    private final MarketHoursService service;
    private final PushHub hub;
    private final ScheduledExecutorService scheduler = DaemonSchedulers.scheduled("market-hours-publisher");

    @Inject
    public MarketHoursPublisher(MarketHoursService service, PushHub hub) {
        this.service = service;
        this.hub = hub;
        // Push on every fresh WebSocket connect. That's all that's
        // needed: the page caches the full 14-day session table client-
        // side and recomputes "now" every second from JS Date.now().
        // Holidays come from the bundled JSON snapshot, so the
        // information set is complete at first push.
        //
        // A safety re-push every 12 h catches the rare overnight case
        // where the lookahead window starts to expire while the app
        // is still running.
        hub.onClientOpen(this::push);
        scheduler.scheduleAtFixedRate(this::push, 12 * 3600, 12 * 3600, TimeUnit.SECONDS);
    }

    private void push() {
        List<Map<String, Object>> payload = REGIONS.stream().map(region -> {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("symbol", region.symbol());
            r.put("zone", region.zone().getId());
            r.put("sessions", service.upcomingSessions(region).stream().map(this::toJson).toList());
            return r;
        }).toList();
        hub.broadcast("market-hours", payload);
    }

    private Map<String, Object> toJson(MarketSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", s.state());
        m.put("startUtcMs", s.startUtcMs());
        m.put("endUtcMs", s.endUtcMs());
        return m;
    }
}
