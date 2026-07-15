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
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * EU Sanctions Map (live-probed 2026-07-14): the regimes live on
 * {@code sanctionsmap.eu/api/v1/regime} (~390 KB keyless JSON, ~55 regimes) —
 * NOT on the dossier's {@code /api/v1/data}, which answers only the UI's
 * filter metadata (countries, measure-type icons, disclaimer articles).
 * Regime shape pinned: {@code specification} (the regime's own name),
 * {@code country.data} ({@code {code,title}} — an EMPTY ARRAY for thematic
 * regimes: chemical weapons, cyber, human rights), {@code adopted_by.data
 * .title} (EU/UN), {@code measures.data[]} (the restrictive measures with
 * descriptions), {@code amendment} = last-update as unix SECONDS.
 */
@Singleton
public class SanctionsMapClient {

    private static final Logger LOG = LoggerFactory.getLogger(SanctionsMapClient.class);

    private static final String URL = "https://www.sanctionsmap.eu/api/v1/regime";
    private static final ObjectMapper JSON = new ObjectMapper();
    /** Regimes change on Council decisions — daily freshness is plenty. */
    private static final Duration CACHE_TTL = Duration.ofHours(24);

    /**
     * One EU sanctions regime; {@code country} null for thematic regimes,
     * {@code lastUpdate} null when the map carries no amendment stamp.
     */
    public record Regime(int id, String specification, String country, String adoptedBy,
            int measuresCount, LocalDate lastUpdate) {
    }

    private record Cached(Instant at, List<Regime> regimes) {
    }

    private final WebFetcher fetcher;
    private final String userAgent = BrowserUserAgent.random();
    private final Duration requestTimeout = Duration.ofSeconds(20);
    private volatile Cached cache;

    /** Test/default: plain direct transport. */
    public SanctionsMapClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam - browser-first since the 2026-07-14 joker mandate. */
    @Inject
    public SanctionsMapClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** All EU sanctions regimes (country-bound and thematic). Empty on any failure. */
    public List<Regime> regimes() {
        Cached hit = cache;
        if (hit != null && hit.at().isAfter(Instant.now().minus(CACHE_TTL))) {
            return hit.regimes();
        }
        try {
            WebResponse resp = fetcher.fetch(URL,
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) {
                List<Regime> regimes = parse(resp.body());
                if (!regimes.isEmpty()) {
                    cache = new Cached(Instant.now(), regimes);
                }
                return regimes;
            }
            LOG.debug("[SanctionsMap] answered status {}", resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[SanctionsMap] fetch failed: {}", e.getMessage());
        }
        // An outage keeps the stale pool instead of caching empty.
        return hit != null ? hit.regimes() : List.of();
    }

    /**
     * The country's sanctions regime by English name (case-insensitive).
     * A country with several regimes (Russia carries three) answers with the
     * broadest one — most measures wins.
     */
    public Optional<Regime> forCountry(String name) {
        return pickForCountry(regimes(), name);
    }

    /** Package-private for tests: country pick over a parsed pool. */
    static Optional<Regime> pickForCountry(List<Regime> regimes, String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        String needle = name.strip().toLowerCase(Locale.ROOT);
        return regimes.stream()
                .filter(r -> r.country() != null
                        && r.country().toLowerCase(Locale.ROOT).equals(needle))
                .max(java.util.Comparator.comparingInt(Regime::measuresCount));
    }

    /** Package-private for tests: regime JSON → regimes, network-free. */
    static List<Regime> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        List<Regime> out = new ArrayList<>();
        try {
            for (JsonNode r : JSON.readTree(json).path("data")) {
                String spec = r.path("specification").asText("").strip();
                if (spec.isEmpty()) continue;
                // Thematic regimes ship country.data as an EMPTY ARRAY.
                JsonNode country = r.path("country").path("data");
                String countryName = country.isObject()
                        ? blankToNull(country.path("title").asText("")) : null;
                long amendment = r.path("amendment").asLong(-1);
                out.add(new Regime(r.path("id").asInt(-1), spec, countryName,
                        blankToNull(r.path("adopted_by").path("data").path("title").asText("")),
                        r.path("measures").path("data").size(),
                        amendment > 0
                                ? Instant.ofEpochSecond(amendment)
                                        .atZone(ZoneOffset.UTC).toLocalDate()
                                : null));
            }
        } catch (Exception e) {
            LOG.debug("[SanctionsMap] parse failed: {}", e.getMessage());
        }
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
