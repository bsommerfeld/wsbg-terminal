package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;
import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.impl.sources.financialjuice.FinancialJuiceSource;
import de.bsommerfeld.wsbg.terminal.web.pool.ArticlePool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The FinancialJuice pump: polls the breaking-news wire every 60 s (FJ
 * rate-limits below ~30 s), pushes new items to connected clients, and pours
 * the same yield into the article pool — one poller serves both the live
 * ticker UI and the basin, so the wire keeps its push-grade latency instead
 * of falling to the collector clock's 2-minute floor. FJ is therefore NOT
 * registered as a scheduled collector; this publisher IS its schedule.
 *
 * <p>Keeps an in-memory rolling buffer of the last 200 items so fresh clients
 * receive a snapshot immediately upon connection. Tag/impact classification
 * lives in {@link FjHeadlineTagger}.
 */
@Singleton
public final class FjNewsPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(FjNewsPublisher.class);
    private static final int BUFFER_CAP = 200;
    private static final long POLL_SECONDS = 60;

    private final FinancialJuiceSource source;
    private final ArticlePool pool;
    private final PushHub hub;
    private final ScheduledExecutorService scheduler = DaemonSchedulers.scheduled("fj-publisher");

    private final List<Article> buffer = new ArrayList<>();
    /** collect() returns the full current window — the delta is derived here. */
    private final Set<String> seen = new HashSet<>();

    @Inject
    public FjNewsPublisher(FinancialJuiceSource source, ArticlePool pool, PushHub hub) {
        this.source = source;
        this.pool = pool;
        this.hub = hub;
        hub.onClientOpen(this::sendSnapshot);
        scheduler.schedule(this::poll, 2, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::poll, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
    }

    private void poll() {
        try {
            List<Article> window = source.collect();
            if (window == null || window.isEmpty()) return;
            List<Article> fresh = new ArrayList<>();
            synchronized (buffer) {
                for (Article a : window) {
                    if (seen.add(a.uuid())) fresh.add(a);
                }
                if (fresh.isEmpty()) return;
                // newest first inside the buffer
                for (int i = fresh.size() - 1; i >= 0; i--) buffer.add(0, fresh.get(i));
                while (buffer.size() > BUFFER_CAP) buffer.remove(buffer.size() - 1);
                // Keep the seen-set bounded alongside the buffer.
                if (seen.size() > BUFFER_CAP * 4) {
                    seen.clear();
                    for (Article a : buffer) seen.add(a.uuid());
                }
            }
            // The same yield feeds the basin (origin stamp + dedupe live there).
            pool.add(source, fresh);
            sendSnapshot();
        } catch (Exception e) {
            LOG.warn("fj poll failed: {}", e.getMessage());
        }
    }

    private void sendSnapshot() {
        List<Map<String, Object>> payload;
        synchronized (buffer) {
            payload = buffer.stream().map(FjNewsPublisher::toJson).toList();
        }
        hub.broadcast("fj-news", payload);
    }

    private static Map<String, Object> toJson(Article it) {
        List<String> tags = FjHeadlineTagger.extractTags(it.title());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("guid", it.uuid());
        m.put("title", it.title());
        m.put("description", it.summary());
        m.put("publishedUtc", it.publishedAt() == null ? 0L : it.publishedAt().getEpochSecond());
        m.put("imageUrl", it.imageUrl());
        m.put("tags", tags);
        m.put("isRed", FjHeadlineTagger.isRed(it.title(), tags));
        return m;
    }
}
