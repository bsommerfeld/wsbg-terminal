package de.bsommerfeld.wsbg.terminal.web.impl.sources.onvista;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.article.SourceOrigin;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.source.AbstractWebSource;
import de.bsommerfeld.wsbg.terminal.web.source.InstrumentSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * onvista's per-instrument press ARCHIVE — the articles finder
 * ({@code api.onvista.de/api/v1/articles/finder/configuration_query}) serves
 * the dated, attributed press history (dpa-AFX, EQS/DGAP corporate news) as
 * plain JSON teasers, newest first. For small caps and pennystocks the
 * COMPLETE multi-year history sits inside the ~1,000-article pagination cap
 * (probed 2026-07-16: artec technologies = 57 articles back 4.7 years in one
 * request; SAP total 3,499, cap reaches ~14 months). Keyless, no bot wall
 * (probed 2026-07-12).
 *
 * <p>Two calls per instrument: {@code /api/v1/instruments/query?searchValue=…}
 * resolves the entity ({@code entityType} + {@code entityValue}) —
 * ISIN-addressed first (never a same-named twin), company name as the
 * fallback — then the finder pages through the archive
 * ({@code entityType=STOCK&entityValue=…&page=…&perPage=100}).
 *
 * <p><b>Rate discipline / terms.</b> onvista's {@code robots.txt} declares
 * {@code Crawl-delay: 20} and its terms forbid automated querying without
 * consent ("Eine automatisierte Abfrage … ist ohne ausdrückliche Einwilligung
 * von onvista in jeglicher Form unzulässig."), backed by § 87a UrhG. The
 * project owner has knowingly cleared this source for sparse use; the note
 * stays so the decision is never mistaken for an oversight. Callers stay
 * sparse and never poll.
 *
 * <p>Transport {@code DIRECT,BROWSER}: the API answers a bare client; the
 * browser joker is the fallback seam.
 */
@Singleton
public class OnvistaNewsSource extends AbstractWebSource implements InstrumentSource {

    private static final Logger LOG = LoggerFactory.getLogger(OnvistaNewsSource.class);

    private static final String QUERY_URL =
            "https://api.onvista.de/api/v1/instruments/query?searchValue=";
    private static final String ARTICLES_URL_FMT =
            "https://api.onvista.de/api/v1/articles/finder/configuration_query"
                    + "?application=WEBSITE&calculateTotal=true&device=DESKTOP"
                    + "&entityType=STOCK&entityValue=%s&page=%d&perPage=100";

    /** The finder paginates to ~1,000 articles per instrument (probed 2026-07-16). */
    private static final int MAX_ARTICLE_PAGES = 10;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Duration requestTimeout = Duration.ofSeconds(12);

    @Inject
    public OnvistaNewsSource(WebFetcher fetcher) {
        super(fetcher);
    }

    @Override
    public String sourceName() {
        return "onvista";
    }

    @Override
    public FetchUtil[] mode() {
        return new FetchUtil[] {FetchUtil.DIRECT, FetchUtil.BROWSER};
    }

    /** A German finance portal - overwhelmingly German-language agency copy. */
    @Override
    public SourceOrigin origin() {
        return SourceOrigin.of("de", "DE");
    }

    /**
     * The instrument's press history, newest first: the entity is resolved
     * ISIN-first (exact, never a same-named twin) with the canonical name as
     * the fallback key, then the finder pages until {@code limit} is filled,
     * a page comes back empty, or the pagination cap is reached. The archive
     * is entity-addressed - a bare symbol answers nothing here, so the ticker
     * key is deliberately unused.
     */
    @Override
    public List<Article> newsFor(ResolvedInstrument instrument, int limit) {
        if (instrument == null || limit <= 0) return List.of();
        try {
            String entity = archiveEntity(
                    instrument.isin().map(i -> i.value()).orElse(null),
                    instrument.name());
            if (entity == null) return List.of();
            List<Article> out = new ArrayList<>();
            for (int page = 0; page < MAX_ARTICLE_PAGES && out.size() < limit; page++) {
                WebResponse resp = get(String.format(ARTICLES_URL_FMT, entity, page),
                        Map.of("Accept", "application/json"), requestTimeout);
                if (resp == null || resp.status() != 200) break;
                JsonNode items = articleArray(JSON.readTree(resp.body()));
                if (items == null || items.isEmpty()) break;
                for (JsonNode item : items) {
                    Article a = toArticle(item, entity);
                    if (a != null) out.add(a);
                    if (out.size() >= limit) break;
                }
            }
            return List.copyOf(out);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("[onvista] archive fan failed: {}", e.getMessage());
            return List.of();
        }
    }

    /** One finder teaser → an {@link Article}, or null when undated/untitled. */
    private static Article toArticle(JsonNode item, String entity) {
        Instant at = parseArticleInstant(item.path("datetimePublication").asText(null));
        if (at == null) return null;
        String headline = item.path("headline").asText(null);
        if (headline == null || headline.isBlank()) return null;
        JsonNode pub = item.path("publisher");
        String publisher = pub.isTextual() ? pub.asText() : pub.path("name").asText("onvista");
        // Live shape (probed 2026-07-16): urls = {"WEBSITE": "<link>"}.
        String url = item.path("urls").path("WEBSITE").asText(null);
        if (url == null) url = firstText(item, "urlArticle", "url", "link");
        return new Article(
                "onvista-" + entity + "-" + at.getEpochSecond()
                        + "-" + Math.abs(headline.hashCode()),
                headline.strip(), publisher, url, at, List.of());
    }

    /** Entity for the archive: ISIN lookup first (exact), name as fallback. */
    private String archiveEntity(String isin, String companyName) throws Exception {
        for (String key : new String[]{isin, companyName}) {
            if (key == null || key.isBlank()) continue;
            WebResponse resp = get(QUERY_URL
                            + URLEncoder.encode(key.trim(), StandardCharsets.UTF_8),
                    Map.of("Accept", "application/json"), requestTimeout);
            if (resp == null || resp.status() != 200) continue;
            String entity = parseStockEntity(resp.body(), key.trim());
            if (entity != null) return entity;
        }
        return null;
    }

    /**
     * Picks the query hit whose key matches AND whose entityType is STOCK,
     * returning its entityValue — or null (miss/garbled). An ISIN key matches
     * the row's {@code isin} exactly; a name key takes the first STOCK row
     * (onvista ranks its own index). Package-private for tests.
     */
    static String parseStockEntity(String body, String key) {
        try {
            JsonNode list = JSON.readTree(body).path("list");
            if (!list.isArray()) return null;
            boolean isinKey = key.matches("[A-Za-z]{2}[A-Za-z0-9]{9}[0-9]");
            for (JsonNode n : list) {
                if (!"STOCK".equalsIgnoreCase(n.path("entityType").asText(""))) continue;
                if (isinKey && !key.equalsIgnoreCase(n.path("isin").asText(""))) continue;
                String v = n.path("entityValue").asText("");
                if (!v.isBlank()) return v;
            }
            return null;
        } catch (Exception e) {
            LOG.debug("[onvista] query parse failure: {}", e.getMessage());
            return null;
        }
    }

    /** The finder's article array - field name parsed defensively. Package-private for tests. */
    static JsonNode articleArray(JsonNode root) {
        for (String field : new String[]{"list", "articles", "items", "data"}) {
            JsonNode n = root.path(field);
            if (n.isArray()) return n;
        }
        Iterator<JsonNode> it = root.elements();
        while (it.hasNext()) {
            JsonNode n = it.next();
            if (n.isArray() && n.size() > 0 && n.get(0).has("headline")) return n;
        }
        return null;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String f : fields) {
            String v = node.path(f).asText(null);
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    /** onvista timestamps are ISO-8601 with an offset; null on anything else. */
    static Instant parseArticleInstant(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return OffsetDateTime.parse(raw).toInstant();
        } catch (Exception e) {
            try {
                return Instant.parse(raw);
            } catch (Exception e2) {
                return null;
            }
        }
    }
}
