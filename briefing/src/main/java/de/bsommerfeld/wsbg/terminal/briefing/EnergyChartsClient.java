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
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * German power market via Fraunhofer ISE's Energy-Charts API (live-probed
 * 2026-07-14, keyless, CC BY 4.0 / SMARD data): {@code /price?bzn=DE-LU} is
 * the day-ahead auction price in 15-minute slots ({@code {"unix_seconds":
 * […],"price":[…],"unit":"EUR / MWh"}}), {@code /public_power?country=de} the
 * live generation mix ({@code production_types[].name/data} aligned to
 * {@code unix_seconds}, MW; the trailing slots of Load/share series are null
 * until published). The price answers "Strompreis-Spike/Dunkelflaute" for the
 * evening report; the mix carries its own precomputed
 * "Renewable share of generation" percentage.
 */
@Singleton
public class EnergyChartsClient {

    private static final Logger LOG = LoggerFactory.getLogger(EnergyChartsClient.class);

    private static final String PRICE_URL = "https://api.energy-charts.info/price?bzn=DE-LU";
    private static final String POWER_URL = "https://api.energy-charts.info/public_power?country=de";
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Intraday data — 30 min politeness cache. */
    private static final Duration CACHE_TTL = Duration.ofMinutes(30);
    /** Aggregate/bookkeeping series that must never win "top source". */
    private static final Set<String> NON_SOURCES = Set.of(
            "Load", "Residual load", "Renewable share of load",
            "Renewable share of generation", "Cross border electricity trading",
            "Hydro pumped storage consumption");

    /**
     * Today's day-ahead price statistics, all in EUR/MWh. {@code current} is
     * the slot containing "now" (the last slot once the day ran out);
     * min/max/avg span the whole served day. NaN where the series was empty.
     */
    public record PriceStats(double currentEurMwh, double minEurMwh, double maxEurMwh,
            double avgEurMwh) {
    }

    /**
     * The current generation mix: renewables share of generation in percent
     * (the API's own aggregate), the single largest source right now with its
     * output in MW, and total load in MW (NaN when not yet published).
     */
    public record PowerMix(double renewableSharePercent, String topSource,
            double topSourceMw, double loadMw) {
    }

    private record Cached(Instant at, Object value) {
    }

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(15);
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /** Test/default: plain direct transport. */
    public EnergyChartsClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam - browser-first since the 2026-07-14 joker mandate. */
    @Inject
    public EnergyChartsClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** Today's DE-LU day-ahead price stats; empty on any failure. */
    public Optional<PriceStats> priceToday() {
        return fetchCached(PRICE_URL, body ->
                parsePrice(body, Instant.now().getEpochSecond()).orElse(null))
                .map(PriceStats.class::cast);
    }

    /**
     * Today's full day-ahead price series (EUR/MWh, quarter-hour slots,
     * chronological, nulls skipped) — the intraday curve figure's input.
     * Empty on any failure; rides the same politeness cache as the stats.
     */
    public java.util.List<Double> priceSeries() {
        return fetchCached(PRICE_URL + "#series", body -> {
            java.util.List<Double> series = parsePriceSeries(body);
            return series.isEmpty() ? null : series;
        }).map(v -> {
            @SuppressWarnings("unchecked")
            java.util.List<Double> series = (java.util.List<Double>) v;
            return series;
        }).orElse(java.util.List.of());
    }

    /** Package-private for tests: price JSON → chronological series, network-free. */
    static java.util.List<Double> parsePriceSeries(String body) {
        if (body == null || body.isBlank()) return java.util.List.of();
        try {
            JsonNode prices = JSON.readTree(body).path("price");
            if (!prices.isArray() || prices.isEmpty()) return java.util.List.of();
            java.util.List<Double> out = new java.util.ArrayList<>();
            for (JsonNode p : prices) {
                if (p != null && p.isNumber()) out.add(p.asDouble());
            }
            return out;
        } catch (Exception e) {
            return java.util.List.of();
        }
    }

    /** The current German generation mix; empty on any failure. */
    public Optional<PowerMix> currentMix() {
        return fetchCached(POWER_URL, body -> parseMix(body).orElse(null))
                .map(PowerMix.class::cast);
    }

