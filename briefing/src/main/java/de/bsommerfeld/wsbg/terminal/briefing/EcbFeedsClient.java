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
 * ECB press RSS + Data Portal SDMX series (live-probed 2026-07-14, keyless).
 *
 * <p>Press: {@code ecb.europa.eu/rss/press.html} — despite the {@code .html}
 * suffix the URL itself answers {@code application/rss+xml} (press releases,
 * speeches, interviews, press conferences; RFC-1123 pubDates with offset).
 *
 * <p>Series: {@code data-api.ecb.europa.eu/service/data/<FLOW>/<KEY>
 * ?lastNObservations=1&format=csvdata} — CSV with quoted commas inside the
 * TITLE columns, column POSITIONS differ per flow (resolved by header name).
 * Pinned keys:
 * <ul>
 *   <li>deposit facility rate — {@code FM/D.U2.EUR.4F.KR.DFR.LEV} (daily, %)</li>
 *   <li>€STR — {@code EST/B.EU000A2X2A25.WT} (business-daily, %, T+1)</li>
 *   <li>HICP euro area y/y — {@code HICP/M.U2.N.000000.4D0.ANR}. The old
 *       {@code ICP} flow was DISCONTINUED 2026-02-04 (last obs 2025-12; its
 *       own COMMENT_OBS announces the cut) — the successor flow is
 *       {@code HICP} with dims FREQ.REF_AREA.ADJUSTMENT.ICP_ITEM.DATA_PROVIDER
 *       .ICP_SUFFIX, provider {@code 4D0} = Eurostat. The newest month
 *       arrives with {@code OBS_STATUS=E} — that IS the flash estimate,
 *       surfaced as {@code estimate=true}.</li>
 * </ul>
 */
@Singleton
public class EcbFeedsClient {

    private static final Logger LOG = LoggerFactory.getLogger(EcbFeedsClient.class);

    private static final String PRESS_URL = "https://www.ecb.europa.eu/rss/press.html";
    private static final String DATA_BASE = "https://data-api.ecb.europa.eu/service/data/";
    static final String DFR_KEY = "FM/D.U2.EUR.4F.KR.DFR.LEV";
    static final String ESTR_KEY = "EST/B.EU000A2X2A25.WT";
    static final String HICP_FLASH_KEY = "HICP/M.U2.N.000000.4D0.ANR";

    private static final Duration PRESS_TTL = Duration.ofMinutes(30);
    private static final Duration SERIES_TTL = Duration.ofHours(6);

    /** One ECB press item (release, speech, interview or press conference). */
    public record PressItem(String title, String link, Instant publishedAt) {
    }

    /**
     * One SDMX observation: {@code isoPeriod} as the portal ships it
     * ("2026-07-14" daily, "2026-06" monthly), value in percent,
     * {@code estimate} true when OBS_STATUS=E (e.g. the HICP flash).
     */
    public record Observation(String isoPeriod, double value, boolean estimate) {
    }

    private record CachedPress(Instant at, List<PressItem> items) {
    }

    private record CachedObs(Instant at, Observation obs) {
    }

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(15);
    private volatile CachedPress pressCache;
    private final Map<String, CachedObs> seriesCache = new ConcurrentHashMap<>();

    /** Test/default: plain direct transport. */
    public EcbFeedsClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam - browser-first since the 2026-07-14 joker mandate. */
    @Inject
    public EcbFeedsClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The ECB's latest press items, feed order (newest first). Empty on any failure. */
    public List<PressItem> press(int limit) {
        CachedPress hit = pressCache;
        if (hit != null && hit.at().isAfter(Instant.now().minus(PRESS_TTL))) {
            return cap(hit.items(), limit);
        }
        String body = get(PRESS_URL, "application/rss+xml, application/xml, text/xml");
        List<PressItem> items = parsePress(body);
        if (!items.isEmpty()) {
            pressCache = new CachedPress(Instant.now(), items);
        } else if (hit != null) {
            return cap(hit.items(), limit); // outage keeps the stale pool
        }
        return cap(items, limit);
    }

    /** The current ECB deposit facility rate, or null when the portal is unreachable. */
    public Observation depositFacilityRate() {
        return series(DFR_KEY);
    }

    /** The latest €STR fixing (T+1), or null when the portal is unreachable. */
    public Observation estr() {
        return series(ESTR_KEY);
    }

    /**
     * The latest euro-area HICP y/y print; {@code estimate=true} marks the
     * Eurostat flash. Null when the portal is unreachable.
     */
    public Observation hicpFlash() {
        return series(HICP_FLASH_KEY);
    }

    private Observation series(String flowAndKey) {
        CachedObs hit = seriesCache.get(flowAndKey);
        if (hit != null && hit.at().isAfter(Instant.now().minus(SERIES_TTL))) {
            return hit.obs();
        }
        String body = get(DATA_BASE + flowAndKey + "?lastNObservations=1&format=csvdata",
                "text/csv, */*");
        Observation obs = parseSeriesCsv(body);
        if (obs != null) {
            seriesCache.put(flowAndKey, new CachedObs(Instant.now(), obs));
            return obs;
        }
        // An outage keeps the stale observation instead of caching null.
        return hit != null ? hit.obs() : null;
    }

    private String get(String url, String accept) {
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", userAgent, "Accept", accept), requestTimeout);
            if (resp != null && resp.status() == 200) return resp.body();
            LOG.debug("[ECB] {} answered status {}", url, resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[ECB] fetch {} failed: {}", url, e.getMessage());
        }
        return null;
    }

    /** Package-private for tests: press feed XML → items, network-free. */
    static List<PressItem> parsePress(String xml) {
        List<PressItem> out = new ArrayList<>();
        for (Rss.Item item : Rss.parse(xml)) {
            out.add(new PressItem(item.title(), item.link(), item.publishedAt()));
        }
        return out;
    }

    /**
     * Package-private for tests: SDMX csvdata → the LAST (latest) observation,
     * network-free. Column positions resolved by header name — they differ per
     * flow; the TITLE columns carry quoted commas, so the split is quote-aware.
     * Null on garbage / walled HTML / empty replies.
     */
    static Observation parseSeriesCsv(String csv) {
        if (csv == null || csv.isBlank() || csv.startsWith("{") || csv.startsWith("<")) return null;
        String[] lines = csv.split("\r?\n");
        if (lines.length < 2) return null;
        List<String> header = splitCsv(lines[0]);
        int timeIdx = header.indexOf("TIME_PERIOD");
        int valueIdx = header.indexOf("OBS_VALUE");
        int statusIdx = header.indexOf("OBS_STATUS");
        if (timeIdx < 0 || valueIdx < 0) return null;
        for (int i = lines.length - 1; i >= 1; i--) {
            if (lines[i].isBlank()) continue;
            List<String> row = splitCsv(lines[i]);
            if (row.size() <= Math.max(timeIdx, valueIdx)) return null;
            String period = row.get(timeIdx).strip();
            double value;
            try {
                value = Double.parseDouble(row.get(valueIdx).strip());
            } catch (NumberFormatException e) {
                return null;
            }
            boolean estimate = statusIdx >= 0 && statusIdx < row.size()
                    && "E".equals(row.get(statusIdx).strip());
            return period.isEmpty() ? null : new Observation(period, value, estimate);
        }
        return null;
    }

    /** Minimal RFC-4180 field split (quotes + embedded commas; no embedded newlines). */
    static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                out.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString());
        return out;
    }

    private static List<PressItem> cap(List<PressItem> list, int limit) {
        return list.size() > limit ? List.copyOf(list.subList(0, limit)) : list;
    }
}
