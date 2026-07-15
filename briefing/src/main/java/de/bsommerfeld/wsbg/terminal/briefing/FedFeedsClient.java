package de.bsommerfeld.wsbg.terminal.briefing;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Federal Reserve Board press feeds (live-probed 2026-07-14): keyless RSS 2.0
 * on {@code federalreserve.gov} — {@code feeds/press_all.xml} carries every
 * Board press release (monetary policy, enforcement, banking) and
 * {@code feeds/speeches.xml} the officials' speeches with venue in the
 * description. Item shape pinned: title, CDATA link/guid, {@code category}
 * ("Monetary Policy", "Speech", ...), RFC-1123 {@code pubDate}.
 *
 * <p>Feed quirk pinned by probe: both files open with a UTF-8 BOM before the
 * XML declaration — StAX rejects that prolog, so the body is BOM-stripped
 * before parsing.
 */
@Singleton
public class FedFeedsClient {

    private static final Logger LOG = LoggerFactory.getLogger(FedFeedsClient.class);

    private static final String PRESS_URL = "https://www.federalreserve.gov/feeds/press_all.xml";
    private static final String SPEECHES_URL = "https://www.federalreserve.gov/feeds/speeches.xml";
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    /** Which Fed feed an item came from. */
    public enum Feed { PRESS, SPEECHES }

    /** One dated Fed item; {@code category} null when the feed didn't carry one. */
    public record Item(String title, String link, String category,
            Instant publishedAt, Feed feed) {
    }

    private record Cached(Instant at, List<Item> items) {
    }

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(15);
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /** Test/default: plain direct transport. */
    public FedFeedsClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam - browser-first since the 2026-07-14 joker mandate. */
    @Inject
    public FedFeedsClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The Board's latest press releases, feed order (newest first). Empty on any failure. */
    public List<Item> pressReleases(int limit) {
        return cap(fetch(PRESS_URL, Feed.PRESS), limit);
    }

    /** The latest official speeches, feed order (newest first). Empty on any failure. */
    public List<Item> speeches(int limit) {
        return cap(fetch(SPEECHES_URL, Feed.SPEECHES), limit);
    }

    private List<Item> fetch(String url, Feed feed) {
        Cached hit = cache.get(url);
        if (hit != null && hit.at().isAfter(Instant.now().minus(CACHE_TTL))) {
            return hit.items();
        }
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", userAgent,
                            "Accept", "application/rss+xml, application/xml, text/xml"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) {
                List<Item> items = parse(resp.body(), feed);
                if (!items.isEmpty()) {
                    cache.put(url, new Cached(Instant.now(), items));
                }
                return items;
            }
            LOG.debug("[Fed] {} answered status {}", url, resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[Fed] fetch {} failed: {}", url, e.getMessage());
        }
        // An outage keeps the stale pool instead of caching empty.
        return hit != null ? hit.items() : List.of();
    }

    /** Package-private for tests: feed XML (BOM-tolerant) → items, network-free. */
    static List<Item> parse(String xml, Feed feed) {
        List<Item> out = new ArrayList<>();
        for (Rss.Item item : Rss.parse(stripBom(xml))) {
            out.add(new Item(item.title(), item.link(),
                    item.category().isBlank() ? null : item.category(),
                    item.publishedAt(), feed));
        }
        return out;
    }

    /** The Fed feeds ship a UTF-8 BOM before the XML declaration — StAX rejects it. */
    static String stripBom(String s) {
        return s != null && !s.isEmpty() && s.charAt(0) == '\uFEFF' ? s.substring(1) : s;
    }

    private static List<Item> cap(List<Item> list, int limit) {
        return list.size() > limit ? List.copyOf(list.subList(0, limit)) : list;
    }
}
