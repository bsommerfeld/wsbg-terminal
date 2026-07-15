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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * IMF PortWatch daily chokepoint transit counts (live-probed 2026-07-14):
 * keyless ArcGIS FeatureServer JSON over the {@code Daily_Chokepoints_Data}
 * service — 28 chokepoints per day (Suez, Panama, Hormuz, Bab el-Mandeb,
 * Taiwan, Kerch, Malacca, Gibraltar, …) with vessel counts by class and the
 * day's aggregate carrying capacity. Row shape pinned by probe: {@code date}
 * arrives as a plain {@code "YYYY-MM-DD"} string ({@code esriFieldTypeDateOnly}),
 * counts as integers, {@code portname} as the display name. <b>Freshness is
 * T-2</b>: the newest available date trails today by two days (satellite AIS
 * processing lag) — a consumer must present the data as "as of &lt;date&gt;",
 * never as live traffic. An ArcGIS-side problem answers HTTP 200 with an
 * {@code {"error": …}} body — no {@code features}, parses to empty.
 */
@Singleton
public class PortWatchClient {

    private static final Logger LOG = LoggerFactory.getLogger(PortWatchClient.class);

    private static final String QUERY_BASE =
            "https://services9.arcgis.com/weJ1QsnbMYJlCHdG/arcgis/rest/services/"
                    + "Daily_Chokepoints_Data/FeatureServer/0/query";
    /** The slim field set the briefing reads — keeps the payload small. */
    private static final String OUT_FIELDS = "date,portname,n_total,n_tanker,n_container,n_cargo,capacity";
    /**
     * The latest() window: two full days (2×28) plus slack — the newest date is
     * filtered client-side, so a partially-ingested day can never truncate it.
     */
    private static final int LATEST_WINDOW_ROWS = 60;
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Data advances once a day (T-2) — 6h politeness cache. */
    private static final Duration CACHE_TTL = Duration.ofHours(6);

    /**
     * One chokepoint-day. Counts are vessel TRANSITS that calendar day
     * (n_cargo = dry bulk + general cargo + roro aggregated by the source;
     * n_total = cargo + tanker + container); {@code capacityTons} is the
     * aggregate carrying capacity of the day's transits in metric tons
     * (deadweight). Unknown numeric = -1.
     */
    public record ChokepointDay(String name, LocalDate date, int nTotal, int nTanker,
            int nContainer, int nCargo, long capacityTons) {
    }

    private record Cached(Instant at, List<ChokepointDay> rows) {
    }

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(20);
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /** Test/default: plain direct transport. */
    public PortWatchClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam - browser-first since the 2026-07-14 joker mandate. */
    @Inject
    public PortWatchClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * The newest available day's 28 chokepoints (T-2), name-sorted by the
     * server. Empty on any failure; an outage keeps the stale cache.
     */
    public List<ChokepointDay> latest() {
        String url = QUERY_BASE + "?where=" + enc("1=1")
                + "&orderByFields=" + enc("date DESC,portname")
                + "&resultRecordCount=" + LATEST_WINDOW_ROWS
                + "&outFields=" + enc(OUT_FIELDS) + "&f=json";
        return fetchRows("latest", url, true);
    }

    /**
     * All chokepoints of ONE specific day, name-sorted (single filtered query;
     * empty when that day isn't ingested — remember T-2). Lets a consumer
     * compute week-over-week deltas across the whole board with exactly one
     * extra request instead of 28 per-name history calls.
     */
    public List<ChokepointDay> day(LocalDate date) {
        if (date == null) return List.of();
        String where = "date=DATE '" + date + "'";
        String url = QUERY_BASE + "?where=" + enc(where)
                + "&orderByFields=" + enc("portname")
                + "&outFields=" + enc(OUT_FIELDS) + "&f=json";
        return fetchRows("day:" + date, url, false);
    }

    /**
     * One chokepoint's last {@code days} daily rows (single filtered query),
     * chronological (oldest first). Empty on any failure or unknown name.
     */
    public List<ChokepointDay> history(String chokepoint, int days) {
        if (chokepoint == null || chokepoint.isBlank() || days <= 0) return List.of();
        // ArcGIS SQL string literal: double any single quote.
        String where = "portname='" + chokepoint.replace("'", "''") + "'";
        String url = QUERY_BASE + "?where=" + enc(where)
                + "&orderByFields=" + enc("date DESC")
                + "&resultRecordCount=" + days
                + "&outFields=" + enc(OUT_FIELDS) + "&f=json";
        List<ChokepointDay> newestFirst = fetchRows("history:" + chokepoint + ":" + days, url, false);
        List<ChokepointDay> chronological = new ArrayList<>(newestFirst);
        java.util.Collections.reverse(chronological);
        return List.copyOf(chronological);
    }

    private List<ChokepointDay> fetchRows(String cacheKey, String url, boolean newestDateOnly) {
        Cached hit = cache.get(cacheKey);
        if (hit != null && hit.at().isAfter(Instant.now().minus(CACHE_TTL))) {
            return hit.rows();
        }
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) {
                List<ChokepointDay> rows = parse(resp.body());
                if (newestDateOnly) rows = latestDay(rows);
                if (!rows.isEmpty()) {
                    cache.put(cacheKey, new Cached(Instant.now(), rows));
                    return rows;
                }
            } else {
                LOG.debug("[PortWatch] answered status {}", resp == null ? "null" : resp.status());
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[PortWatch] fetch failed: {}", e.getMessage());
        }
        // An outage keeps the stale rows instead of caching empty.
        return hit != null ? hit.rows() : List.of();
    }

    /** Package-private for tests: ArcGIS query JSON → rows, network-free. */
    static List<ChokepointDay> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        List<ChokepointDay> out = new ArrayList<>();
        try {
            for (JsonNode f : JSON.readTree(json).path("features")) {
                JsonNode a = f.path("attributes");
                String name = a.path("portname").asText("").strip();
                LocalDate date = parseDate(a.get("date"));
                if (name.isEmpty() || date == null) continue;
                out.add(new ChokepointDay(name, date,
                        intOr(a, "n_total"), intOr(a, "n_tanker"),
                        intOr(a, "n_container"), intOr(a, "n_cargo"),
                        longOr(a, "capacity")));
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    /** Package-private for tests: keep only the rows of the newest date present. */
    static List<ChokepointDay> latestDay(List<ChokepointDay> rows) {
        if (rows.isEmpty()) return rows;
        LocalDate newest = rows.get(0).date();
        for (ChokepointDay r : rows) {
            if (r.date().isAfter(newest)) newest = r.date();
        }
        final LocalDate day = newest;
        return rows.stream().filter(r -> day.equals(r.date())).toList();
    }

    /** DateOnly fields arrive as "YYYY-MM-DD"; classic esri date fields as epoch millis. */
    private static LocalDate parseDate(JsonNode n) {
        if (n == null || n.isNull()) return null;
        try {
            if (n.isNumber()) {
                return Instant.ofEpochMilli(n.asLong()).atZone(ZoneOffset.UTC).toLocalDate();
            }
            return LocalDate.parse(n.asText().strip());
        } catch (Exception e) {
            return null;
        }
    }

    private static int intOr(JsonNode a, String field) {
        JsonNode v = a.get(field);
        return v == null || v.isNull() || !v.canConvertToInt() ? -1 : v.asInt();
    }

    private static long longOr(JsonNode a, String field) {
        JsonNode v = a.get(field);
        return v == null || v.isNull() || !v.canConvertToLong() ? -1L : v.asLong();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }
}
