package de.bsommerfeld.wsbg.terminal.ui.bridge;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import de.bsommerfeld.wsbg.terminal.fj.FjScraper;
import de.bsommerfeld.wsbg.terminal.ui.web.PushHub;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
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

    private final List<RawNewsItem> buffer = new ArrayList<>();

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
            List<RawNewsItem> fresh = scraper.fetch();
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

    private static Map<String, Object> toJson(RawNewsItem it) {
        List<String> tags = extractTags(it.title());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("guid", it.uuid());
        m.put("title", it.title());
        m.put("description", it.summary());
        m.put("publishedUtc", it.publishedAt() == null ? 0L : it.publishedAt().getEpochSecond());
        m.put("imageUrl", it.imageUrl());
        m.put("tags", tags);
        m.put("isRed", isRed(it.title(), tags));
        return m;
    }

    /**
     * Derives display tags from a FinancialJuice headline. Pure presentation —
     * lives here (not in the source) because tags are a UI concern, recomputed at
     * render time rather than carried on the transport-neutral {@code RawNewsItem}.
     */
    private static List<String> extractTags(String title) {
        List<String> tags = new ArrayList<>();
        String lower = title.toLowerCase(Locale.ROOT);

        if (lower.contains("oil") || lower.contains("energy") || lower.contains("opec") || lower.contains("wti") || lower.contains("brent")) {
            tags.add("Energy");
        }
        if (lower.matches(".*\\b(iran|israel|war|lebanon|strait|hormuz|gaza|idf|geopolitics)\\b.*")) {
            tags.add("Geopolitics");
        }
        if (lower.matches(".*\\b(ecb|eur|europe|germany|france)\\b.*")) {
            tags.add("EUR");
            tags.add("Europe");
        }
        if (lower.matches(".*\\b(fed|powell|usd|us)\\b.*")) {
            tags.add("USD");
        }
        if (lower.matches(".*\\b(s&p|nasdaq|dow|spy|qqq|indexes|mag 7|moo imbalance)\\b.*")) {
            tags.add("US Indexes");
        }
        if (lower.matches(".*\\b(bonds|treasury|yield)\\b.*")) {
            tags.add("US Bonds");
        }
        if (lower.matches(".*\\b(boj|ueda|japan|jpy)\\b.*")) {
            tags.add("Asia");
            tags.add("JPY");
        }
        if (lower.matches(".*\\b(china|pboc|cny)\\b.*")) {
            tags.add("China");
        }
        if (lower.matches(".*\\b(boe|uk|gbp|starmer)\\b.*")) {
            tags.add("UK");
            tags.add("GBP");
        }

        return tags.stream().distinct().toList();
    }

    /** High-impact flag for UI styling — geopolitics or market-moving language. */
    private static boolean isRed(String title, List<String> tags) {
        String lower = title.toLowerCase(Locale.ROOT);
        if (tags.contains("Geopolitics")) return true;
        return lower.contains("blockade") || lower.contains("attack") || lower.contains("missile")
                || lower.contains("urgent") || lower.contains("market moving");
    }
}
