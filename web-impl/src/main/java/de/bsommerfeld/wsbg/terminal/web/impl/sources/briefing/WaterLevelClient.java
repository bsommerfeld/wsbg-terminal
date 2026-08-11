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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PEGELONLINE - the German waterways authority's own gauge network
 * (live-probed 2026-08-03, keyless): 786 stations on the Rhine, Elbe, Danube,
 * Weser, Mosel, the Kiel Canal and the coasts, each with its current reading
 * AND the authority's own classification of that reading against the station's
 * long-term marks.
 *
 * <p>The house already watches Kaub because low Rhine water prices chemicals,
 * steel and fuel. This is that same fact for the whole country: when 150
 * gauges read "low" at once, the Rhine is not having a bad day, the German
 * industrial supply chain is.
 *
 * <p>Only stations OUT of their normal band ride. A gauge sitting where it
 * belongs is not news, and 786 normal dots would bury the ones that matter.
 */
@Singleton
public class WaterLevelClient {

    private static final Logger LOG = LoggerFactory.getLogger(WaterLevelClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String URL = "https://www.pegelonline.wsv.de/webservices/rest-api/v2/"
            + "stations.json?includeTimeseries=true&includeCurrentMeasurement=true";
    /** Gauges are read every quarter hour; the picture moves in hours. */
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);
    private static final int MAX_GAUGES = 260;
    /** The water-level series; the network also carries flow, temperature and wind. */
    private static final String LEVEL_SERIES = "W";
    /** Everything else ("unknown", "out-dated", "commented") is not a verdict. */
    private static final Set<String> OUT_OF_BAND = Set.of("low", "high");

    /**
     * One gauge out of its normal band: {@code state} is the authority's own
     * word (low/high) against the station's mean-low/mean-high marks,
     * {@code centimetres} the current reading in the series' own unit.
     */
    public record Gauge(String name, String water, String state, double centimetres,
            String atIso, double lat, double lon) {
    }

    private record Cached(Instant at, List<Gauge> gauges) {
    }

    /** Transport order of the old {@code @DirectFirst} seam: direct first, browser joker as fallback. */
    private static final FetchUtil[] MODES = {FetchUtil.DIRECT, FetchUtil.BROWSER};

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(30);
    private volatile Cached cached;

    /** Production: the shared house fetcher; {@code MODES} carries the old {@code @DirectFirst} order. */
    @Inject
    public WaterLevelClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The gauges out of band; stale kept on failure. */
    public List<Gauge> gauges() {
        Cached hit = cached;
        if (hit != null && hit.at().isAfter(Instant.now().minus(CACHE_TTL))) {
            return hit.gauges();
        }
        try {
            WebResponse resp = fetcher.fetch(URL,
                    Map.of("Accept", "application/json"),
                    requestTimeout, MODES);
            if (resp != null && resp.status() == 200) {
                List<Gauge> parsed = parse(resp.body());
                // A country whose rivers all sit normal is a VALID picture.
                cached = new Cached(Instant.now(), parsed);
                return parsed;
            }
            LOG.debug("[PEGEL] answered status {}", resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[PEGEL] fetch failed: {}", e.getMessage());
        }
        return hit == null ? List.of() : hit.gauges();
    }

    /** Package-visible for the fixture test. */
    List<Gauge> parse(String body) {
        try {
            JsonNode stations = JSON.readTree(body);
            if (!stations.isArray()) return List.of();
            List<Gauge> out = new ArrayList<>();
            for (JsonNode s : stations) {
                if (!s.path("latitude").isNumber() || !s.path("longitude").isNumber()) continue;
                JsonNode level = levelSeries(s.path("timeseries"));
                if (level == null) continue;
                JsonNode now = level.path("currentMeasurement");
                if (!now.path("value").isNumber()) continue;
                // The two scales answer different questions (mean marks vs
                // navigation/flood marks); either one out of band is a story.
                String state = verdict(now.path("stateMnwMhw").asText(null),
                        now.path("stateNswHsw").asText(null));
                if (state == null) continue;
                out.add(new Gauge(
                        s.path("longname").asText(s.path("shortname").asText("")),
                        s.path("water").path("shortname").asText(null),
                        state, now.path("value").asDouble(),
                        now.path("timestamp").asText(null),
                        s.path("latitude").asDouble(), s.path("longitude").asDouble()));
                if (out.size() >= MAX_GAUGES) break;
            }
            return List.copyOf(out);
        } catch (Exception e) {
            LOG.debug("[PEGEL] parse failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static JsonNode levelSeries(JsonNode series) {
        if (!series.isArray()) return null;
        for (JsonNode s : series) {
            if (LEVEL_SERIES.equals(s.path("shortname").asText())) return s;
        }
        return null;
    }

    /** "high" outranks "low": if either scale cries flood, that is the headline. */
    private static String verdict(String meanScale, String navigationScale) {
        String a = meanScale == null ? "" : meanScale;
        String b = navigationScale == null ? "" : navigationScale;
        if ("high".equals(a) || "high".equals(b)) return "high";
        if (OUT_OF_BAND.contains(a)) return a;
        if (OUT_OF_BAND.contains(b)) return b;
        return null;
    }
}
