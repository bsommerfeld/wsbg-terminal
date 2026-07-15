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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * DIVI Intensivregister — the ICU-occupancy leg (keyless JSON, live-probed
 * 2026-07-14): {@code intensivregister.de/api/public/reporting/laendertabelle}
 * returns all 16 states' current intensive-care load. Per state
 * ({@code bundesland_name}): beds occupied ({@code intensivbetten_belegt}),
 * free ({@code intensivbetten_frei}), total ({@code intensivbetten_gesamt}),
 * current COVID cases ({@code faelle_covid_aktuell}) and the number of
 * reporting sites ({@code anzahl_standorte}).
 *
 * <p>Slow-moving: cache 6h. Best-effort empty on any failure. Missing counts
 * follow the core.price convention ({@code -1}).
 */
@Singleton
public class DiviClient {

    private static final Logger LOG = LoggerFactory.getLogger(DiviClient.class);

    private static final String URL =
            "https://www.intensivregister.de/api/public/reporting/laendertabelle";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration CACHE_TTL = Duration.ofHours(6);

    /**
     * One state's ICU load. {@code occupancyPercent} is house-computed from
     * occupied/total (NaN when total is unknown/zero). Missing counts are -1.
     */
    public record StateIcu(String state, int occupied, int free, int total,
            int covidCases, int sites) {

        public double occupancyPercent() {
            return total > 0 ? occupied * 100.0 / total : Double.NaN;
        }
    }

    private record Cached(Instant at, List<StateIcu> states) {
    }

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(12);
    private volatile Cached cache;

    /** Test/default: plain direct transport. */
    public DiviClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam — the DIVI API is wall-less. */
    @Inject
    public DiviClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** All 16 states' current ICU occupancy, feed order. */
    public synchronized List<StateIcu> states() {
        Cached hit = cache;
        if (hit != null && hit.at().isAfter(Instant.now().minus(CACHE_TTL))) {
            return hit.states();
        }
        try {
            WebResponse resp = fetcher.fetch(URL,
                    Map.of("Accept", "application/json"), requestTimeout);
            if (resp != null && resp.status() == 200) {
                List<StateIcu> states = parse(resp.body());
                if (!states.isEmpty()) {
                    cache = new Cached(Instant.now(), states);
                    return states;
                }
            } else {
                LOG.debug("[DIVI] answered status {}", resp == null ? "null" : resp.status());
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[DIVI] failed: {}", e.getMessage());
        }
        return hit != null ? hit.states() : List.of();
    }

    /** Package-private for tests: laendertabelle JSON → per-state ICU rows, network-free. */
    static List<StateIcu> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        List<StateIcu> out = new ArrayList<>();
        try {
            for (JsonNode n : JSON.readTree(json).path("data")) {
                String state = n.path("bundesland_name").asText("").strip();
                if (state.isEmpty()) continue;
                out.add(new StateIcu(state,
                        n.path("intensivbetten_belegt").asInt(-1),
                        n.path("intensivbetten_frei").asInt(-1),
                        n.path("intensivbetten_gesamt").asInt(-1),
                        n.path("faelle_covid_aktuell").asInt(-1),
                        n.path("anzahl_standorte").asInt(-1)));
            }
        } catch (Exception e) {
            LOG.debug("[DIVI] parse failed: {}", e.getMessage());
        }
        return out;
    }
}
