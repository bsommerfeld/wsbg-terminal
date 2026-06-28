package de.bsommerfeld.wsbg.terminal.nasdaq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import de.bsommerfeld.wsbg.terminal.source.NewsSource;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
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
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * NASDAQ.com per-symbol news as a {@link NewsSource} for the triangulation pool.
 * Endpoint: {@code www.nasdaq.com/api/news/topic/articlebysymbol?q=<sym>|STOCKS&limit=N}
 * → {@code data.rows[]}. Rides the shared browser-joker {@link WebFetcher} (the
 * www host anchors fine, so the same-origin fetch clears the bot wall).
 */
@Singleton
public class NasdaqNewsClient implements NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(NasdaqNewsClient.class);

    private static final String BASE = "https://www.nasdaq.com/api/news/topic/articlebysymbol?fallback=true&offset=0";
    private static final String HOST = "https://www.nasdaq.com";
    private static final DateTimeFormatter CREATED = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** Successful responses live this long — mirrors Yahoo's news cache, which NASDAQ lacked. */
    private static final long CACHE_TTL_SECONDS = 120;
    /** Always page the max so one fetch serves every caller's {@code limit} (own=6, related=2). */
    private static final int MAX_PAGE = 20;

    private final String userAgent = BrowserUserAgent.random();
    private final WebFetcher fetcher;
    private final Duration timeout = Duration.ofSeconds(10);
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /** A cached news response with the epoch-second it was stored. */
    private record Cached(List<RawNewsItem> items, long storedAt) {}

    public NasdaqNewsClient() {
        this(new DirectWebFetcher());
    }

    @Inject
    public NasdaqNewsClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String sourceName() {
        return "nasdaq";
    }

    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        if (symbol == null || symbol.isBlank() || limit <= 0) return List.of();
        // US-listed plain symbols only (NASDAQ keys on the bare ticker).
        String sym = symbol.trim().toUpperCase(Locale.ROOT);
        if (sym.indexOf('.') >= 0 || sym.indexOf('-') >= 0 || sym.indexOf('=') >= 0 || sym.startsWith("^")) {
            return List.of();
        }
        // Cache by the bare symbol (never by limit): we always fetch the max page and
        // sub-list per caller, so a symbol that is one subject's own ticker (limit 6)
        // AND several others' related ticker (limit 2) hits the network only ONCE per
        // TTL. Without this the same ticker was re-fetched per mention (MU twice, BX
        // five times in one observed tick).
        long now = Instant.now().getEpochSecond();
        Cached hit = cache.get(sym);
        if (hit != null && now - hit.storedAt() < CACHE_TTL_SECONDS) {
            return trim(hit.items(), limit);
        }
        try {
            String url = BASE + "&limit=" + MAX_PAGE
                    + "&q=" + URLEncoder.encode(sym + "|STOCKS", StandardCharsets.UTF_8);
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", userAgent, "Accept", "application/json"), timeout);
            if (resp.status() != 200) {
                LOG.info("[NASDAQ news] {} → HTTP {}", sym, resp.status());
                return List.of(); // a transient failure isn't cached — retry next call
            }
            List<RawNewsItem> items = parse(resp.body(), sym);
            cache.put(sym, new Cached(items, now));
            LOG.info("[NASDAQ news] {} → {} items", sym, items.size());
            return trim(items, limit);
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            LOG.debug("NASDAQ news {} failed: {}", sym, e.getMessage());
            return List.of();
        }
    }

    /** The newest {@code limit} of a cached/just-fetched list, as an unmodifiable view. */
    private static List<RawNewsItem> trim(List<RawNewsItem> items, int limit) {
        return items.size() <= limit ? items : List.copyOf(items.subList(0, limit));
    }

    /** Shape: {@code {"data":{"rows":[{title,url,publisher,created,ago,…}],"totalrecords":N}}}. */
    List<RawNewsItem> parse(String body, String symbol) {
        try {
            JsonNode rows = JSON.readTree(body).path("data").path("rows");
            if (!rows.isArray() || rows.isEmpty()) return List.of();
            List<RawNewsItem> out = new ArrayList<>();
            for (JsonNode r : rows) {
                String title = r.path("title").asText("").trim();
                if (title.isEmpty()) continue;
                String url = r.path("url").asText("").trim();
                if (url.startsWith("/")) url = HOST + url;
                out.add(new RawNewsItem(
                        url.isBlank() ? title : url,            // uuid = url (dedup key)
                        title,
                        r.path("publisher").asText(r.path("primaryTopic").asText("NASDAQ")),
                        url,
                        parseCreated(r.path("created").asText("")),
                        List.of(symbol)));
            }
            return out;
        } catch (Exception e) {
            LOG.debug("NASDAQ news parse failure: {}", e.getMessage());
            return List.of();
        }
    }

    /** "Jun 24, 2026" → that day at 00:00 UTC; unparseable / relative ("ago") → null. */
    private static Instant parseCreated(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s.trim(), CREATED).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (Exception e) {
            return null;
        }
    }
}
