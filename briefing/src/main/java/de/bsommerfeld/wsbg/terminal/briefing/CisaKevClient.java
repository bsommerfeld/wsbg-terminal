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
import java.util.List;
import java.util.Map;

/**
 * CISA Known Exploited Vulnerabilities (KEV) — the tech-sector shadow leg
 * (keyless JSON, live-probed 2026-07-14):
 * {@code cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json}
 * ships the whole catalog (~1600 CVEs) with {@code catalogVersion} +
 * {@code dateReleased} and a {@code vulnerabilities[]} array. Per CVE:
 * {@code cveID}, {@code vendorProject}, {@code product},
 * {@code dateAdded} (ISO {@code YYYY-MM-DD}), {@code shortDescription}, plus a
 * {@code knownRansomwareCampaignUse} flag. We surface only the CVEs ADDED in
 * the last N days (a named vendor/product landing on the actively-exploited
 * list is the market-relevant signal for its sector).
 *
 * <p>Daily releases: cache 12h. Best-effort empty on any failure.
 */
@Singleton
public class CisaKevClient {

    private static final Logger LOG = LoggerFactory.getLogger(CisaKevClient.class);

    private static final String URL = "https://www.cisa.gov/sites/default/files/feeds/"
            + "known_exploited_vulnerabilities.json";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration CACHE_TTL = Duration.ofHours(12);

    /** One newly-listed exploited CVE. {@code ransomware} true when knownRansomwareCampaignUse == "Known". */
    public record Kev(String cveID, String vendorProject, String product,
            LocalDate dateAdded, String shortDescription, boolean ransomware) {
    }

    private record Cached(Instant at, List<Kev> all) {
    }

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(20);
    private volatile Cached cache;

    /** Test/default: plain direct transport. */
    public CisaKevClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam — the CISA CDN is wall-less. */
    @Inject
    public CisaKevClient(@de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** CVEs added within the last {@code days} (inclusive), newest-added first. */
    public synchronized List<Kev> recentlyAdded(int days) {
        List<Kev> all = load();
        LocalDate cutoff = LocalDate.now().minusDays(Math.max(days, 0));
        List<Kev> out = new ArrayList<>();
        for (Kev k : all) {
            if (k.dateAdded() != null && !k.dateAdded().isBefore(cutoff)) out.add(k);
        }
        out.sort((a, b) -> b.dateAdded().compareTo(a.dateAdded()));
        return out;
    }

    private List<Kev> load() {
        Cached hit = cache;
        if (hit != null && hit.at().isAfter(Instant.now().minus(CACHE_TTL))) {
            return hit.all();
        }
        try {
            WebResponse resp = fetcher.fetch(URL, Map.of("Accept", "application/json"), requestTimeout);
            if (resp != null && resp.status() == 200) {
                List<Kev> all = parse(resp.body());
                if (!all.isEmpty()) {
                    cache = new Cached(Instant.now(), all);
                    return all;
                }
            } else {
                LOG.debug("[CISA-KEV] answered status {}", resp == null ? "null" : resp.status());
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[CISA-KEV] failed: {}", e.getMessage());
        }
        return hit != null ? hit.all() : List.of();
    }

    /** Package-private for tests: KEV catalog JSON → all CVEs, network-free. */
    static List<Kev> parse(String json) {
        if (json == null || json.isBlank()) return List.of();
        List<Kev> out = new ArrayList<>();
        try {
            for (JsonNode n : JSON.readTree(json).path("vulnerabilities")) {
                String cve = n.path("cveID").asText("").strip();
                if (cve.isEmpty()) continue;
                out.add(new Kev(cve,
                        n.path("vendorProject").asText("").strip(),
                        n.path("product").asText("").strip(),
                        parseDate(n.path("dateAdded").asText("")),
                        n.path("shortDescription").asText("").strip(),
                        "Known".equalsIgnoreCase(n.path("knownRansomwareCampaignUse").asText(""))));
            }
        } catch (Exception e) {
            LOG.debug("[CISA-KEV] parse failed: {}", e.getMessage());
        }
        return out;
    }

    private static LocalDate parseDate(String iso) {
        try {
            return LocalDate.parse(iso);
        } catch (Exception e) {
            return null;
        }
    }
}
