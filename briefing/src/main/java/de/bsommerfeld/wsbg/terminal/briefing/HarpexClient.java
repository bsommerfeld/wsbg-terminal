package de.bsommerfeld.wsbg.terminal.briefing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Harpex container-ship charter rate index from Harper Petersen's own page
 * (live-probed 2026-07-14): {@code harperpetersen.com/harpex} embeds the full
 * weekly series as HTML-escaped JSON in {@code data-json} attributes (one per
 * chart period — 6/12/24 months; the client parses all and keeps the longest).
 * Series shape pinned by fixture: {@code {"harpex":[{"date":
 * "2026-07-10T00:00:00","value":2337.11},…]}} — weekly Friday points, value in
 * dimensionless index points (Harpex base). The page root 307s
 * (TYPO3) — the transport follows. The shipping-cycle gauge for the DD's
 * logistics sector gate.
 */
@Singleton
public class HarpexClient {

    private static final Logger LOG = LoggerFactory.getLogger(HarpexClient.class);

    private static final String URL = "https://www.harperpetersen.com/harpex";
    private static final Pattern DATA_JSON = Pattern.compile("data-json=\"([^\"]*)\"");
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Weekly series — 12h politeness cache. */
    private static final Duration CACHE_TTL = Duration.ofHours(12);

    /** One weekly index fix: date (a Friday) and the index level in points. */
    public record HarpexPoint(LocalDate date, double value) {
    }

    private record Cached(Instant at, List<HarpexPoint> series) {
    }

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(20);
    private volatile Cached cached;

    /** Test/default: plain direct transport. */
    public HarpexClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam - browser-first since the 2026-07-14 joker mandate. */
    @Inject
    public HarpexClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * The latest {@code n} weekly points, chronological (oldest first, newest
     * last). Empty on any failure; an outage keeps the stale series.
     */
    public List<HarpexPoint> latest(int n) {
        if (n <= 0) return List.of();
        List<HarpexPoint> series = series();
        return series.size() > n
                ? List.copyOf(series.subList(series.size() - n, series.size()))
                : series;
    }

    private List<HarpexPoint> series() {
        Cached hit = cached;
        if (hit != null && hit.at().isAfter(Instant.now().minus(CACHE_TTL))) {
            return hit.series();
        }
        try {
            WebResponse resp = fetcher.fetch(URL,
                    Map.of("User-Agent", userAgent, "Accept", "text/html"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) {
                List<HarpexPoint> series = parseSeries(resp.body());
                if (!series.isEmpty()) {
                    cached = new Cached(Instant.now(), series);
                    return series;
                }
            } else {
                LOG.debug("[Harpex] answered status {}", resp == null ? "null" : resp.status());
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[Harpex] fetch failed: {}", e.getMessage());
        }
        return hit != null ? hit.series() : List.of();
    }

    /**
     * Package-private for tests: page HTML → the longest embedded series,
     * date-sorted ascending, network-free.
     */
    static List<HarpexPoint> parseSeries(String html) {
        if (html == null || html.isBlank()) return List.of();
        List<HarpexPoint> best = List.of();
        Matcher m = DATA_JSON.matcher(html);
        while (m.find()) {
            List<HarpexPoint> series = parseJson(unescapeHtml(m.group(1)));
            if (series.size() > best.size()) best = series;
        }
        return best;
    }

    private static List<HarpexPoint> parseJson(String json) {
        List<HarpexPoint> out = new ArrayList<>();
        try {
            for (JsonNode p : JSON.readTree(json).path("harpex")) {
                LocalDate date = parseDate(p.path("date").asText(""));
                double value = p.path("value").asDouble(Double.NaN);
                if (date == null || Double.isNaN(value)) continue;
                out.add(new HarpexPoint(date, value));
            }
        } catch (Exception e) {
            return List.of();
        }
        out.sort(Comparator.comparing(HarpexPoint::date));
        return out;
    }

    /** Dates arrive as zone-less "2026-07-10T00:00:00". */
    private static LocalDate parseDate(String s) {
        try {
            return LocalDateTime.parse(s).toLocalDate();
        } catch (Exception e) {
            return null;
        }
    }

    /** The attribute's entity set is tiny ({@code &quot;} dominates); ampersand last. */
    static String unescapeHtml(String s) {
        return s.replace("&quot;", "\"").replace("&#34;", "\"").replace("&#039;", "'")
                .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&amp;", "&");
    }
}
