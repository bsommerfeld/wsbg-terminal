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
import java.util.Map;
import java.util.Optional;

/**
 * NOAA CPC Oceanic Niño Index (2026-07-17, keyless plain text):
 * {@code cpc.ncep.noaa.gov/data/indices/oni.ascii.txt} — three-month running
 * Niño-3.4 SST anomalies back to 1950, one row per season. The EL NIÑO gauge
 * the state that moves grain, soft commodities,
 * fertilizer and shipping seasons. Classification follows NOAA's own
 * convention: anomaly ≥ +0.5 °C = El Niño conditions, ≤ −0.5 °C = La Niña,
 * in between = neutral.
 */
@Singleton
public class EnsoClient {

    private static final Logger LOG = LoggerFactory.getLogger(EnsoClient.class);

    private static final String URL =
            "https://www.cpc.ncep.noaa.gov/data/indices/oni.ascii.txt";
    /** Monthly product — one polite fetch a day is plenty. */
    private static final Duration CACHE_TTL = Duration.ofHours(24);

    /** Direct-first (joker mandate 2026-07-14); the browser rescues a walled answer. */
    static final FetchUtil[] MODES = {FetchUtil.DIRECT, FetchUtil.BROWSER};

    /** The newest ONI reading: season label ("MJJ 2026") + anomaly + phase token. */
    public record Oni(String season, double anomaly, String phase) {
    }

    private record Cached(Instant at, Oni oni) {
    }

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(12);
    private volatile Cached cached;

    @Inject
    public EnsoClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The newest reading; empty on any failure (stale kept while it lasts). */
    public Optional<Oni> latest() {
        Cached hit = cached;
        if (hit != null && hit.at().isAfter(Instant.now().minus(CACHE_TTL))) {
            return Optional.of(hit.oni());
        }
        try {
            WebResponse resp = fetcher.fetch(URL,
                    Map.of("Accept", "text/plain"), requestTimeout, MODES);
            if (resp != null && resp.status() == 200) {
                Optional<Oni> parsed = parse(resp.body());
                parsed.ifPresent(o -> cached = new Cached(Instant.now(), o));
                if (parsed.isPresent()) return parsed;
            } else {
                LOG.debug("[ONI] answered status {}", resp == null ? "null" : resp.status());
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[ONI] fetch failed: {}", e.getMessage());
        }
        return hit == null ? Optional.empty() : Optional.of(hit.oni());
    }

    /** Package-visible for the fixture test. Columns: SEAS YR TOTAL ANOM. */
    Optional<Oni> parse(String body) {
        if (body == null) return Optional.empty();
        Oni last = null;
        for (String line : body.split("\n")) {
            String[] cols = line.trim().split("\\s+");
            if (cols.length < 4 || !cols[1].matches("\\d{4}")) continue;
            try {
                double anom = Double.parseDouble(cols[3]);
                last = new Oni(cols[0] + " " + cols[1], anom, phaseOf(anom));
            } catch (NumberFormatException ignored) {
                // header or malformed row — skip
            }
        }
        return Optional.ofNullable(last);
    }

    private static String phaseOf(double anomaly) {
        if (anomaly >= 0.5) return "EL_NINO";
        if (anomaly <= -0.5) return "LA_NINA";
        return "NEUTRAL";
    }
}
