package de.bsommerfeld.wsbg.terminal.feargreed;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.config.NetConfig;
import de.bsommerfeld.wsbg.terminal.core.util.PollingMonitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Polls {@link CryptoFearGreedClient} and holds the latest
 * {@link CryptoFearGreedIndex} in memory, fanning each new reading out to
 * listeners. The crypto index fixes once per day (midnight UTC), so the
 * cadence is deliberately lazy ({@value #POLL_INTERVAL_SECONDS}s) — it only
 * has to catch the daily flip reasonably fast. Mirrors
 * {@link FearGreedMonitorService}.
 */
@Singleton
public class CryptoFearGreedMonitorService extends PollingMonitor<CryptoFearGreedIndex> {

    private static final Logger LOG = LoggerFactory.getLogger(CryptoFearGreedMonitorService.class);

    /** Zero initial delay — same reasoning as the CNN monitor: fast first paint, runs off-thread. */
    private static final long INITIAL_DELAY_SECONDS = 0;
    /** 30 min — the index only changes once a day; anything faster re-fetches an unchanged value. */
    private static final long POLL_INTERVAL_SECONDS = 1800;

    private final CryptoFearGreedClient client;
    private final double pollJitterPercent;

    public CryptoFearGreedMonitorService() {
        this(new CryptoFearGreedClient(), true, new NetConfig().getPollJitterPercent());
    }

    @Inject
    public CryptoFearGreedMonitorService(CryptoFearGreedClient client, GlobalConfig config) {
        // Production (Guice) path: build WITHOUT auto-starting — uniform with the
        // other monitors (AppMain starts them all after the window brought CEF up),
        // even though this client's direct transport never touches JCEF.
        this(client, false,
                config == null || config.getNet() == null
                        ? new NetConfig().getPollJitterPercent()
                        : config.getNet().getPollJitterPercent());
    }

    CryptoFearGreedMonitorService(CryptoFearGreedClient client, boolean autoStart,
            double pollJitterPercent) {
        super("crypto-fear-greed-monitor");
        this.client = client;
        this.pollJitterPercent = pollJitterPercent;
        if (autoStart) start();
    }

    /** Starts the poll loop. Idempotent — safe whether called by the ctor or AppMain. */
    public void start() {
        if (!beginStart()) return;
        LOG.info("Starting crypto Fear&Greed monitor (interval: {} seconds, jitter {}%)",
                POLL_INTERVAL_SECONDS, pollJitterPercent);
        scheduleTick(this::tick, INITIAL_DELAY_SECONDS, POLL_INTERVAL_SECONDS, pollJitterPercent);
    }

    /** Single poll cycle: fetch, update the cache, fan out. Never lets the executor see an exception. */
    void tick() {
        try {
            Optional<CryptoFearGreedIndex> next = client.fetch();
            if (next.isEmpty()) {
                LOG.debug("Crypto Fear&Greed unavailable this tick; keeping last reading");
                return;
            }
            CryptoFearGreedIndex idx = next.get();
            setCurrent(idx);
            LOG.debug("Crypto Fear&Greed = {} ({})", Math.round(idx.score()), idx.band());
            fanOut(idx);
        } catch (Exception e) {
            LOG.error("Crypto Fear&Greed tick failed", e);
        }
    }

    @Override
    protected void onListenerError(Consumer<CryptoFearGreedIndex> listener, Exception e) {
        LOG.warn("Crypto Fear&Greed listener {} threw: {}", listener, e.getMessage());
    }
}