    private Optional<Object> fetchCached(String url, java.util.function.Function<String, Object> parser) {
        Cached hit = cache.get(url);
        if (hit != null && hit.at().isAfter(Instant.now().minus(CACHE_TTL))) {
            return Optional.of(hit.value());
        }
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) {
                Object parsed = parser.apply(resp.body());
                if (parsed != null) {
                    cache.put(url, new Cached(Instant.now(), parsed));
                    return Optional.of(parsed);
                }
            } else {
                LOG.debug("[EnergyCharts] {} answered status {}", url,
                        resp == null ? "null" : resp.status());
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[EnergyCharts] fetch {} failed: {}", url, e.getMessage());
        }
        // An outage keeps the stale reading instead of caching empty.
        return hit != null ? Optional.of(hit.value()) : Optional.empty();
    }

    /** Package-private for tests: price JSON + "now" → stats, network-free. */
    static Optional<PriceStats> parsePrice(String body, long nowEpochSeconds) {
        if (body == null || body.isBlank()) return Optional.empty();
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode times = root.path("unix_seconds");
            JsonNode prices = root.path("price");
            if (!times.isArray() || !prices.isArray() || prices.isEmpty()) return Optional.empty();
            double current = Double.NaN, min = Double.NaN, max = Double.NaN, sum = 0;
            int count = 0;
            for (int i = 0; i < prices.size(); i++) {
                JsonNode p = prices.get(i);
                if (p == null || p.isNull() || !p.isNumber()) continue;
                double v = p.asDouble();
                if (Double.isNaN(min) || v < min) min = v;
                if (Double.isNaN(max) || v > max) max = v;
                sum += v;
                count++;
                // Latest slot at/before now wins; the last slot stands in once the day ran out.
                if (i < times.size() && times.get(i).asLong(Long.MAX_VALUE) <= nowEpochSeconds) {
                    current = v;
                }
                if (Double.isNaN(current)) current = v; // now before the first slot
            }
            if (count == 0) return Optional.empty();
            return Optional.of(new PriceStats(current, min, max, sum / count));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** Package-private for tests: public_power JSON → current mix, network-free. */
    static Optional<PowerMix> parseMix(String body) {
        if (body == null || body.isBlank()) return Optional.empty();
        try {
            JsonNode root = JSON.readTree(body);
            JsonNode types = root.path("production_types");
            if (!types.isArray() || types.isEmpty()) return Optional.empty();
            JsonNode share = seriesByName(types, "Renewable share of generation");
            // The freshest slot with published data: the share series' last non-null
            // index, else the last index of the longest series.
            int idx = lastNonNullIndex(share);
            if (idx < 0) {
                for (JsonNode t : types) idx = Math.max(idx, t.path("data").size() - 1);
            }
            if (idx < 0) return Optional.empty();
            double sharePct = valueAt(share, idx);
            String topSource = null;
            double topMw = Double.NaN;
            for (JsonNode t : types) {
                String name = t.path("name").asText("");
                if (name.isEmpty() || NON_SOURCES.contains(name)) continue;
                double v = valueAt(t.path("data"), idx);
                if (!Double.isNaN(v) && (Double.isNaN(topMw) || v > topMw)) {
                    topMw = v;
                    topSource = name;
                }
            }
            if (topSource == null && Double.isNaN(sharePct)) return Optional.empty();
            double loadMw = valueAt(seriesByName(types, "Load"), idx);
            return Optional.of(new PowerMix(sharePct, topSource, topMw, loadMw));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static JsonNode seriesByName(JsonNode types, String name) {
        for (JsonNode t : types) {
            if (name.equals(t.path("name").asText(""))) return t.path("data");
        }
        return null;
    }

    private static int lastNonNullIndex(JsonNode data) {
        if (data == null || !data.isArray()) return -1;
        for (int i = data.size() - 1; i >= 0; i--) {
            JsonNode v = data.get(i);
            if (v != null && v.isNumber()) return i;
        }
        return -1;
    }

    private static double valueAt(JsonNode data, int idx) {
        if (data == null || !data.isArray() || idx < 0 || idx >= data.size()) return Double.NaN;
        JsonNode v = data.get(idx);
        return v != null && v.isNumber() ? v.asDouble() : Double.NaN;
    }
}
