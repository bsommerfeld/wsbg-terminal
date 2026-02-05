package de.bsommerfeld.wsbg.terminal.db;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.db.DatabaseService.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.db.DatabaseService.TickerMentionRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Write-through caching layer for AI-generated agent data (headlines + ticker
 * mentions).
 *
 * <p>
 * Mirrors the {@link RedditRepository} architecture: in-memory caches provide
 * instant reads, while DB writes are dispatched asynchronously to a dedicated
 * executor. The cache is warmed on first read from the DB, then kept in sync
 * by write-through on every save.
 *
 * <h3>TTL management</h3>
 * Agent data has a fixed 24-hour TTL. {@link #cleanup()} purges both the
 * DB and the in-memory caches. It is called by {@link PassiveMonitorService}
 * via the same hourly cleanup cycle that prunes Reddit threads.
 */
@Singleton
public class AgentRepository {

    private static final Logger LOG = LoggerFactory.getLogger(AgentRepository.class);

    /** 24 hours in seconds. */
    private static final long TTL_SECONDS = 86400;

    private final DatabaseService databaseService;
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();

    private final List<HeadlineRecord> headlineCache = new CopyOnWriteArrayList<>();
    private final List<TimestampedTicker> tickerCache = new CopyOnWriteArrayList<>();
    private volatile boolean cacheWarmed = false;

    @Inject
    public AgentRepository(DatabaseService databaseService) {
        this.databaseService = databaseService;
    }

    // -- Writes (async write-through) --

    /**
     * Persists a headline with context. Cache is updated instantly,
     * DB write is async.
     */
    public void saveHeadline(String clusterId, String headline, String context) {
        long now = System.currentTimeMillis() / 1000;
        headlineCache.add(new HeadlineRecord(clusterId, headline, context, now));
        CompletableFuture.runAsync(
                () -> databaseService.saveHeadline(clusterId, headline, context), dbExecutor);
    }

    /**
     * Persists ticker mentions. Cache is updated instantly,
     * DB write is async (batched).
     */
    public void saveTickerMentions(List<TickerMentionRecord> mentions) {
        if (mentions == null || mentions.isEmpty())
            return;
        long now = System.currentTimeMillis() / 1000;
        for (TickerMentionRecord m : mentions) {
            tickerCache.add(new TimestampedTicker(m.symbol().toUpperCase(), now));
        }
        CompletableFuture.runAsync(
                () -> databaseService.saveTickerMentions(mentions), dbExecutor);
    }

    // -- Reads (cache-first) --

    /**
     * Returns ticker mention counts within the last hour, sorted
     * descending by count. Serves from cache after initial warmup.
     */
    public Map<String, Integer> getTickerCountsLastHour() {
        warmupIfNeeded();
        long cutoff = (System.currentTimeMillis() / 1000) - 3600;
        return tickerCache.stream()
                .filter(t -> t.createdAt >= cutoff)
                .collect(Collectors.groupingBy(
                        TimestampedTicker::symbol,
                        Collectors.summingInt(t -> 1)))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .collect(Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue,
                        (a, b) -> a, LinkedHashMap::new));
    }

    /** Returns headlines from the last 24 hours, newest first. */
    public List<HeadlineRecord> getRecentHeadlines() {
        warmupIfNeeded();
        long cutoff = (System.currentTimeMillis() / 1000) - TTL_SECONDS;
        return headlineCache.stream()
                .filter(h -> h.createdAt() >= cutoff)
                .sorted((a, b) -> Long.compare(b.createdAt(), a.createdAt()))
                .toList();
    }

    // -- Lifecycle --

    /**
     * Purges expired data from both DB and caches.
     * Called from the hourly cleanup cycle.
     */
    public void cleanup() {
        long cutoff = (System.currentTimeMillis() / 1000) - TTL_SECONDS;
        CompletableFuture.runAsync(() -> {
            int removed = databaseService.cleanupAgentData(cutoff);
            if (removed > 0)
                LOG.info("Agent data cleanup: purged {} expired rows", removed);
        }, dbExecutor);

        headlineCache.removeIf(h -> h.createdAt() < cutoff);
        tickerCache.removeIf(t -> t.createdAt < cutoff);
    }

    /** Gracefully drains pending writes before shutdown. */
    public void shutdown() {
        LOG.info("Shutting down AgentRepository...");
        dbExecutor.shutdown();
        try {
            if (!dbExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                dbExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            dbExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // -- Internal --

    private void warmupIfNeeded() {
        if (cacheWarmed)
            return;
        synchronized (this) {
            if (cacheWarmed)
                return;
            long cutoff24h = (System.currentTimeMillis() / 1000) - TTL_SECONDS;
            long cutoff1h = (System.currentTimeMillis() / 1000) - 3600;

            headlineCache.addAll(databaseService.getHeadlinesSince(cutoff24h));

            // Warm ticker cache from DB — we need individual rows, not aggregated counts,
            // to support per-hour filtering. Re-query the raw mentions.
            Map<String, Integer> counts = databaseService.getTickerCountsSince(cutoff1h);
            for (Map.Entry<String, Integer> e : counts.entrySet()) {
                for (int i = 0; i < e.getValue(); i++) {
                    tickerCache.add(new TimestampedTicker(e.getKey(), cutoff1h));
                }
            }

            cacheWarmed = true;
            LOG.info("AgentRepository cache warmed: {} headlines, {} ticker entries",
                    headlineCache.size(), tickerCache.size());
        }
    }

    private record TimestampedTicker(String symbol, long createdAt) {
    }
}
