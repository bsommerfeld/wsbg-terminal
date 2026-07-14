package de.bsommerfeld.wsbg.terminal.briefing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tagesschau/ARD as the day's German top-news leg (live-probed 2026-07-13):
 * the OFFICIAL open {@code api2u} — keyless JSON, no wall, no key. Two
 * surfaces: {@code /api2u/homepage} carries the ~10 stories the ARD desk
 * itself ranks as the day's top news (the "Top News des Tages" the report
 * wants), {@code /api2u/news?ressort=wirtschaft} the Wirtschaft ressort
 * (~50 stories). Item shape pinned by probe: {@code title}, {@code topline}
 * (the kicker), {@code firstSentence}, ISO {@code date} WITH offset,
 * {@code ressort} (nullable on homepage items), {@code breakingNews},
 * {@code type} (= "story"; other feeds may carry "video" — filtered).
 *
 * <p>Editorial mode downstream: Tagesschau content is the PRESS's account —
 * the report attributes it, never asserts it as its own observation.
 */
@Singleton
public class TagesschauClient {

    private static final Logger LOG = LoggerFactory.getLogger(TagesschauClient.class);

    private static final String HOMEPAGE = "https://www.tagesschau.de/api2u/homepage";
    private static final String WIRTSCHAFT =
            "https://www.tagesschau.de/api2u/news?ressort=wirtschaft";
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Politeness cache — one collect per day makes this mostly academic. */
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    /** One ARD story: kicker (topline), headline, teaser sentence, publish instant. */
    public record Article(String title, String topline, String firstSentence,
            Instant publishedAt, String ressort, boolean breaking) {
    }

    private record Cached(Instant at, List<Article> articles) {
    }

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(12);
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /** Test/default: plain direct transport. */
    public TagesschauClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam - browser-first since the 2026-07-14 joker mandate. */
    @Inject
    public TagesschauClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The ARD desk's current top stories (homepage ranking), newest-ranked order. */
    public List<Article> topNews(int limit) {
        return cap(fetch(HOMEPAGE), limit);
    }

    /** The Wirtschaft ressort's current stories, feed order (newest first). */
    public List<Article> wirtschaft(int limit) {
        return cap(fetch(WIRTSCHAFT), limit);
    }

    private List<Article> fetch(String url) {
        Cached hit = cache.get(url);
        if (hit != null && hit.at().isAfter(Instant.now().minus(CACHE_TTL))) {
            return hit.articles();
        }
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("Accept", "application/json"), requestTimeout);
            if (resp != null && resp.status() == 200) {
                List<Article> articles = parse(resp.body());
                if (!articles.isEmpty()) {
                    cache.put(url, new Cached(Instant.now(), articles));
                }
                return articles;
            }
            LOG.debug("[Tagesschau] {} answered status {}", url,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[Tagesschau] {} failed: {}", url, e.getMessage());
        }
        // An outage keeps the stale pool instead of caching empty.
        return hit != null ? hit.articles() : List.of();
    }

    /** Package-private for tests: api2u JSON → articles, network-free. */
    static List<Article> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        List<Article> out = new ArrayList<>();
        try {
            for (JsonNode n : JSON.readTree(json).path("news")) {
                if (!"story".equals(n.path("type").asText(""))) continue;
                String title = n.path("title").asText("").strip();
                if (title.isEmpty()) continue;
                out.add(new Article(title,
                        blankToNull(n.path("topline").asText("")),
                        blankToNull(n.path("firstSentence").asText("")),
                        parseDate(n.path("date").asText("")),
                        blankToNull(n.path("ressort").asText("")),
                        n.path("breakingNews").asBoolean(false)));
            }
        } catch (Exception e) {
            LOG.debug("[Tagesschau] parse failed: {}", e.getMessage());
        }
        return out;
    }

    private static Instant parseDate(String iso) {
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static List<Article> cap(List<Article> list, int limit) {
        return list.size() > limit ? List.copyOf(list.subList(0, limit)) : list;
    }
}
