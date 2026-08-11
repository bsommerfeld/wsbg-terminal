package de.bsommerfeld.wsbg.terminal.web.impl.sources.briefing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Nordic (and beyond) issuer disclosures with the ISIN already attached
 * (live-verified 2026-08-03): {@code mfn.se/all/a.json?limit=&offset=}, a plain
 * JSON-feed envelope. Every item names its subject with {@code isins},
 * {@code leis} and exchange-prefixed {@code tickers}, so it joins to our
 * instrument spine exactly — no name guessing. 20 of 20 items in the live
 * sample carried an ISIN.
 *
 * <p>Despite the {@code .se} it is not Sweden-only: a 200-item sample spanned
 * GB, SE, NO, FR, US, FI, DK, CA, IE, LU, PT, BE, NL and CH. Tags are
 * machine-usable ({@code :regulatory}, {@code sub:report:interim},
 * {@code sub:ci:insider}), and the full release body ships inline as
 * {@code content.html} plus a plain-text twin — no second fetch to read it.
 *
 * <p>Only this one route works: {@code api.mfn.se/v1/feed/all} answers 401,
 * {@code feed.mfn.se} 400, and {@code ?filter=} 500. Paging is
 * {@code limit}/{@code offset}, and the envelope hands back the next page as
 * {@code next_url}. Titles arrive in twelve languages, and issuers routinely
 * file the SAME release twice — once locally, once in English. The envelope
 * groups those twins under a shared {@code group_id}, so one release is kept
 * per group with English preferred; without that, every Nordic filing would
 * reach the reports doubled.
 */
@Singleton
public class MfnDisclosureClient {

    private static final Logger LOG = LoggerFactory.getLogger(MfnDisclosureClient.class);

    private static final String FEED = "https://mfn.se/all/a.json?limit=";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long CACHE_TTL_MS = 15 * 60 * 1000L;
    /** One page is already about a megabyte — never sweep the archive. */
    private static final int MAX_LIMIT = 200;

    /**
     * One disclosure. {@code isins} may name several instruments of one issuer;
     * {@code tags} are MFN's own machine labels; {@code text} is the release
     * body as plain text (the HTML twin stays out — nothing here renders it).
     */
    public record Disclosure(Instant publishedAt, String title, String url, String issuer,
            List<String> isins, List<String> tickers, List<String> tags,
            String language, String text) {

        /** The issuer's own filing, as opposed to a supervisor's or agency's notice. */
        public boolean regulatory() {
            return tags.contains(":regulatory");
        }
    }

    /** Transport order of the old {@code @DirectFirst} seam: direct first, browser joker as fallback. */
    private static final FetchUtil[] MODES = {FetchUtil.DIRECT, FetchUtil.BROWSER};

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(20);

    private volatile List<Disclosure> cache = List.of();
    private volatile long cachedAtMs;

    /** Production: the shared house fetcher; {@code MODES} carries the old {@code @DirectFirst} order. */
    @Inject
    public MfnDisclosureClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    /** The newest disclosures, newest first. Cached 15 minutes. Empty on any failure. */
    public List<Disclosure> latest(int limit) {
        long now = System.currentTimeMillis();
        if (!cache.isEmpty() && now - cachedAtMs < CACHE_TTL_MS) return cache;
        int n = Math.max(1, Math.min(limit, MAX_LIMIT));
        List<Disclosure> parsed = List.of();
        try {
            WebResponse resp = fetcher.fetch(FEED + n,
                    Map.of("Accept", "application/json"),
                    requestTimeout, MODES);
            if (resp != null && resp.status() == 200) {
                parsed = parse(resp.body());
            } else {
                LOG.debug("[MFN] answered status {}", resp == null ? "null" : resp.status());
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[MFN] fetch failed: {}", e.getMessage());
        }
        if (!parsed.isEmpty()) {
            cache = parsed;
            cachedAtMs = now;
        }
        return parsed;
    }

    /** Everything filed about that ISIN, newest first. The join is exact, never by name. */
    public List<Disclosure> forIsin(String isin, int limit) {
        if (isin == null || isin.isBlank()) return List.of();
        List<Disclosure> out = new ArrayList<>();
        for (Disclosure d : latest(MAX_LIMIT)) {
            for (String candidate : d.isins()) {
                if (isin.equalsIgnoreCase(candidate)) {
                    out.add(d);
                    break;
                }
            }
            if (out.size() >= limit) break;
        }
        return out;
    }

    /** Package-private for tests: the JSON-feed envelope → disclosures, garbage-tolerant. */
    static List<Disclosure> parse(String body) {
        if (body == null || body.isBlank()) return List.of();
        // group_id → the one item kept for that release (see the class note).
        java.util.Map<String, Disclosure> byGroup = new java.util.LinkedHashMap<>();
        List<Disclosure> ungrouped = new ArrayList<>();
        try {
            JsonNode items = JSON.readTree(body).path("items");
            if (!items.isArray()) return List.of();
            for (JsonNode n : items) {
                JsonNode content = n.path("content");
                String title = content.path("title").asText("").trim();
                Instant at = instantOf(content.path("publish_date").asText(""));
                if (title.isEmpty() || at == null) continue;

                // One release can name several subjects (a parent and its
                // listed sub-entity); every ISIN of every subject counts.
                Set<String> isins = new LinkedHashSet<>();
                Set<String> tickers = new LinkedHashSet<>();
                String issuer = "";
                for (JsonNode subject : n.path("subjects")) {
                    if (issuer.isEmpty()) issuer = subject.path("name").asText("").trim();
                    for (JsonNode i : subject.path("isins")) add(isins, i.asText(""));
                    for (JsonNode t : subject.path("tickers")) add(tickers, t.asText(""));
                }
                List<String> tags = new ArrayList<>();
                for (JsonNode t : n.path("properties").path("tags")) add(tags, t.asText(""));

                Disclosure d = new Disclosure(at, title, n.path("url").asText("").trim(), issuer,
                        List.copyOf(isins), List.copyOf(tickers), List.copyOf(tags),
                        n.path("properties").path("lang").asText("").trim()
                                .toLowerCase(Locale.ROOT),
                        content.path("text").asText("").trim());
                String group = n.path("group_id").asText("").trim();
                if (group.isEmpty()) {
                    ungrouped.add(d);
                    continue;
                }
                Disclosure kept = byGroup.get(group);
                if (kept == null || (!"en".equals(kept.language()) && "en".equals(d.language()))) {
                    byGroup.put(group, d);
                }
            }
        } catch (Exception e) {
            return List.of();
        }
        List<Disclosure> out = new ArrayList<>(byGroup.values());
        out.addAll(ungrouped);
        out.sort(java.util.Comparator.comparing(Disclosure::publishedAt).reversed());
        return out;
    }

    private static void add(java.util.Collection<String> target, String value) {
        String v = value == null ? "" : value.trim();
        if (!v.isEmpty()) target.add(v);
    }

    private static Instant instantOf(String stamp) {
        if (stamp == null || stamp.isBlank()) return null;
        try {
            return OffsetDateTime.parse(stamp).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
