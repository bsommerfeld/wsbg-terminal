package de.bsommerfeld.wsbg.terminal.briefing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.source.net.DirectFirst;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Wikipedia + Wikidata - the fact context around a name (live-probed
 * 2026-08-02, keyless throughout).
 *
 * <p>Four endpoints carry everything this house needs:
 * <ul>
 *   <li>{@code de.wikipedia.org/api/rest_v1/page/summary/<title>} - the lead
 *       paragraph AND the {@code wikibase_item} (the Q-id) in the same
 *       payload, which is what makes the summary the cheapest safe entry
 *       point into Wikidata.</li>
 *   <li>{@code de.wikipedia.org/w/api.php?action=query&prop=pageprops&ppprop=wikibase_item&titles=A|B|C&redirects=1}
 *       - name → Q-id in batches, with redirects and title normalisation
 *       resolved server-side.</li>
 *   <li>{@code wikidata.org/w/api.php?action=query&list=search&srsearch=haswbstatement:P946=<ISIN>}
 *       - the way BACK, ISIN → Q-id (verified down to Nynomic:
 *       DE000A0MSN11 → Q125924224).</li>
 *   <li>{@code wikimedia.org/api/rest_v1/metrics/pageviews/per-article/…/daily/…}
 *       - daily page views as an attention signal, roughly one day behind.</li>
 * </ul>
 * plus {@code query.wikidata.org/sparql} for the structured facts.
 *
 * <p><b>Two traps that would otherwise make this client worthless:</b>
 * <ol>
 *   <li><b>Tickers are NOT {@code wdt:P249}.</b> Querying that path finds 38
 *       items worldwide. Tickers hang as a QUALIFIER on the stock-exchange
 *       statement - {@code ?c p:P414 ?s . ?s ps:P414 ?exchange ; pq:P249 ?ticker}
 *       - which is 17,182 statements and is the better shape anyway, because a
 *       listing carries a different symbol per exchange.</li>
 *   <li><b>Never guess a Q-id.</b> {@code wbsearchentities} looks helpful and
 *       is dangerous: Q188326 reads like SAP and is H&amp;M (P946 =
 *       SE0000106270). The only safe routes are {@code pageprops.wikibase_item}
 *       off a real article title and {@code haswbstatement:P946=<ISIN>}. This
 *       client offers no guessing path at all; {@link #qidForNameVerifiedByIsin}
 *       exists for the case where a name is all one has and pins the answer to
 *       an ISIN before returning it.</li>
 * </ol>
 *
 * <p><b>Expectation management.</b> About 9,956 items worldwide carry an ISIN
 * (P946) against 32,563 tradeable shares on onvista alone; P1278 LEI reaches
 * 52,924, P414 exchange listings 17,182. Small caps are fragments - Nynomic has
 * an item with an ISIN but no exchange, no ticker, no headcount, no revenue.
 * <b>Unusable as an identity resolver, good as enrichment for names that are
 * already known.</b>
 *
 * <p><b>Licence.</b> Wikipedia text is CC BY-SA 4.0 - attribution plus
 * share-alike on derived text; harmless as model context, attribution-bound the
 * moment a paragraph is displayed verbatim ({@link PageSummary#url()} is the
 * attribution link). Wikidata is CC0 - no strings whatsoever.
 *
 * <p>Wikimedia asks for a descriptive User-Agent with a contact, and
 * {@code api-user-agent} is explicitly allowed in their CORS headers - so this
 * client sends a speaking UA rather than a random browser one.
 *
 * <p>Per the owner mandate of 2026-08-02 every endpoint is wired even where no
 * caller exists yet - the pageviews leg in particular is the general attention
 * signal and is marked "currently unused, stands ready".
 */
@Singleton
public class WikidataClient {

    private static final Logger LOG = LoggerFactory.getLogger(WikidataClient.class);

    private static final String DEFAULT_WIKI = "de";
    private static final String SUMMARY = "https://%s.wikipedia.org/api/rest_v1/page/summary/%s";
    private static final String PAGEPROPS = "https://%s.wikipedia.org/w/api.php"
            + "?action=query&format=json&prop=pageprops&ppprop=wikibase_item&redirects=1&titles=%s";
    private static final String ISIN_SEARCH = "https://www.wikidata.org/w/api.php"
            + "?action=query&format=json&list=search&srsearch=haswbstatement:P946=%s";
    private static final String PAGEVIEWS = "https://wikimedia.org/api/rest_v1/metrics/pageviews"
            + "/per-article/%s.wikipedia/all-access/all-agents/%s/daily/%s/%s";
    private static final String SPARQL = "https://query.wikidata.org/sparql?format=json&query=%s";

    /**
     * A speaking agent with a contact - Wikimedia rate-limits anonymous
     * browser-shaped traffic and asks for exactly this.
     */
    private static final String USER_AGENT =
            "wsbg-terminal/1.0 (https://github.com/bsommerfeld/wsbg-terminal; contact via repository)";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final DateTimeFormatter PV_DAY = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** Articles and items move slowly; the cache is politeness, not performance. */
    private static final Duration TTL = Duration.ofHours(6);
    /** Pageviews get a fresh number once a day at most (the API lags ~1 day). */
    private static final Duration PAGEVIEWS_TTL = Duration.ofHours(6);

    // ---- records -----------------------------------------------------------

    /**
     * A Wikipedia lead. {@code qid} is the Wikidata item of the SAME page - the
     * only safe name→item bridge there is. {@code url} is the canonical article
     * link and doubles as the CC BY-SA attribution.
     */
    public record PageSummary(String title, String qid, String description,
            String extract, String url) {
    }

    /**
     * The structured facts of an item; every field may be empty, which is the
     * normal case outside the large caps. {@code employees}/{@code revenue} are
     * the raw literals as Wikidata stores them (no unit, no currency - the
     * qualifier carries those and is deliberately not flattened here).
     */
    public record CompanyFacts(String qid, String isin, String lei, String employees,
            String revenue, String inception, String website) {

        /** True when the item is more than a bare name - worth putting in front of the model. */
        public boolean hasAnything() {
            return !isin.isEmpty() || !lei.isEmpty() || !employees.isEmpty()
                    || !revenue.isEmpty() || !inception.isEmpty() || !website.isEmpty();
        }
    }

    /** One listing: the symbol as it trades on THAT exchange (P249 qualifier on P414). */
    public record ExchangeTicker(String exchangeQid, String exchange, String ticker) {
    }

    /** One day of attention on an article. */
    public record PageviewPoint(LocalDate day, long views) {
    }

    private record Cached(Instant at, Object value) {
    }

    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(15);
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /** Test/default: plain direct transport. */
    public WikidataClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the shared {@code @DirectFirst} seam (joker mandate 2026-07-14). */
    @Inject
    public WikidataClient(@DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    // ---- Wikipedia ---------------------------------------------------------

    /** The German article lead plus its Q-id. */
    public Optional<PageSummary> summary(String title) {
        return summary(DEFAULT_WIKI, title);
    }

    /**
     * The lead of any language wiki ({@code "de"}, {@code "en"}, …).
     * Currently unused beyond the German default, stands ready.
     */
    public Optional<PageSummary> summary(String wiki, String title) {
        if (title == null || title.isBlank()) return Optional.empty();
        String url = String.format(SUMMARY, wikiOf(wiki), encodeTitle(title));
        return one(url, TTL, WikidataClient::parseSummary);
    }

    /**
     * Batch name → Q-id, redirects and title normalisation resolved by the API.
     * The answer is keyed by the caller's own strings where the API reports a
     * redirect or a normalisation, so a lookup never has to guess what the
     * server renamed. Names without an article are simply absent - and note
     * that titles are case-sensitive past the first letter ("siemens energy"
     * normalises to "Siemens energy" and misses).
     */
    public Map<String, String> qidsFor(List<String> titles) {
        return qidsFor(DEFAULT_WIKI, titles);
    }

    /** Same in any wiki. Currently unused beyond the German default, stands ready. */
    public Map<String, String> qidsFor(String wiki, List<String> titles) {
        if (titles == null || titles.isEmpty()) return Map.of();
        String joined = String.join("|", titles.stream().filter(t -> t != null && !t.isBlank()).toList());
        if (joined.isBlank()) return Map.of();
        String url = String.format(PAGEPROPS, wikiOf(wiki), encode(joined));
        Optional<Map<String, String>> hit = one(url, TTL, WikidataClient::parsePageProps);
        return hit.orElse(Map.of());
    }

    // ---- Wikidata ----------------------------------------------------------

    /**
     * The way back: ISIN → Q-id, via {@code haswbstatement} - the ONE lookup in
     * this whole area that cannot silently return the wrong company.
     */
    public Optional<String> qidForIsin(String isin) {
        if (isin == null || isin.isBlank()) return Optional.empty();
        String url = String.format(ISIN_SEARCH, encode(isin.trim().toUpperCase(Locale.ROOT)));
        return one(url, TTL, WikidataClient::parseIsinSearch);
    }

    /**
     * A name resolved to a Q-id and then PINNED to the expected ISIN - the only
     * name-shaped path this client will vouch for. Answers empty when the
     * article's item carries a different ISIN or none at all.
     */
    public Optional<String> qidForNameVerifiedByIsin(String title, String isin) {
        if (isin == null || isin.isBlank()) return Optional.empty();
        Optional<PageSummary> page = summary(title);
        if (page.isEmpty() || page.get().qid().isEmpty()) return Optional.empty();
        String qid = page.get().qid();
        Optional<CompanyFacts> facts = facts(qid);
        boolean matches = facts.isPresent()
                && facts.get().isin().equalsIgnoreCase(isin.trim());
        return matches ? Optional.of(qid) : Optional.empty();
    }

    /** ISIN, LEI, headcount, revenue, founding date and website of an item. */
    public Optional<CompanyFacts> facts(String qid) {
        String id = qidOf(qid);
        if (id.isEmpty()) return Optional.empty();
        String query = """
                SELECT ?isin ?lei ?employees ?revenue ?inception ?website WHERE {
                  OPTIONAL { wd:%1$s wdt:P946 ?isin }
                  OPTIONAL { wd:%1$s wdt:P1278 ?lei }
                  OPTIONAL { wd:%1$s wdt:P1128 ?employees }
                  OPTIONAL { wd:%1$s wdt:P2139 ?revenue }
                  OPTIONAL { wd:%1$s wdt:P571 ?inception }
                  OPTIONAL { wd:%1$s wdt:P856 ?website }
                } LIMIT 1""".formatted(id);
        return one(String.format(SPARQL, encode(query)), TTL,
                body -> parseFacts(id, body));
    }

    /**
     * Every listing of an item with the symbol it trades under THERE - the
     * qualifier path {@code p:P414 / pq:P249}, never the near-empty
     * {@code wdt:P249}.
     */
    public List<ExchangeTicker> tickers(String qid) {
        String id = qidOf(qid);
        if (id.isEmpty()) return List.of();
        String query = """
                SELECT ?exchange ?exchangeLabel ?ticker WHERE {
                  wd:%s p:P414 ?s .
                  ?s ps:P414 ?exchange ; pq:P249 ?ticker .
                  SERVICE wikibase:label { bd:serviceParam wikibase:language "de,en". }
                }""".formatted(id);
        return list(String.format(SPARQL, encode(query)), TTL, WikidataClient::parseTickers);
    }

    // ---- Wikimedia attention ----------------------------------------------

    /**
     * Daily page views of an article - the general attention signal of this
     * client, roughly one day behind. Currently unused, stands ready.
     */
    public List<PageviewPoint> pageviews(String title, LocalDate from, LocalDate to) {
        return pageviews(DEFAULT_WIKI, title, from, to);
    }

    /** Same in any wiki. Currently unused, stands ready. */
    public List<PageviewPoint> pageviews(String wiki, String title, LocalDate from, LocalDate to) {
        if (title == null || title.isBlank() || from == null || to == null) return List.of();
        String url = String.format(PAGEVIEWS, wikiOf(wiki), encodeTitle(title),
                PV_DAY.format(from), PV_DAY.format(to));
        return list(url, PAGEVIEWS_TTL, WikidataClient::parsePageviews);
    }

    // ---- transport ---------------------------------------------------------

    @SuppressWarnings("unchecked")
    private <T> Optional<T> one(String url, Duration ttl, Function<String, Optional<T>> parser) {
        Cached hit = cache.get(url);
        if (hit != null && hit.at().isAfter(Instant.now().minus(ttl))) {
            return Optional.of((T) hit.value());
        }
        String body = fetch(url);
        if (body != null) {
            Optional<T> parsed = parser.apply(body);
            if (parsed.isPresent()) {
                cache.put(url, new Cached(Instant.now(), parsed.get()));
                return parsed;
            }
        }
        return hit == null ? Optional.empty() : Optional.of((T) hit.value());
    }

    @SuppressWarnings("unchecked")
    private <T> List<T> list(String url, Duration ttl, Function<String, List<T>> parser) {
        Cached hit = cache.get(url);
        if (hit != null && hit.at().isAfter(Instant.now().minus(ttl))) {
            return (List<T>) hit.value();
        }
        String body = fetch(url);
        if (body != null) {
            List<T> parsed = parser.apply(body);
            if (!parsed.isEmpty()) {
                cache.put(url, new Cached(Instant.now(), parsed));
                return parsed;
            }
        }
        return hit == null ? List.of() : (List<T>) hit.value();
    }

    private String fetch(String url) {
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", USER_AGENT, "Api-User-Agent", USER_AGENT,
                            "Accept", "application/json"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) return resp.body();
            LOG.debug("[Wikidata] {} answered status {}", url, resp == null ? "null" : resp.status());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[Wikidata] fetch {} failed: {}", url, e.getMessage());
        }
        return null;
    }

    // ---- parsers (package-visible for the fixture tests) -------------------

    /** REST summary → lead + Q-id. Network-free, garbage-tolerant. */
    static Optional<PageSummary> parseSummary(String body) {
        if (body == null || body.isBlank()) return Optional.empty();
        try {
            JsonNode n = JSON.readTree(body);
            String title = text(n, "title");
            if (title.isEmpty()) return Optional.empty();
            String url = n.path("content_urls").path("desktop").path("page").asText("");
            return Optional.of(new PageSummary(title, text(n, "wikibase_item"),
                    text(n, "description"), text(n, "extract"), url));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * {@code prop=pageprops} → title → Q-id. Pages without an item (and the
     * {@code missing} ones) drop out; redirect and normalisation sources are
     * added as extra keys so the caller finds its own spelling again.
     */
    static Optional<Map<String, String>> parsePageProps(String body) {
        if (body == null || body.isBlank()) return Optional.empty();
        try {
            JsonNode query = JSON.readTree(body).path("query");
            JsonNode pages = query.path("pages");
            if (!pages.isObject()) return Optional.empty();
            Map<String, String> byTitle = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = pages.fields();
            while (it.hasNext()) {
                JsonNode page = it.next().getValue();
                String qid = page.path("pageprops").path("wikibase_item").asText("");
                String title = text(page, "title");
                if (qid.isEmpty() || title.isEmpty()) continue;
                byTitle.put(title, qid);
            }
            Map<String, String> out = new LinkedHashMap<>(byTitle);
            // "from"→"to" hops, applied twice so normalisation→redirect chains resolve.
            Map<String, String> hops = new HashMap<>();
            for (String kind : List.of("normalized", "redirects")) {
                for (JsonNode hop : query.path(kind)) {
                    hops.put(text(hop, "from"), text(hop, "to"));
                }
            }
            for (int pass = 0; pass < 2; pass++) {
                hops.forEach((from, to) -> {
                    String qid = out.get(to);
                    if (qid != null && !from.isEmpty()) out.putIfAbsent(from, qid);
                });
            }
            return out.isEmpty() ? Optional.empty() : Optional.of(Map.copyOf(out));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** {@code haswbstatement:P946=…} search → the single Q-id, or empty. */
    static Optional<String> parseIsinSearch(String body) {
        if (body == null || body.isBlank()) return Optional.empty();
        try {
            for (JsonNode hit : JSON.readTree(body).path("query").path("search")) {
                String title = text(hit, "title");
                if (title.startsWith("Q")) return Optional.of(title);
            }
        } catch (Exception e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** SPARQL facts → the record; an all-empty answer stays empty. */
    static Optional<CompanyFacts> parseFacts(String qid, String body) {
        if (body == null || body.isBlank()) return Optional.empty();
        try {
            JsonNode bindings = JSON.readTree(body).path("results").path("bindings");
            if (!bindings.isArray() || bindings.isEmpty()) return Optional.empty();
            JsonNode row = bindings.get(0);
            CompanyFacts facts = new CompanyFacts(qid,
                    value(row, "isin"), value(row, "lei"), value(row, "employees"),
                    value(row, "revenue"), value(row, "inception"), value(row, "website"));
            return facts.hasAnything() ? Optional.of(facts) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /** SPARQL listings → one entry per exchange, deduplicated on exchange+symbol. */
    static List<ExchangeTicker> parseTickers(String body) {
        if (body == null || body.isBlank()) return List.of();
        List<ExchangeTicker> out = new ArrayList<>();
        try {
            for (JsonNode row : JSON.readTree(body).path("results").path("bindings")) {
                String ticker = value(row, "ticker");
                if (ticker.isEmpty()) continue;
                String uri = value(row, "exchange");
                String exchangeQid = uri.substring(uri.lastIndexOf('/') + 1);
                ExchangeTicker entry = new ExchangeTicker(exchangeQid,
                        value(row, "exchangeLabel"), ticker);
                if (!out.contains(entry)) out.add(entry);
            }
        } catch (Exception e) {
            return List.of();
        }
        return List.copyOf(out);
    }

    /** Pageviews REST → day/views, chronological. */
    static List<PageviewPoint> parsePageviews(String body) {
        if (body == null || body.isBlank()) return List.of();
        List<PageviewPoint> out = new ArrayList<>();
        try {
            for (JsonNode item : JSON.readTree(body).path("items")) {
                String stamp = text(item, "timestamp");
                if (stamp.length() < 8) continue;
                out.add(new PageviewPoint(LocalDate.parse(stamp.substring(0, 8), PV_DAY),
                        item.path("views").asLong(0L)));
            }
        } catch (Exception e) {
            return List.of();
        }
        return List.copyOf(out);
    }

    // ---- helpers -----------------------------------------------------------

    private static String value(JsonNode row, String var) {
        return row.path(var).path("value").asText("").trim();
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? "" : v.asText("").trim();
    }

    /** Article titles travel with underscores for spaces, then percent-encoded. */
    private static String encodeTitle(String title) {
        return encode(title.trim().replace(' ', '_'));
    }

    private static String encode(String raw) {
        return URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String wikiOf(String wiki) {
        return wiki == null || wiki.isBlank() ? DEFAULT_WIKI : wiki.trim().toLowerCase(Locale.ROOT);
    }

    /** Accepts "Q552581" and a full entity URI; anything else is not an item. */
    private static String qidOf(String raw) {
        if (raw == null) return "";
        String id = raw.trim();
        int slash = id.lastIndexOf('/');
        if (slash >= 0) id = id.substring(slash + 1);
        return id.matches("Q\\d+") ? id : "";
    }
}
