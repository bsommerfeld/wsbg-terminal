package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.google.common.eventbus.Subscribe;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.agent.event.AgentStreamEndEvent;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.db.HeadlineSentiment;
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
 * Aggregates the room's mood from the last 24h of published headlines into one
 * badge figure: <b>"54% BEARISH"</b> (which implies 46% bullish). The data is
 * the wire's own sentiment classification ({@link HeadlineRecord#sentiment()}),
 * so no Reddit re-read is needed — pure counting over {@link AgentRepository},
 * whose 24h window is archive-seeded and therefore survives restarts.
 *
 * <p><b>Camp mapping:</b> directional sentiments are counted in two camps —
 * bullish = BULLISH, FOMO, BREAKOUT, SQUEEZE (a squeeze is a long bet on the
 * shorts burning); bearish = BEARISH, CAPITULATION. NEUTRAL, MIXED and
 * REVERSAL carry no direction and are excluded from the percentage (REVERSAL
 * says "the move turned", not which way the room leans). The percentage is
 * {@code dominant camp / (bullish + bearish)} — the two camps always sum to
 * 100%, exactly the "54% → implies 46%" reading the badge wants.
 *
 * <p>Pushed as {@code market-mood} on client open, after every published
 * headline, and on a 60s safety poll (mirrors {@link HeadlinePublisher}).
 * Skips re-broadcasting unchanged figures.
 */
@Singleton
public final class MarketMoodPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(MarketMoodPublisher.class);

    private final AgentRepository repo;
    private final PushHub hub;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "market-mood-publisher");
                t.setDaemon(true);
                return t;
            });

    /** Fingerprint of the last pushed mood — skip re-broadcasting identical state. */
    private volatile String lastKey = "";

    @Inject
    public MarketMoodPublisher(AgentRepository repo, PushHub hub, ApplicationEventBus bus) {
        this.repo = repo;
        this.hub = hub;
        bus.register(this);
        hub.onClientOpen(() -> push(true));
        scheduler.scheduleAtFixedRate(() -> push(false), 60, 60, TimeUnit.SECONDS);
    }

    @Subscribe
    public void onAgentStreamEnd(AgentStreamEndEvent event) {
        String msg = event.fullMessage();
        if (msg == null || !msg.startsWith("||PASSIVE||")) return;
        scheduler.submit(() -> push(false));
    }

    private void push(boolean force) {
        try {
            Map<String, Object> mood = compute(repo.getRecentHeadlines());
            String key = String.valueOf(mood);
            if (!force && key.equals(lastKey)) return;
            lastKey = key;
            hub.broadcast("market-mood", mood);
        } catch (Exception e) {
            LOG.warn("market-mood push failed: {}", e.getMessage());
        }
    }

    /**
     * Counts the camps and derives the badge figures. {@code dominant}/
     * {@code percent} are {@code null} when no directional headline exists yet
     * (the badge then has nothing honest to show). On a dead 50/50 tie the
     * dominant is reported as {@code "MIXED"} with percent 50. Package-private
     * for testing.
     */
    static Map<String, Object> compute(List<HeadlineRecord> records) {
        int bullish = 0;
        int bearish = 0;
        for (HeadlineRecord r : records) {
            HeadlineSentiment s = r.sentiment();
            if (s == null) continue;
            switch (s) {
                case BULLISH, FOMO, BREAKOUT, SQUEEZE -> bullish++;
                case BEARISH, CAPITULATION -> bearish++;
                default -> { /* NEUTRAL / MIXED / REVERSAL: no direction */ }
            }
        }
        int directional = bullish + bearish;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("bullish", bullish);
        m.put("bearish", bearish);
        m.put("directional", directional);
        m.put("total", records.size());
        if (directional == 0) {
            m.put("dominant", null);
            m.put("percent", null);
        } else if (bullish == bearish) {
            m.put("dominant", "MIXED");
            m.put("percent", 50);
        } else {
            boolean bull = bullish > bearish;
            m.put("dominant", bull ? "BULLISH" : "BEARISH");
            m.put("percent", Math.round(100.0 * Math.max(bullish, bearish) / directional));
        }
        return m;
    }
}
