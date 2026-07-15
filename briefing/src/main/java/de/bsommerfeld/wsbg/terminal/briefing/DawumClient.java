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
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * dawum.de German election polls (live-probed 2026-07-14): {@code api.dawum.de/}
 * answers the WHOLE poll database as ONE keyless JSON dump (~1 MB, ODC-ODbL,
 * {@code Database.Last_Update} dates the dump). Normalized tables keyed by id
 * strings — {@code Surveys} (~3.9k, NOT reliably ordered) joined to
 * {@code Parliaments}/{@code Institutes}/{@code Parties}; a survey's
 * {@code Results} maps party-id → percent (int or decimal — read as double),
 * {@code Surveyed_Persons} is a STRING. Unknown party ids surface as
 * {@code "#<id>"} rather than being dropped.
 */
@Singleton
public class DawumClient {

    private static final Logger LOG = LoggerFactory.getLogger(DawumClient.class);

    private static final String URL = "https://api.dawum.de/";
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Polls land a handful of times per day; the ~1 MB dump is worth 6 h. */
    private static final Duration CACHE_TTL = Duration.ofHours(6);

    /**
     * One poll: parliament + institute by name, {@code results} maps party
     * shortcut ("CDU/CSU", "AfD", ...) → percent, {@code surveyedPersons}
     * -1 when unknown.
     */
    public record Survey(String parliament, String institute, LocalDate date,
            int surveyedPersons, Map<String, Double> results) {
    }

    private record Cached(Instant at, List<Survey> surveys) {
    }

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(25);
    private volatile Cached cache;

    /** Test/default: plain direct transport. */
    public DawumClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam - browser-first since the 2026-07-14 joker mandate. */
    @Inject
    public DawumClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /**
     * The newest {@code perParliament} surveys of EACH polled parliament
     * (Bundestag, Landtage, EU), newest first overall. Empty on any failure.
     */
    public List<Survey> latest(int perParliament) {
        return latestOf(fetch(), perParliament);
    }

    /** Package-private for tests: newest-N-per-parliament over a parsed pool. */
    static List<Survey> latestOf(List<Survey> all, int perParliament) {
        List<Survey> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparing(Survey::date,
                Comparator.nullsLast(Comparator.reverseOrder())));
        Map<String, Integer> taken = new HashMap<>();
        List<Survey> out = new ArrayList<>();
        for (Survey s : sorted) {
            int seen = taken.merge(s.parliament(), 1, Integer::sum);
            if (seen <= perParliament) out.add(s);
        }
        return out;
    }

    private List<Survey> fetch() {
        Cached hit = cache;
        if (hit != null && hit.at().isAfter(Instant.now().minus(CACHE_TTL))) {
            return hit.surveys();
        }
        try {
            WebResponse resp = fetcher.fetch(URL,
                    Map.of("Accept", "application/json"), requestTimeout);
            if (resp != null && resp.status() == 200) {
                List<Survey> surveys = parse(resp.body());
                if (!surveys.isEmpty()) {
                    cache = new Cached(Instant.now(), surveys);
                }
                return surveys;
            }
            LOG.debug("[dawum] answered status {}", resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[dawum] fetch failed: {}", e.getMessage());
        }
        // An outage keeps the stale pool instead of caching empty.
        return hit != null ? hit.surveys() : List.of();
    }

    /** Package-private for tests: the full dump JSON → joined surveys, network-free. */
    static List<Survey> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        List<Survey> out = new ArrayList<>();
        try {
            JsonNode root = JSON.readTree(json);
            JsonNode parliaments = root.path("Parliaments");
            JsonNode institutes = root.path("Institutes");
            JsonNode parties = root.path("Parties");
            root.path("Surveys").properties().forEach(e -> {
                JsonNode s = e.getValue();
                LocalDate date = parseDate(s.path("Date").asText(""));
                String parliament = nameOf(parliaments, s.path("Parliament_ID").asText(""),
                        "Shortcut");
                String institute = nameOf(institutes, s.path("Institute_ID").asText(""), "Name");
                Map<String, Double> results = new LinkedHashMap<>();
                s.path("Results").properties().forEach(r -> {
                    String party = nameOf(parties, r.getKey(), "Shortcut");
                    results.put(party != null ? party : "#" + r.getKey(),
                            r.getValue().asDouble(Double.NaN));
                });
                if (parliament != null && !results.isEmpty()) {
                    out.add(new Survey(parliament, institute, date,
                            parseInt(s.path("Surveyed_Persons").asText("")),
                            Map.copyOf(results)));
                }
            });
        } catch (Exception e) {
            LOG.debug("[dawum] parse failed: {}", e.getMessage());
        }
        return out;
    }

    private static String nameOf(JsonNode table, String id, String field) {
        JsonNode row = table.path(id);
        String name = row.path(field).asText("");
        return name.isBlank() ? null : name;
    }

    private static LocalDate parseDate(String iso) {
        try {
            return LocalDate.parse(iso);
        } catch (Exception e) {
            return null;
        }
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception e) {
            return -1;
        }
    }
}
