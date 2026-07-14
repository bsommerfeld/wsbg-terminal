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
 */
@Singleton
public class RhinePegelClient {

    private static final Logger LOG = LoggerFactory.getLogger(RhinePegelClient.class);

    private static final String URL =
            "https://www.pegelonline.wsv.de/webservices/rest-api/v2/stations/KAUB/W/currentmeasurement.json";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One gauge reading: level in cm and the API's own state classification. */
    public record PegelReading(double centimeters, String state) {
    }

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(10);

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
}
