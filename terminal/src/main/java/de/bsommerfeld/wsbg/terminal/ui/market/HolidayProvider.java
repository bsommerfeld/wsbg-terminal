package de.bsommerfeld.wsbg.terminal.ui.market;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Source of exchange trading-day closures. Resolves the set of
 * full-closure dates for a country/year from the bundled
 * {@code market-holidays.json} calendar first, only falling back to the
 * live {@code date.nager.at} API for years the bundle does not cover.
 *
 * <p>
 * The bundled calendar is authoritative because it lists <em>exchange</em>
 * closures, whereas the live API returns generic <em>public</em> holidays
 * — a different set: it misses NYSE's Good Friday and TSE's Jan 2-3
 * (not public holidays), and includes days exchanges trade through (e.g.
 * German Unity Day on XETRA, the ASX Anzac-Day weekend substitute). The
 * live API is therefore only a rough gap-filler for years beyond the
 * bundle (currently 2026-2028); refresh the JSON annually.
 *
 * <p>
 * Results are cached per {@code (country, year)} for the lifetime of the
 * process.
 */
@Singleton
public final class HolidayProvider {

    private static final Logger LOG = LoggerFactory.getLogger(HolidayProvider.class);
    private static final String API = "https://date.nager.at/api/v3/PublicHolidays/%d/%s";

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, Set<LocalDate>> cache = new ConcurrentHashMap<>();

    public Set<LocalDate> holidays(String countryCode, int year) {
        String key = countryCode + ":" + year;
        return cache.computeIfAbsent(key, k -> resolve(countryCode, year));
    }

    private Set<LocalDate> resolve(String country, int year) {
        Set<LocalDate> curated = loadCurated(country, year);
        if (!curated.isEmpty()) {
            LOG.info("Loaded {} exchange closures for {} {} from bundled calendar",
                    curated.size(), country, year);
            return curated;
        }
        // No curated data for this year — approximate with the live
        // public-holiday API. This is intentionally a rough fallback:
        // public holidays are NOT exchange closures (see class javadoc).
        Set<LocalDate> live = fetchLive(country, year);
        if (!live.isEmpty()) {
            LOG.warn("No bundled calendar for {} {}; approximating with public-holiday API "
                    + "({} dates) — refresh market-holidays.json", country, year, live.size());
            return live;
        }
        LOG.warn("No closure data for {} {} from any source", country, year);
        return Collections.emptySet();
    }

    private Set<LocalDate> fetchLive(String country, int year) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(String.format(API, year, country)))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) return Collections.emptySet();

            JsonNode arr = mapper.readTree(resp.body());
            Set<LocalDate> out = new HashSet<>();
            for (JsonNode n : arr) {
                JsonNode types = n.get("types");
                boolean isPublic = types != null && types.isArray()
                        && stream(types).anyMatch(t -> "Public".equalsIgnoreCase(t.asText()));
                if (!isPublic) continue;
                JsonNode date = n.get("date");
                if (date != null) out.add(LocalDate.parse(date.asText()));
            }
            return out;
        } catch (Exception e) {
            LOG.debug("Live holiday fetch failed for {} {}: {}", country, year, e.getMessage());
            return Collections.emptySet();
        }
    }

    private Set<LocalDate> loadCurated(String country, int year) {
        try (InputStream in = HolidayProvider.class.getResourceAsStream("/market-holidays.json")) {
            if (in == null) return Collections.emptySet();
            JsonNode root = mapper.readTree(in);
            JsonNode countryNode = root.get(country);
            if (countryNode == null) return Collections.emptySet();
            JsonNode yearNode = countryNode.get(String.valueOf(year));
            if (yearNode == null || !yearNode.isArray()) return Collections.emptySet();
            Set<LocalDate> out = new HashSet<>();
            for (JsonNode n : yearNode) out.add(LocalDate.parse(n.asText()));
            return out;
        } catch (Exception e) {
            LOG.warn("Curated holiday load failed for {} {}: {}", country, year, e.getMessage());
            return Collections.emptySet();
        }
    }

    private static java.util.stream.Stream<JsonNode> stream(JsonNode arr) {
        return java.util.stream.StreamSupport.stream(arr.spliterator(), false);
    }
}
