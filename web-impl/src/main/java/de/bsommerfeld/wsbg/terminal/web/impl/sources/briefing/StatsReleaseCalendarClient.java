package de.bsommerfeld.wsbg.terminal.web.impl.sources.briefing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The statistics offices' own release schedules (live-verified 2026-08-03) —
 * the layer that reaches PAST the traders' calendars. This matters because the
 * FTMO gateway publishes barely a week forward (measured), so without a primary
 * source an outlook four weeks out has nothing dated to stand on. These three
 * run months ahead:
 * <ul>
 *   <li><b>Eurostat</b> — {@code ec.europa.eu/eurostat/o/calendars/eventsJson},
 *       the undocumented backend of the release-calendar widget. Filtered to
 *       {@code isEuroindicator=true} it is exactly the market-moving euro-area
 *       set, and each entry names the {@code datasetCodes} the number will
 *       appear under, so schedule and data come from one house.</li>
 *   <li><b>BEA</b> — {@code apps.bea.gov/API/signup/release_dates.json},
 *       keyless (the BEA <i>data</i> API is not), 31 US series with UTC stamps
 *       through year end. Its sibling ICS is served happily but is STALE by a
 *       year — do not be tempted.</li>
 *   <li><b>ONS</b> — {@code api.beta.ons.gov.uk/v1/search/releases}, the
 *       cleanest of the three, and the only one that says out loud when a
 *       release is <b>provisional, postponed or cancelled</b>. A statistic that
 *       slips its date is itself news, so that flag rides along.</li>
 * </ul>
 * Every other ONS path 404s; only {@code /v1/search/releases} answers.
 */
@Singleton
public class StatsReleaseCalendarClient {

    private static final Logger LOG = LoggerFactory.getLogger(StatsReleaseCalendarClient.class);

    private static final String EUROSTAT =
            "https://ec.europa.eu/eurostat/o/calendars/eventsJson"
                    + "?theme=&category=&keywords=&isEuroindicator=true"
                    + "&authorInclude=&authorExclude=&start=";
    private static final String BEA = "https://apps.bea.gov/API/signup/release_dates.json";
    private static final String ONS = "https://api.beta.ons.gov.uk/v1/search/releases"
            + "?release-type=type-upcoming&sort=release_date_asc&limit=";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long CACHE_TTL_MS = 12 * 60 * 60 * 1000L;

    /**
     * One scheduled statistic. {@code office} is the publishing body,
     * {@code period} the reference period where the source names it,
     * {@code datasets} Eurostat's dataset codes (empty elsewhere).
     * {@code slipped} means the office itself flagged the date as postponed or
     * cancelled — the release NOT happening is the news.
     */
    public record Release(LocalDate date, String office, String title, String period,
            String datasets, boolean provisional, boolean slipped) {
    }

    /** Transport order of the old {@code @DirectFirst} seam: direct first, browser joker as fallback. */
    private static final FetchUtil[] MODES = {FetchUtil.DIRECT, FetchUtil.BROWSER};

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(15);

    private volatile List<Release> cache = List.of();
    private volatile long cachedAtMs;

    /** Production: the shared house fetcher; {@code MODES} carries the old {@code @DirectFirst} order. */
    @Inject
    public StatsReleaseCalendarClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * Scheduled releases from {@code from} to {@code to}, chronological, all
     * three offices merged. Cached half a day. One office failing costs only
     * its own leg.
     */
    public List<Release> upcoming(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) return List.of();
        long now = System.currentTimeMillis();
        if (!cache.isEmpty() && now - cachedAtMs < CACHE_TTL_MS) return window(cache, from, to);

        List<Release> all = new ArrayList<>();
        all.addAll(parseEurostat(get(EUROSTAT + from + "T00:00:00Z&end=" + to + "T00:00:00Z")));
        all.addAll(parseBea(get(BEA)));
        all.addAll(parseOns(get(ONS + 100)));
        all.sort(java.util.Comparator.comparing(Release::date));
        if (!all.isEmpty()) {
            cache = List.copyOf(all);
            cachedAtMs = now;
        }
        return window(all, from, to);
    }

    private static List<Release> window(List<Release> all, LocalDate from, LocalDate to) {
        List<Release> out = new ArrayList<>();
        for (Release r : all) {
            if (r.date().isBefore(from) || r.date().isAfter(to)) continue;
            out.add(r);
        }
        return out;
    }

    private String get(String url) {
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("Accept", "application/json"),
                    requestTimeout, MODES);
            if (resp != null && resp.status() == 200) return resp.body();
            LOG.debug("[Statistiktermine] {} answered status {}", url,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[Statistiktermine] fetch failed: {}", e.getMessage());
        }
        return null;
    }

    /** Package-private for tests: Eurostat's widget JSON → releases. */
    static List<Release> parseEurostat(String body) {
        if (body == null || body.isBlank()) return List.of();
        List<Release> out = new ArrayList<>();
        try {
            JsonNode root = JSON.readTree(body);
            if (!root.isArray()) return List.of();
            for (JsonNode n : root) {
                String title = text(n, "title");
                LocalDate date = dateOf(text(n, "start"));
                if (title.isEmpty() || date == null) continue;
                out.add(new Release(date, "Eurostat", title, text(n, "period"),
                        text(n, "datasetCodes"), false, false));
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    /**
     * Package-private for tests: BEA's schedule → releases. The file is one
     * object per series with EVERY date of the year, past ones included — the
     * caller's window does the filtering.
     */
    static List<Release> parseBea(String body) {
        if (body == null || body.isBlank()) return List.of();
        List<Release> out = new ArrayList<>();
        try {
            JsonNode root = JSON.readTree(body);
            if (!root.isObject()) return List.of();
            for (Iterator<Map.Entry<String, JsonNode>> it = root.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> series = it.next();
                for (JsonNode stamp : series.getValue().path("release_dates")) {
                    LocalDate date = dateOf(stamp.asText(""));
                    if (date != null) {
                        out.add(new Release(date, "BEA", series.getKey(), "", "", false, false));
                    }
                }
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    /** Package-private for tests: ONS releases → releases, slip flags kept. */
    static List<Release> parseOns(String body) {
        if (body == null || body.isBlank()) return List.of();
        List<Release> out = new ArrayList<>();
        try {
            for (JsonNode n : JSON.readTree(body).path("releases")) {
                JsonNode d = n.path("description");
                String title = d.path("title").asText("").trim();
                LocalDate date = dateOf(d.path("release_date").asText(""));
                if (title.isEmpty() || date == null) continue;
                boolean slipped = d.path("cancelled").asBoolean(false)
                        || d.path("postponed").asBoolean(false);
                out.add(new Release(date, "ONS", title, "", "",
                        !d.path("finalised").asBoolean(true), slipped));
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    /** ISO stamps arrive with an offset, a Z, or millis — all of them are one day. */
    private static LocalDate dateOf(String stamp) {
        if (stamp == null || stamp.isBlank()) return null;
        try {
            return OffsetDateTime.parse(stamp).atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
        } catch (Exception e) {
            try {
                return LocalDate.parse(stamp.substring(0, 10));
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "" : v.asText("").trim();
    }
}
