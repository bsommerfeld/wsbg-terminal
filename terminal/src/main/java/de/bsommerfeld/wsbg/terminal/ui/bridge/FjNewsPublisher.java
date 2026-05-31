package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.domain.FjNewsItem;
import de.bsommerfeld.wsbg.terminal.fj.FjScraper;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Polls the FinancialJuice RSS feed and pushes new items to connected
 * clients. Keeps an in-memory rolling buffer of the last 200 items so
 * fresh clients receive a snapshot immediately upon connection.
 *
 * <p>
 * Poll cadence matches the legacy JavaFX widget (60 s) — FJ rate-limits
 * below ~30 s.
 */
@Singleton
public final class FjNewsPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(FjNewsPublisher.class);
    private static final int BUFFER_CAP = 200;
    private static final long POLL_SECONDS = 60;

    private final FjScraper scraper;
    private final PushHub hub;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "fj-publisher");
                t.setDaemon(true);
                return t;
            });

    private final List<FjNewsItem> buffer = new ArrayList<>();

    @Inject
    public FjNewsPublisher(FjScraper scraper, PushHub hub) {
        this.scraper = scraper;
        this.hub = hub;
        hub.onClientOpen(this::sendSnapshot);
        scheduler.schedule(this::poll, 2, TimeUnit.SECONDS);
        scheduler.scheduleAtFixedRate(this::poll, POLL_SECONDS, POLL_SECONDS, TimeUnit.SECONDS);
    }

    private void poll() {
        try {
            List<FjNewsItem> fresh = scraper.fetch();
            if (fresh.isEmpty()) return;
            synchronized (buffer) {
                // newest first inside the buffer
                for (int i = fresh.size() - 1; i >= 0; i--) buffer.add(0, fresh.get(i));
                while (buffer.size() > BUFFER_CAP) buffer.remove(buffer.size() - 1);
            }
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

    private static Map<String, Object> toJson(FjNewsItem it) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("guid", it.guid());
        m.put("title", it.title());
        m.put("description", it.description());
        m.put("publishedUtc", it.publishedUtc());
        m.put("imageUrl", it.imageUrl());
        m.put("tags", it.tags());
        m.put("isRed", it.isRed());
        return m;
    }
}
