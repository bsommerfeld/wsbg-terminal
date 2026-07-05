package de.bsommerfeld.wsbg.terminal.finanznachrichten;

import de.bsommerfeld.wsbg.terminal.core.util.JitteredScheduler;
import de.bsommerfeld.wsbg.terminal.core.util.PollingMonitor;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Periodically reads a chosen set of finanznachrichten.de RSS {@link FnFeed}s
 * and pushes newly-seen {@link RawNewsItem}s to registered listeners.
 *
 * <h3>Choosing feeds</h3>
 * The feed selection is passed as varargs to {@link #start(FnFeed...)}:
 *
 * <pre>{@code
 * FnMonitorService monitor = new FnMonitorService();
 * monitor.addListener(item -> System.out.println(item.title()));
 *
 * monitor.start(FnFeed.AKTIEN_NACHRICHTEN, FnFeed.DAX_40_NACHRICHTEN_1);  // a few
 * monitor.start(FnFeed.of(FnCategory.BRANCHE));                           // a bucket
 * monitor.start();                                                        // ALL 134 feeds
 * }</pre>
 *
 * <p>Each tick fetches the selected feeds <b>sequentially</b> with a polite
 * {@link FinanznachrichtenConfig#getInterRequestDelayMillis() pause} between
 * requests, de-duplicates by article link across all ticks (the feeds carry no
 * {@code guid}), and fans each genuinely new item out to listeners on the
 * monitor's own daemon thread.
 *
 * <h3>Polling cadence</h3>
 * The feeds refresh roughly every 10 minutes (per the site's terms), so the
 * effective interval is {@link FinanznachrichtenConfig#getPollIntervalSeconds()}
 * floored at {@value #MIN_POLL_INTERVAL_SECONDS}s — faster polling fetches
 * nothing new and is needlessly aggressive toward a free service. That floor is
 * a terms-of-use constraint and stays in this concrete monitor, not the base.
 *
 * <h3>Isolation</h3>
 * Like the {@code currency} module's monitor this is intentionally stand-alone:
 * it does not touch {@code GlobalConfig}, the {@code ApplicationEventBus} or any
 * UI. Wiring it into the terminal is a matter of registering it as an eager
 * singleton and forwarding {@link #addListener(Consumer)} items over the
 * websocket — see {@code EurUsdMonitorService} for the pattern.
 *
 * <p>The scheduler / listener fan-out / shutdown scaffolding lives in
 * {@link PollingMonitor}; this monitor is a <b>multi-emit</b> variant (one sweep
 * emits many items, there is no single "current" value) and manages its own
 * restartable loop via a cancellable {@link JitteredScheduler.Handle}.
 */
@Singleton
public class FnMonitorService extends PollingMonitor<RawNewsItem> {

    private static final Logger LOG = LoggerFactory.getLogger(FnMonitorService.class);

    /**
     * Hard floor on the poll interval. The feeds advertise a 10-minute refresh
     * cadence; 5 minutes is already generous headroom below that.
     */
    static final long MIN_POLL_INTERVAL_SECONDS = 300;

    /** Short, non-zero initial delay so the first sweep doesn't race construction. */
    private static final long INITIAL_DELAY_SECONDS = 5;

    private final FeedFetcher fetcher;
    private final long pollIntervalSeconds;
    private final long interRequestDelayMillis;
    private final double pollJitterPercent;

    /** Article links already emitted, across all ticks (no {@code guid} in the feeds). */
    private final Set<String> seenLinks = ConcurrentHashMap.newKeySet();

    private volatile List<FnFeed> feeds = List.of();
    private volatile JitteredScheduler.Handle task;

    /** Convenience: a real {@link FnRssClient} with default config. */
    public FnMonitorService() {
        this(new FnRssClient(), new FinanznachrichtenConfig());
    }

    @Inject
    public FnMonitorService(FnRssClient client, FinanznachrichtenConfig config) {
        this((FeedFetcher) client, config);
    }

    /** Primary constructor — any {@link FeedFetcher} (real or test double). */
    public FnMonitorService(FeedFetcher fetcher, FinanznachrichtenConfig config) {
        super("finanznachrichten-monitor");
        this.fetcher = fetcher;
        this.pollIntervalSeconds = Math.max(MIN_POLL_INTERVAL_SECONDS, config.getPollIntervalSeconds());
        this.interRequestDelayMillis = Math.max(0, config.getInterRequestDelayMillis());
        this.pollJitterPercent = config.getPollJitterPercent();
    }

    /**
     * Begins (or restarts) the polling loop over {@code selected} feeds. Passing
     * no feeds selects <b>all</b> of them. Duplicate feeds are collapsed and the
     * de-duplication state is reset, so the first sweep re-emits the current head
     * of each selected feed. Calling this again replaces the selection.
     *
     * @return the distinct feeds that will be polled, in the order they'll be hit
     */
    public synchronized List<FnFeed> start(FnFeed... selected) {
        List<FnFeed> chosen = (selected == null || selected.length == 0)
                ? Arrays.asList(FnFeed.values())
                : new ArrayList<>(new LinkedHashSet<>(Arrays.asList(selected)));

        stop();
        seenLinks.clear();
        this.feeds = List.copyOf(chosen);

        LOG.info("finanznachrichten monitor starting: {} feed(s), every {}s (≈{}s/sweep idle)",
                feeds.size(), pollIntervalSeconds,
                (feeds.size() * interRequestDelayMillis) / 1000);

        // Jittered cadence (traffic blending): sweeps vary around the base
        // interval instead of firing machine-exactly.
        this.task = scheduleTick(this::tick,
                INITIAL_DELAY_SECONDS, pollIntervalSeconds, pollJitterPercent);
        return feeds;
    }

    /** Overload accepting a feed {@link List} (e.g. {@code FnFeed.of(category)}). */
    public synchronized List<FnFeed> start(List<FnFeed> selected) {
        return start(selected == null ? new FnFeed[0] : selected.toArray(new FnFeed[0]));
    }

    /**
     * One sweep: fetch each selected feed in order, emit links not seen before.
     * Package-visible so tests can drive a deterministic tick without the
     * scheduler. Catches everything so the scheduled executor never dies.
     */
    void tick() {
        List<FnFeed> snapshot = feeds;
        int newCount = 0;
        try {
            for (int i = 0; i < snapshot.size(); i++) {
                FnFeed feed = snapshot.get(i);
                List<RawNewsItem> items;
                try {
                    items = fetcher.fetch(feed);
                } catch (Exception e) {
                    LOG.warn("fetch of {} threw: {}", feed.slug(), e.getMessage());
                    continue;
                }
                if (items == null) {
                    continue;
                }
                for (RawNewsItem item : items) {
                    if (item.link() != null && !item.link().isEmpty() && seenLinks.add(item.link())) {
                        newCount++;
                        fanOut(item);
                    }
                }
                if (interRequestDelayMillis > 0 && i < snapshot.size() - 1) {
                    Thread.sleep(interRequestDelayMillis);
                }
            }
            if (newCount > 0) {
                LOG.info("finanznachrichten: {} new item(s) across {} feed(s)", newCount, snapshot.size());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            LOG.error("finanznachrichten tick failed", e);
        }
    }

    @Override
    protected void onListenerError(Consumer<RawNewsItem> listener, Exception e) {
        LOG.warn("finanznachrichten listener {} threw: {}", listener, e.getMessage());
    }

    /** The feeds currently being polled (empty until {@link #start} is called). */
    public List<FnFeed> activeFeeds() {
        return feeds;
    }

    /** Effective interval the scheduler uses, in seconds (after the floor). */
    public long pollIntervalSeconds() {
        return pollIntervalSeconds;
    }

    /** Number of distinct article links emitted since the last {@link #start}. */
    public int seenCount() {
        return seenLinks.size();
    }

    /** Stops the current polling task without tearing down the scheduler. */
    public synchronized void stop() {
        JitteredScheduler.Handle t = this.task;
        if (t != null) {
            t.cancel();
            this.task = null;
        }
    }
}
