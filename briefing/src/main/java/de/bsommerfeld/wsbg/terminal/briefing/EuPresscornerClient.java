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

/**
 * EU Commission presscorner RSS (live-probed 2026-07-14):
 * {@code ec.europa.eu/commission/presscorner/api/rss?language=en} — keyless,
 * the Commission's own press releases/speeches/statements. Feed ships a FIXED
 * 10 items (its channel description says {@code Search={format=RSS,size=10}});
 * items carry a teaser {@code description}, RFC-1123 GMT {@code pubDate} and
 * a {@code category} of the form {@code POLICY_AREA=<CODE>} (ENVIRO, TRADE,
 * ...) — the code is surfaced as {@code policyArea}, null when absent.
 */
@Singleton
public class EuPresscornerClient {

    private static final Logger LOG = LoggerFactory.getLogger(EuPresscornerClient.class);

    private static final String URL =
            "https://ec.europa.eu/commission/presscorner/api/rss?language=en";
    private static final String POLICY_PREFIX = "POLICY_AREA=";
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);

    /** One Commission press item; {@code policyArea} is the feed's area code or null. */
    public record Item(String title, String link, Instant publishedAt, String policyArea) {
    }

    private record Cached(Instant at, List<Item> items) {
    }

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(15);
    private volatile Cached cache;

    /** Test/default: plain direct transport. */
    public EuPresscornerClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam - browser-first since the 2026-07-14 joker mandate. */
    @Inject
    public EuPresscornerClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The Commission's latest press items, feed order (newest first). Empty on any failure. */
    public List<Item> items(int limit) {
        Cached hit = cache;
        if (hit != null && hit.at().isAfter(Instant.now().minus(CACHE_TTL))) {
            return cap(hit.items(), limit);
        }
        try {
            WebResponse resp = fetcher.fetch(URL,
                    Map.of("User-Agent", userAgent,
                            "Accept", "application/rss+xml, application/xml, text/xml"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) {
                List<Item> items = parse(resp.body());
                if (!items.isEmpty()) {
                    cache = new Cached(Instant.now(), items);
                }
                return cap(items, limit);
            }
            LOG.debug("[Presscorner] answered status {}", resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[Presscorner] fetch failed: {}", e.getMessage());
        }
        // An outage keeps the stale pool instead of caching empty.
        return hit != null ? cap(hit.items(), limit) : List.of();
    }

    /** Package-private for tests: feed XML → items, network-free. */
    static List<Item> parse(String xml) {
        List<Item> out = new ArrayList<>();
        for (Rss.Item item : Rss.parse(xml)) {
            out.add(new Item(item.title(), item.link(), item.publishedAt(),
                    policyArea(item.category())));
        }
        return out;
    }

    /** "POLICY_AREA=ENVIRO" → "ENVIRO"; anything else → null. */
    static String policyArea(String category) {
        if (category == null) return null;
        int i = category.indexOf(POLICY_PREFIX);
        if (i < 0) return null;
        String code = category.substring(i + POLICY_PREFIX.length()).strip();
        int space = code.indexOf(' ');
        if (space > 0) code = code.substring(0, space);
        return code.isEmpty() ? null : code;
    }

    private static List<Item> cap(List<Item> list, int limit) {
        return list.size() > limit ? List.copyOf(list.subList(0, limit)) : list;
    }
}
