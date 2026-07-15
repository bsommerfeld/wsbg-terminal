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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * The LITERAL world weather over the market-relevant places — Open-Meteo's
 * keyless forecast API (no key, no wall, ~10k calls/day free tier; probed
 * 2026-07-14). "Viele handeln auf Wetter" (user mandate): a Gulf-coast storm
 * moves oil and refiners, a Chicago heat wave moves the grain futures, a cold
 * snap over Rotterdam moves TTF gas — this leg gives the evening edition the
 * actual sky over those places, deterministically.
 *
 * <p>ONE request covers ALL places (Open-Meteo accepts comma-separated
 * coordinate lists and answers a JSON ARRAY, one object per coordinate in
 * order). Fields: current temperature/weather code/wind, plus tomorrow's
 * min/max and code from the {@code daily} block ({@code forecast_days=2},
 * index 1 = tomorrow). WMO weather codes are mapped to a small set of stable
 * words ({@link #codeWord}).
 */
@Singleton
public class WorldWeatherClient {

    private static final Logger LOG = LoggerFactory.getLogger(WorldWeatherClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** One market-relevant place: display name + why the market cares. */
    record Place(String name, String role, double lat, double lon) {}

    /**
     * The curated places — each one a market story, not a travel guide:
     * the two financial capitals, the US energy coast, the grain belt's hub,
     * Europe's energy port, the world's factory gate, and the oil capital.
     */
    static final List<Place> PLACES = List.of(
            new Place("New York", "Wall Street", 40.71, -74.01),
            new Place("Houston", "Öl/Gas-Küste", 29.76, -95.37),
            new Place("Chicago", "Agrar-Futures", 41.88, -87.63),
            new Place("London", "City", 51.51, -0.13),
            new Place("Rotterdam", "Energie-Hafen/TTF", 51.92, 4.48),
            new Place("Shanghai", "Fabrik & Hafen", 31.23, 121.47),
            new Place("Riad", "Öl", 24.71, 46.68));

    /**
     * One place's sky, current + tomorrow. Words are stable tokens from
     * {@link #codeWord}; {@code lat}/{@code lon} carry the place's position
     * through to the world map (2026-07-15).
     */
    public record PlaceWeather(String place, String role, Double tempC, String word,
            Double windKmh, Double tomorrowMaxC, Double tomorrowMinC, String tomorrowWord,
            double lat, double lon) {}

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(12);

    /** Test/default: plain direct transport. */
    public WorldWeatherClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam — Open-Meteo has no wall. */
    @Inject
    public WorldWeatherClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** All curated places' current sky + tomorrow, one HTTP call. Empty on any failure. */
    public List<PlaceWeather> worldWeather() {
        StringJoiner lats = new StringJoiner(",");
        StringJoiner lons = new StringJoiner(",");
        for (Place p : PLACES) {
            lats.add(String.valueOf(p.lat()));
            lons.add(String.valueOf(p.lon()));
        }
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + lats
                + "&longitude=" + lons
                + "&current=temperature_2m,weather_code,wind_speed_10m"
                + "&daily=temperature_2m_max,temperature_2m_min,weather_code"
                + "&forecast_days=2&timezone=auto";
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) return parse(resp.body());
            LOG.debug("[WorldWeather] answered status {}", resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[WorldWeather] fetch failed: {}", e.getMessage());
        }
        return List.of();
    }

    /**
     * Package-private for tests. Multi-coordinate replies are a JSON ARRAY in
     * request order; a SINGLE coordinate answers a bare object (handled too).
     */
    static List<PlaceWeather> parse(String body) {
        if (body == null || body.isBlank()) return List.of();
        List<PlaceWeather> out = new ArrayList<>();
        try {
            JsonNode root = JSON.readTree(body);
            List<JsonNode> nodes = new ArrayList<>();
            if (root.isArray()) {
                root.forEach(nodes::add);
            } else if (root.isObject()) {
                nodes.add(root);
            }
            for (int i = 0; i < nodes.size() && i < PLACES.size(); i++) {
                Place place = PLACES.get(i);
                JsonNode n = nodes.get(i);
                JsonNode current = n.get("current");
                JsonNode daily = n.get("daily");
                Double temp = doubleOf(current, "temperature_2m");
                Integer code = intOf(current, "weather_code");
                Double wind = doubleOf(current, "wind_speed_10m");
                Double tMax = dailyAt(daily, "temperature_2m_max", 1);
                Double tMin = dailyAt(daily, "temperature_2m_min", 1);
                Double tCode = dailyAt(daily, "weather_code", 1);
                if (temp == null && tMax == null) continue;
                out.add(new PlaceWeather(place.name(), place.role(), temp,
                        code == null ? null : codeWord(code), wind, tMax, tMin,
                        tCode == null ? null : codeWord((int) (double) tCode),
                        place.lat(), place.lon()));
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    /**
     * WMO weather interpretation codes → a small stable word set (the WMO
     * table is finer than any report needs; buckets follow Open-Meteo's docs).
     */
    static String codeWord(int code) {
        if (code == 0) return "klar";
        if (code <= 2) return "heiter";
        if (code == 3) return "bedeckt";
        if (code <= 48) return "Nebel";
        if (code <= 57) return "Niesel";
        if (code <= 67) return "Regen";
        if (code <= 77) return "Schnee";
        if (code <= 82) return "Schauer";
        if (code <= 86) return "Schneeschauer";
        return "Gewitter";
    }

    private static Double doubleOf(JsonNode n, String field) {
        if (n == null) return null;
        JsonNode v = n.get(field);
        return v == null || !v.isNumber() ? null : v.asDouble();
    }

    private static Integer intOf(JsonNode n, String field) {
        if (n == null) return null;
        JsonNode v = n.get(field);
        return v == null || !v.isNumber() ? null : v.asInt();
    }

    private static Double dailyAt(JsonNode daily, String field, int index) {
        if (daily == null) return null;
        JsonNode arr = daily.get(field);
        if (arr == null || !arr.isArray() || arr.size() <= index) return null;
        JsonNode v = arr.get(index);
        return v == null || !v.isNumber() ? null : v.asDouble();
    }
}
