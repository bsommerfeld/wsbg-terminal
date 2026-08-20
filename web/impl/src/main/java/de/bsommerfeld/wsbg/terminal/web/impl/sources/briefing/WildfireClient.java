package de.bsommerfeld.wsbg.terminal.web.impl.sources.briefing;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * NASA FIRMS active fires (researched + live-verified 2026-07-17, keyless):
 * the flat 24-hour global VIIRS CSV under {@code firms.modaps.eosdis.nasa.gov/
 * data/active_fire/...} — satellite fire detections with position and fire
 * radiative power, refreshed roughly hourly. (The FIRMS /api/area/ API needs
 * a key — this flat file does not.)
 *
 * <p>The raw file runs to tens of thousands of detections; the client
 * aggregates them into 1° clusters (count + strongest radiative power) — a
 * few hundred drawable fire fields instead of a point storm, kept to a drawable size. The heavy download hides behind a
 * {@value #CACHE_TTL_HOURS}-hour cache.
 */
@Singleton
public class WildfireClient {

    private static final Logger LOG = LoggerFactory.getLogger(WildfireClient.class);

    private static final String URL = "https://firms.modaps.eosdis.nasa.gov/data/active_fire/"
            + "suomi-npp-viirs-c2/csv/SUOMI_VIIRS_C2_Global_24h.csv";
    private static final long CACHE_TTL_HOURS = 3;
    /** Clusters below this detection count are farm-scale noise. */
    private static final int MIN_DETECTIONS = 3;
    private static final int MAX_CLUSTERS = 700;

    /** One 1°-cell fire cluster: centre + detections + strongest FRP (MW). */
    public record FireCluster(double lat, double lon, int detections, double maxFrp) {
    }

    private record Cached(Instant at, List<FireCluster> clusters) {
    }

    /** Transport order of the old {@code @DirectFirst} seam: direct first, browser joker as fallback. */
    private static final FetchUtil[] MODES = {FetchUtil.DIRECT, FetchUtil.BROWSER};

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(45);
    private volatile Cached cached;

    /** Production: the shared house fetcher; {@code MODES} carries the old {@code @DirectFirst} order. */
    @Inject
    public WildfireClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The current fire clusters; stale kept on failure. */
    public List<FireCluster> clusters() {
        Cached hit = cached;
        if (hit != null && hit.at().isAfter(Instant.now().minus(Duration.ofHours(CACHE_TTL_HOURS)))) {
            return hit.clusters();
        }
        try {
            WebResponse resp = fetcher.fetch(URL,
                    Map.of("Accept", "text/csv"), requestTimeout, MODES);
            if (resp != null && resp.status() == 200) {
                List<FireCluster> parsed = parse(resp.body());
                if (!parsed.isEmpty()) {
                    cached = new Cached(Instant.now(), parsed);
                    return parsed;
                }
            } else {
                LOG.debug("[FIRMS] answered status {}", resp == null ? "null" : resp.status());
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[FIRMS] fetch failed: {}", e.getMessage());
        }
        return hit == null ? List.of() : hit.clusters();
    }

    /** Package-visible for the fixture test. */
    List<FireCluster> parse(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        String[] lines = csv.split("\n");
        int latCol = -1;
        int lonCol = -1;
        int frpCol = -1;
        String[] header = lines[0].split(",");
        for (int i = 0; i < header.length; i++) {
            switch (header[i].trim()) {
                case "latitude" -> latCol = i;
                case "longitude" -> lonCol = i;
                case "frp" -> frpCol = i;
                default -> { }
            }
        }
        if (latCol < 0 || lonCol < 0) return List.of();
        // 1° binning: key = packed cell, value = {count, maxFrp, latSum, lonSum}.
        Map<Integer, double[]> bins = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            String[] cols = lines[i].split(",");
            if (cols.length <= Math.max(latCol, lonCol)) continue;
            try {
                double lat = Double.parseDouble(cols[latCol]);
                double lon = Double.parseDouble(cols[lonCol]);
                double frp = frpCol >= 0 && cols.length > frpCol && !cols[frpCol].isBlank()
                        ? Double.parseDouble(cols[frpCol]) : 0;
                int key = ((int) Math.floor(lat) + 90) * 360 + ((int) Math.floor(lon) + 180);
                double[] bin = bins.computeIfAbsent(key, k -> new double[4]);
                bin[0]++;
                bin[1] = Math.max(bin[1], frp);
                bin[2] += lat;
                bin[3] += lon;
            } catch (NumberFormatException ignored) {
                // malformed row — skip
            }
        }
        List<FireCluster> out = new ArrayList<>();
        for (double[] bin : bins.values()) {
            if (bin[0] < MIN_DETECTIONS) continue;
            out.add(new FireCluster(bin[2] / bin[0], bin[3] / bin[0], (int) bin[0], bin[1]));
        }
        out.sort((a, b) -> Integer.compare(b.detections(), a.detections()));
        return out.size() > MAX_CLUSTERS ? List.copyOf(out.subList(0, MAX_CLUSTERS)) : List.copyOf(out);
    }
}
