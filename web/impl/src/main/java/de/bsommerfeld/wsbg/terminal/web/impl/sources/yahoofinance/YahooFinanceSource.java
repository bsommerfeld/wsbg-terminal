package de.bsommerfeld.wsbg.terminal.web.impl.sources.yahoofinance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.source.AbstractWebSource;
import de.bsommerfeld.wsbg.terminal.web.source.InstrumentSource;
import de.bsommerfeld.wsbg.terminal.web.source.SearchEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Yahoo Finance news via the unauthenticated {@code /v1/finance/search}
 * endpoint (quotes + news in one shot; this source reads the news array).
 *
 * <p>The endpoint is undocumented but stable in practice — yfinance and a
 * dozen other libraries depend on it. No crumb/cookie dance is required,
 * just a browser-shaped User-Agent (the transport's own). The richer
 * {@code /v10/quoteSummary} and {@code /v7/quote} endpoints are deliberately
 * avoided — since Yahoo's 2024 lockdown they hard-require a cookie+crumb
 * session and 401 on every unauthenticated call; where the house needs
 * scalar quote fields, {@code /v8/finance/chart}'s {@code meta} block carries
 * them without that handshake. That approach is the contract of every Yahoo
 * leg, and it stays.
 *
 * <p>Both doors of the new world: as an {@link InstrumentSource} it asks the
 * search endpoint with the resolved SYMBOL as the query — the symbol itself
 * arrives as the top quote and the symbol's news in the {@code news} array
 * (Yahoo's search is symbol-addressed; the resolver already turned names into
 * symbols upstream). As a {@link SearchEngine} it carries the free research
 * query verbatim.
 *
 * <p>Transport {@code BROWSER,DIRECT} — Yahoo's IP/429 block is cleared by
 * the embedded browser with a plain-HTTP fallback. The old client's own
 * rate-limit circuit breaker (first 429 opens, everything short-circuits for
 * 90 s) is GONE: the house fetcher owns the host cooldown after a 429, and
 * this source stays polite by skipping outright while {@code
 * hostCoolingDown()} says Yahoo is resting.
 *
 * <p>Politeness: the parsed result is cached per query for 2 minutes — the
 * lookup → publish path hits the same symbol twice within a tick, and Yahoo
 * should see one request for it.
 */
@Singleton
public final class YahooFinanceSource extends AbstractWebSource
        implements InstrumentSource, SearchEngine {

    private static final Logger LOG = LoggerFactory.getLogger(YahooFinanceSource.class);

    private static final String SEARCH_URL = "https://query2.finance.yahoo.com/v1/finance/search";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration CACHE_TTL = Duration.ofSeconds(120);

    /** Per-query politeness cache: parsed, uncapped news items. */
    private final Map<String, CachedResult> cache = new ConcurrentHashMap<>();

    private record CachedResult(Instant fetchedAt, List<Article> items) {}

    @Inject
    public YahooFinanceSource(WebFetcher fetcher) {
        super(fetcher);
    }

    @Override
    public String sourceName() {
        return "yahoo";
    }

    /** Browser first: the embedded browser clears Yahoo's IP/429 block. */
    @Override
    public FetchUtil[] mode() {
        return new FetchUtil[] {FetchUtil.BROWSER, FetchUtil.DIRECT};
    }

    /**
     * The pull-by-symbol path: Yahoo's search is symbol-addressed, so the
     * resolved home symbol is the one key this backend can address — an
     * instrument without one answers empty instead of guessing.
     */
    @Override
    public List<Article> newsFor(ResolvedInstrument instrument, int limit) {
        if (limit <= 0 || instrument.ticker().isEmpty()) return List.of();
        return query(instrument.ticker().get().value(), limit);
    }

    /** Free research query, verbatim — the caller phrased it. */
    @Override
    public List<Article> search(String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) return List.of();
        return query(query.trim(), limit);
    }

    private List<Article> query(String query, int limit) {
        // The limit is part of the key: the reply is server-capped by
        // newsCount, so a small answer must not starve a larger ask.
        String cacheKey = query.toLowerCase(Locale.ROOT) + "|" + limit;
        CachedResult cached = cache.get(cacheKey);
        if (cached != null && cached.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cap(cached.items(), limit);
        }
        // A recent 429 put Yahoo in the fetcher's cooldown — a comment-heavy
        // burst fans into dozens of searches, and once Yahoo 429s one it will
        // 429 the rest; skipping here is what the old breaker bought.
        if (hostCoolingDown(SEARCH_URL)) return List.of();
        try {
            String url = SEARCH_URL
                    + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                    + "&quotesCount=1"
                    + "&newsCount=" + Math.max(1, limit)
                    + "&enableFuzzyQuery=false"
                    + "&quotesQueryId=tss_match_phrase_query"
                    + "&newsQueryId=news_cie_vespa";
            WebResponse resp = get(url, Map.of("Accept", "application/json"), REQUEST_TIMEOUT);
            if (resp.status() != 200) {
                LOG.warn("Yahoo search '{}' returned HTTP {}", query, resp.status());
                return List.of();
            }
            List<Article> items = parseNews(resp.body());
            cache.put(cacheKey, new CachedResult(Instant.now(), items));
            return cap(items, limit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            LOG.warn("Yahoo search '{}' failed: {}", query, e.getMessage());
            return List.of();
        }
    }

    private static List<Article> cap(List<Article> items, int limit) {
        return items.size() <= limit ? items : List.copyOf(items.subList(0, limit));
    }

    /**
     * The {@code news} array of a {@code /v1/finance/search} reply →
     * {@link Article}s: Yahoo's own uuid, publisher, permalink, publish time
     * and the related-ticker tags. Malformed JSON yields empty, never throws.
     * Package-private for tests.
     */
    static List<Article> parseNews(String body) {
        try {
            List<Article> news = new ArrayList<>();
            JsonNode newsNode = JSON.readTree(body).path("news");
            if (newsNode.isArray()) {
                for (JsonNode n : newsNode) {
                    String title = text(n, "title");
                    if (title.isEmpty()) continue;
                    long ts = n.path("providerPublishTime").asLong(0L);
                    List<String> related = new ArrayList<>();
                    JsonNode rt = n.path("relatedTickers");
                    if (rt.isArray()) {
                        for (JsonNode r : rt) {
                            String s = r.asText("");
                            if (!s.isEmpty()) related.add(s);
                        }
                    }
                    news.add(new Article(
                            text(n, "uuid"),
                            title,
                            text(n, "publisher"),
                            text(n, "link"),
                            ts > 0 ? Instant.ofEpochSecond(ts) : null,
                            Collections.unmodifiableList(related)));
                }
            }
            return Collections.unmodifiableList(news);
        } catch (Exception e) {
            LOG.warn("Failed to parse Yahoo search response: {}", e.getMessage());
            return List.of();
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) return "";
        return v.asText("");
    }
}
