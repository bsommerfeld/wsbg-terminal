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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Rhine water level at Kaub via PEGELONLINE, the federal waterways' own REST
 * API (live-verified 2026-07-13, keyless): {@code {"value": 54.0,
 * "stateMnwMhw": "low"}}. Kaub is THE chokepoint gauge for Rhine freight —
 * low water means barges load half and BASF/Thyssen/the chemistry aisle pay
 * low-water surcharges. A commodity-adjacent German story no retail terminal
 * carries; the API even classifies the state itself ({@code low}/{@code
 * normal}/{@code high}), so no own thresholds needed.
 *
 * <p>Fischernetz extension (live-probed 2026-07-14): the same API carries the
 * FULL German gauge net (~786 stations, {@code stations.json?includeTimeseries
 * =true}) — per station the available series ({@code W} level cm, {@code Q}
 * discharge m³/s on ~92 stations, {@code WT} water temperature °C on ~139) —
 * plus per-series current measurements ({@code {"timestamp":
 * "…+02:00","value": 620.0}}). A station without the requested series answers
 * HTTP 404 ({@code "Current measurement does not exist."}) = a clean miss,
 * not an outage. The Kaub W surface stays untouched (the Abendausgabe
 * consumes it).
 */
@Singleton
public class RhinePegelClient {

    private static final Logger LOG = LoggerFactory.getLogger(RhinePegelClient.class);

    private static final String BASE = "https://www.pegelonline.wsv.de/webservices/rest-api/v2";
    private static final String URL = BASE + "/stations/KAUB/W/currentmeasurement.json";
    private static final String STATIONS_URL = BASE + "/stations.json?includeTimeseries=true";
    private static final ObjectMapper JSON = new ObjectMapper();
    /** The gauge net itself is static — 12h cache on the station list. */
    private static final Duration STATIONS_CACHE_TTL = Duration.ofHours(12);

    /** One gauge reading: level in cm and the API's own state classification. */
    public record PegelReading(double centimeters, String state) {
    }

    /**
     * One current series measurement: measurement instant and raw value in
     * the series' unit ({@code Q} = m³/s, {@code WT} = °C, {@code W} = cm).
     */
    public record Measurement(Instant timestamp, double value) {
    }

    /** One series a station carries: PEGELONLINE shortname + its unit. */
    public record StationSeries(String shortname, String unit) {
    }

    /**
     * One gauge station: PEGELONLINE shortname (the address for the
     * per-series surfaces), display longname, water body, river km,
     * WGS84 coordinates (NaN where absent), and the carried series.
     */
    public record Station(String shortname, String longname, String water, double km,
            double latitude, double longitude, List<StationSeries> series) {
    }

    private record CachedStations(Instant at, List<Station> stations) {
    }

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(10);
    /** The full-net station list is ~530 KB — its own, longer timeout. */
    private final Duration stationsTimeout = Duration.ofSeconds(25);
    private volatile CachedStations cachedStations;

    /** Test/default: plain direct transport. */
    public RhinePegelClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam - browser-first since the 2026-07-14 joker mandate. */
    @Inject
    public RhinePegelClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The current Kaub reading, or empty on any failure. */
    public Optional<PegelReading> kaub() {
        try {
            WebResponse resp = fetcher.fetch(URL,
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) return parse(resp.body());
            LOG.debug("[Pegel] answered status {}", resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[Pegel] fetch failed: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * The station's current discharge {@code Q} in m³/s; empty when the
     * station doesn't carry the series (404 = clean miss) or on failure.
     */
    public Optional<Measurement> discharge(String station) {
        return currentMeasurement(station, "Q");
    }

    /**
     * The station's current water temperature {@code WT} in °C; empty when
     * the station doesn't carry the series (404 = clean miss) or on failure.
     */
    public Optional<Measurement> waterTemperature(String station) {
        return currentMeasurement(station, "WT");
    }

    /**
     * The full German gauge net with each station's available series (~786
     * stations), so any consumer can address any gauge. Cached 12h; empty on
     * failure (an outage keeps the stale list).
     */
    public List<Station> stations() {
        CachedStations hit = cachedStations;
        if (hit != null && hit.at().isAfter(Instant.now().minus(STATIONS_CACHE_TTL))) {
            return hit.stations();
        }
        try {
            WebResponse resp = fetcher.fetch(STATIONS_URL,
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    stationsTimeout);
            if (resp != null && resp.status() == 200) {
                List<Station> stations = parseStations(resp.body());
                if (!stations.isEmpty()) {
                    cachedStations = new CachedStations(Instant.now(), stations);
                    return stations;
                }
            } else {
                LOG.debug("[Pegel] stations answered status {}",
                        resp == null ? "null" : resp.status());
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[Pegel] stations fetch failed: {}", e.getMessage());
        }
        return hit != null ? hit.stations() : List.of();
    }

    private Optional<Measurement> currentMeasurement(String station, String series) {
        if (station == null || station.isBlank()) return Optional.empty();
        // Station shortnames carry spaces ("PASSAU DONAU") — path-encode them.
        String url = BASE + "/stations/"
                + URLEncoder.encode(station, StandardCharsets.UTF_8).replace("+", "%20")
                + "/" + series + "/currentmeasurement.json";
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) return parseMeasurement(resp.body());
            // 404 = the station has no such series — a miss, not an outage.
            LOG.debug("[Pegel] {}/{} answered status {}", station, series,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[Pegel] {}/{} fetch failed: {}", station, series, e.getMessage());
        }
        return Optional.empty();
    }

    /** Package-private for tests. */
    static Optional<PegelReading> parse(String body) {
        if (body == null || body.isBlank()) return Optional.empty();
        try {
            JsonNode n = JSON.readTree(body);
            double value = n.path("value").asDouble(Double.NaN);
            if (Double.isNaN(value)) return Optional.empty();
            return Optional.of(new PegelReading(value, n.path("stateMnwMhw").asText("")));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Package-private for tests: {@code {"timestamp": "…+02:00", "value": …}}
     * → measurement, network-free. Timestamps arrive with offset.
     */
    static Optional<Measurement> parseMeasurement(String body) {
        if (body == null || body.isBlank()) return Optional.empty();
        try {
            JsonNode n = JSON.readTree(body);
            double value = n.path("value").asDouble(Double.NaN);
            if (Double.isNaN(value)) return Optional.empty();
            Instant at;
            try {
                at = OffsetDateTime.parse(n.path("timestamp").asText("")).toInstant();
            } catch (Exception e) {
                at = null;
            }
            return Optional.of(new Measurement(at, value));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Package-private for tests: stations array JSON → stations, network-free. */
    static List<Station> parseStations(String body) {
        if (body == null || body.isBlank()) return List.of();
        List<Station> out = new ArrayList<>();
        try {
            JsonNode root = JSON.readTree(body);
            if (!root.isArray()) return List.of();
            for (JsonNode s : root) {
                String shortname = s.path("shortname").asText("").strip();
                if (shortname.isEmpty()) continue;
                List<StationSeries> series = new ArrayList<>();
                for (JsonNode t : s.path("timeseries")) {
                    String sn = t.path("shortname").asText("").strip();
                    if (!sn.isEmpty()) series.add(new StationSeries(sn, t.path("unit").asText("")));
                }
                out.add(new Station(shortname,
                        s.path("longname").asText(""),
                        s.path("water").path("shortname").asText(""),
                        s.path("km").asDouble(Double.NaN),
                        s.path("latitude").asDouble(Double.NaN),
                        s.path("longitude").asDouble(Double.NaN),
                        List.copyOf(series)));
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }
}
