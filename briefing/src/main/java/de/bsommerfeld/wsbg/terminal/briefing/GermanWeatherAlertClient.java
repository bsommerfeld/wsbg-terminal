package de.bsommerfeld.wsbg.terminal.briefing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.source.net.DirectFirst;
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
 * The official German weather warnings (live-probed 2026-08-03, keyless): the
 * DWD's own GeoServer, district layer. Not a forecast and not a private
 * provider's guess - these are the warnings the Deutscher Wetterdienst issues
 * with legal force, the ones that close schools, ground cranes and stop river
 * traffic.
 *
 * <p>Geometry economics: the district layer answers ~880 kB of real polygons,
 * which is far too much to push through a websocket every few minutes. Every
 * GeoJSON feature carries its own {@code bbox}, so each warned district rides
 * as ONE point at the centre of its box - a few kB for the same picture at
 * map scale, where a district is a dot anyway.
 */
@Singleton
public class GermanWeatherAlertClient {

    private static final Logger LOG = LoggerFactory.getLogger(GermanWeatherAlertClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String URL = "https://maps.dwd.de/geoserver/dwd/ows"
            + "?service=WFS&version=2.0.0&request=GetFeature"
            + "&typeName=dwd:Warnungen_Landkreise&outputFormat=application/json";
    /** Warnings are issued in blocks of hours; a quarter-hour cache is plenty. */
    private static final Duration CACHE_TTL = Duration.ofMinutes(15);
    private static final int MAX_ALERTS = 320;

    /**
     * One warned district: {@code event} is the DWD's own wording
     * ("STARKE HITZE", "GEWITTER"), {@code severity} the CAP level
     * (Minor/Moderate/Severe/Extreme).
     */
    public record Alert(String event, String severity, String headline, String area,
            String onsetIso, String expiresIso, double lat, double lon) {
    }

    private record Cached(Instant at, List<Alert> alerts) {
    }

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(45);
    private volatile Cached cached;

    /** Test/default: plain direct transport. */
    public GermanWeatherAlertClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam (joker mandate 2026-07-14). */
    @Inject
    public GermanWeatherAlertClient(@DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The current warnings; stale kept on failure. */
    public List<Alert> alerts() {
        Cached hit = cached;
        if (hit != null && hit.at().isAfter(Instant.now().minus(CACHE_TTL))) {
            return hit.alerts();
        }
        try {
            WebResponse resp = fetcher.fetch(URL,
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) {
                List<Alert> parsed = parse(resp.body());
                // A Germany with no warning at all is a VALID answer (a calm
                // day) and gets cached like any other picture.
                cached = new Cached(Instant.now(), parsed);
                return parsed;
            }
            LOG.debug("[DWD] answered status {}", resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[DWD] fetch failed: {}", e.getMessage());
        }
        return hit == null ? List.of() : hit.alerts();
    }

    /** Package-visible for the fixture test. */
    List<Alert> parse(String body) {
        try {
            JsonNode features = JSON.readTree(body).path("features");
            if (!features.isArray()) return List.of();
            List<Alert> out = new ArrayList<>();
            for (JsonNode f : features) {
                double[] centre = centreOf(f);
                if (centre == null) continue;
                JsonNode p = f.path("properties");
                out.add(new Alert(
                        text(p, "EVENT"), text(p, "SEVERITY"), text(p, "HEADLINE"),
                        text(p, "AREADESC"), text(p, "ONSET"), text(p, "EXPIRES"),
                        centre[0], centre[1]));
                if (out.size() >= MAX_ALERTS) break;
            }
            return List.copyOf(out);
        } catch (Exception e) {
            LOG.debug("[DWD] parse failed: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * The centre of the feature's own bounding box, as [lat, lon]. Falling
     * back to the polygon would mean carrying the polygon - the whole point of
     * reading the box is that we never have to.
     */
    private static double[] centreOf(JsonNode feature) {
        JsonNode box = feature.path("bbox");
        if (!box.isArray() || box.size() < 4) return null;
        for (int i = 0; i < 4; i++) {
            if (!box.get(i).isNumber()) return null;
        }
        double minLon = box.get(0).asDouble();
        double minLat = box.get(1).asDouble();
        double maxLon = box.get(2).asDouble();
        double maxLat = box.get(3).asDouble();
        return new double[]{(minLat + maxLat) / 2, (minLon + maxLon) / 2};
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isTextual() ? v.asText() : null;
    }
}
