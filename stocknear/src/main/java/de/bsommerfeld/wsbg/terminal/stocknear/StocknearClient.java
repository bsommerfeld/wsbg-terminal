package de.bsommerfeld.wsbg.terminal.stocknear;

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

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Stocknear as a FALLBACK leg for US coverage - deliberately narrow.
 *
 * <p><b>Role.</b> The house heatmap and the cage's own mention counter are the
 * backbone; this client is the second opinion that fills US gaps (owner
 * decision 2026-08-02). It carries exactly two surfaces:
 * <ul>
 *   <li>the SECTOR/STOCK HEATMAP tree - sector nodes with equity leaves,
 *       weight and performance per node, over seven time windows;</li>
 *   <li>the REDDIT MENTION aggregate across five subreddits and four windows.</li>
 * </ul>
 * Everything else Stocknear shows is a Financial Modeling Prep wrapper we
 * either have directly or have better (its Fear&amp;Greed comes from the very
 * CNN endpoint our own fetcher already knows).
 *
 * <p><b>Transport.</b> Every stocknear.com path sits behind a Cloudflare
 * managed challenge - a bare client gets 403 "Just a moment…" even on
 * {@code /}. This client therefore rides the standard browser-first
 * {@link WebFetcher} chain, whose hidden CEF tab resolves the interstitial
 * during warmup. Only GET surfaces are used: the SSR pages carry their payload
 * inline, so the POST-only {@code /api/heatmap} endpoint (which the narrow
 * {@link WebFetcher} contract could not call anyway) is never needed.
 *
 * <p><b>Cadence.</b> One refresh per {@link #CACHE_TTL} at most. The site has
 * no app-level rate limit on these paths, but Cloudflare scores bots on
 * request volume and a fallback source must never be the loudest client in the
 * house.
 *
 * <p><b>Terms.</b> Stocknear's terms forbid bypassing their protections and
 * scripted bulk extraction; the owner weighed this and scoped the source to a
 * low-frequency fallback rather than a backbone. Do not widen the surface or
 * the cadence without revisiting that decision.
 *
 * <p><b>Shape provenance.</b> The payload shapes below are taken from
 * Stocknear's own AGPL-licensed frontend (github.com/stocknear/frontend,
 * {@code src/routes/stocks/heatmap/+page.svelte} type declarations and
 * {@code src/routes/reddit-tracker/+page.svelte}), read 2026-08-02. The
 * embedding of that payload in the SSR HTML is version-dependent, so
 * {@link #extractJsonObject} locates it by marker key and brace balance rather
 * than by any fixed wrapper - a SvelteKit upgrade must not silently blind this
 * client. Live acceptance through the JCEF joker is still outstanding.
 */
@Singleton
public class StocknearClient {

    private static final Logger LOG = LoggerFactory.getLogger(StocknearClient.class);

    static final String HEATMAP_URL = "https://stocknear.com/stocks/heatmap";
    static final String ETF_HEATMAP_URL = "https://stocknear.com/etf/heatmap";
    static final String REDDIT_TRACKER_URL = "https://stocknear.com/reddit-tracker?subreddit=%s";
    static final String REDDIT_POSTS_URL =
            "https://stocknear.com/api/reddit-tracker-posts?subreddit=%s&ticker=%s&period=%s";

    /** Index proxies the heatmap can be drawn for. */
    public static final Set<String> HEATMAP_INDICES = Set.of("SPY", "DIA", "QQQ");

    /** Windows the heatmap accepts. */
    public static final Set<String> HEATMAP_PERIODS =
            Set.of("1D", "1W", "1M", "3M", "6M", "1Y", "3Y");

    /**
     * Subreddits the tracker covers. Note this is Stocknear's list, not ours -
     * our own cage (r/wallstreetbetsGER) is counted in-house and is not here.
     */
    public static final Set<String> SUBREDDITS =
            Set.of("wallstreetbets", "valueinvesting", "stocks", "investing", "stockmarket");

    /**
     * Mention windows. Caveat from Stocknear's own cron: {@code oneDay} is
     * really a TWO-day bucket ({@code period_list = [2,7,30,90]}), so never
     * present it as "today".
     */
    public static final Set<String> PERIODS =
            Set.of("oneDay", "oneWeek", "oneMonth", "threeMonths");

    private static final Duration CACHE_TTL = Duration.ofMinutes(15);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String userAgent = BrowserUserAgent.random();
    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(30);

    /** Test/default: plain direct transport (which the live site will 403). */
    public StocknearClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the standard browser-first chain - see class javadoc. */
    @Inject
    public StocknearClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    // ------------------------------------------------------------------
    // heatmap
    // ------------------------------------------------------------------

    /**
     * The sector→stock performance tree for one index proxy and window.
     * Returns an empty list on a challenge, an outage or a shape change -
     * never a partial tree that would render as a wrong map.
     */
    public List<HeatmapNode> heatmap(String index, String period) {
        if (!HEATMAP_INDICES.contains(index) || !HEATMAP_PERIODS.contains(period)) {
            return List.of();
        }
        String html = get(HEATMAP_URL);
        return html == null ? List.of() : parseHeatmap(html);
    }

    /** The same tree for ETFs rather than single stocks. */
    public List<HeatmapNode> etfHeatmap() {
        String html = get(ETF_HEATMAP_URL);
        return html == null ? List.of() : parseHeatmap(html);
    }

    /**
     * Pulls the heatmap payload out of the SSR page. Located by the
     * {@code colorValue} marker and brace balance, so it survives a change in
     * how SvelteKit wraps it. Package-private for tests.
     */
    static List<HeatmapNode> parseHeatmap(String html) {
        if (html == null || html.isBlank()) return List.of();
        String json = extractJsonObject(html, "\"colorValue\"");
        if (json == null) return List.of();
        List<HeatmapNode> out = new ArrayList<>();
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode data = root.isArray() ? root : root.path("data");
            if (!data.isArray()) return List.of();
            for (JsonNode n : data) {
                String id = str(n, "id");
                if (id == null) continue;
                JsonNode custom = n.path("custom");
                out.add(new HeatmapNode(
                        id,
                        str(n, "name"),
                        str(n, "parent"),
                        dbl(n, "value"),
                        dbl(n, "colorValue"),
                        str(custom, "symbol"),
                        dbl(custom, "marketCap"),
                        dbl(custom, "currentPrice"),
                        dbl(custom, "referencePrice"),
                        str(custom, "referenceDate")));
            }
        } catch (Exception e) {
            LOG.warn("Unparseable Stocknear heatmap payload: {}", e.getMessage());
            return List.of();
        }
        return out;
    }

    // ------------------------------------------------------------------
    // reddit mentions
    // ------------------------------------------------------------------

    /**
     * Mention counts per ticker for one subreddit and window.
     *
     * <p>Read this as a NEIGHBOUR signal, not as a measurement: Stocknear
     * extracts tickers with a bare {@code \$([A-Z]+)} regex against a
     * whitelist, drops every post under 50 comments, and scores sentiment with
     * VADER over the whole post rather than per ticker. Our own counter is
     * strictly better for our cage; this is here for rooms we do not read.
     */
    public List<MentionCount> mentions(String subreddit, String period) {
        if (!SUBREDDITS.contains(subreddit) || !PERIODS.contains(period)) return List.of();
        String html = get(String.format(REDDIT_TRACKER_URL,
                URLEncoder.encode(subreddit, StandardCharsets.UTF_8)));
        return html == null ? List.of() : parseMentions(html, period);
    }

    /** Package-private for tests. */
    static List<MentionCount> parseMentions(String html, String period) {
        if (html == null || html.isBlank() || period == null) return List.of();
        String json = extractJsonObject(html, "\"" + period + "\"");
        if (json == null) return List.of();
        List<MentionCount> out = new ArrayList<>();
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode rows = root.path(period);
            if (!rows.isArray()) return List.of();
            for (JsonNode n : rows) {
                String symbol = str(n, "symbol");
                if (symbol == null) continue;
                out.add(new MentionCount(
                        symbol.toUpperCase(Locale.ROOT),
                        str(n, "name"),
                        n.path("count").asInt(0),
                        dbl(n, "marketCap"),
                        dbl(n, "price"),
                        dbl(n, "changesPercentage"),
                        str(n, "sentiment")));
            }
        } catch (Exception e) {
            LOG.warn("Unparseable Stocknear mention payload: {}", e.getMessage());
            return List.of();
        }
        return out;
    }

    /**
     * The posts behind one ticker's mention count - a plain JSON GET, the only
     * surface here that is not an SSR page. Currently unused; stands ready.
     */
    public List<MentionPost> posts(String subreddit, String ticker, String period) {
        if (!SUBREDDITS.contains(subreddit) || !PERIODS.contains(period)
                || ticker == null || ticker.isBlank()) {
            return List.of();
        }
        String body = get(String.format(REDDIT_POSTS_URL,
                URLEncoder.encode(subreddit, StandardCharsets.UTF_8),
                URLEncoder.encode(ticker.trim().toUpperCase(Locale.ROOT), StandardCharsets.UTF_8),
                period));
        return body == null ? List.of() : parsePosts(body);
    }

    /** Package-private for tests. */
    static List<MentionPost> parsePosts(String json) {
        if (json == null || json.isBlank()) return List.of();
        List<MentionPost> out = new ArrayList<>();
        try {
            JsonNode arr = MAPPER.readTree(json);
            if (!arr.isArray()) return List.of();
            for (JsonNode n : arr) {
                String title = str(n, "title");
                if (title == null) continue;
                String permalink = str(n, "permalink");
                long created = n.path("created_utc").asLong(0);
                out.add(new MentionPost(
                        title,
                        str(n, "author"),
                        permalink == null ? null : "https://reddit.com" + permalink,
                        created > 0 ? Instant.ofEpochSecond(created) : null,
                        n.path("num_comments").asInt(0),
                        dbl(n, "upvote_ratio"),
                        str(n, "link_flair_text"),
                        n.path("ticker_count").asInt(0)));
            }
        } catch (Exception e) {
            LOG.warn("Unparseable Stocknear post payload: {}", e.getMessage());
            return List.of();
        }
        return out;
    }

    // ------------------------------------------------------------------
    // plumbing
    // ------------------------------------------------------------------

    private String get(String url) {
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", userAgent,
                            "Accept", "text/html,application/json,*/*"),
                    requestTimeout);
            if (resp == null) return null;
            if (resp.status() == 403 || looksLikeChallenge(resp.body())) {
                LOG.debug("Stocknear served a Cloudflare challenge for {}", url);
                return null;
            }
            if (resp.status() == 200) return resp.body();
            LOG.debug("Stocknear {} answered status {}", url, resp.status());
        } catch (Exception e) {
            LOG.debug("Stocknear {} failed: {}", url, e.getMessage());
        }
        return null;
    }

    /** A challenge page is an HTTP 200 - detect it by content, not by status. */
    static boolean looksLikeChallenge(String body) {
        if (body == null || body.isEmpty()) return false;
        String head = body.length() > 2000 ? body.substring(0, 2000) : body;
        return head.contains("Just a moment") || head.contains("cf-browser-verification")
                || head.contains("challenge-platform");
    }

    /**
     * Finds the smallest balanced JSON object or array around {@code marker}.
     * Walks back to the enclosing brace, then forward counting depth while
     * respecting string literals and escapes - HTML around it is irrelevant,
     * which is the point: the wrapper changes, the payload does not.
     * Package-private for tests.
     */
    static String extractJsonObject(String html, String marker) {
        int at = html.indexOf(marker);
        while (at >= 0) {
            int start = findEnclosingStart(html, at);
            if (start >= 0) {
                String candidate = balancedFrom(html, start);
                if (candidate != null) return candidate;
            }
            at = html.indexOf(marker, at + marker.length());
        }
        return null;
    }

    /** Scans back for the '{' that opens the object holding the marker. */
    private static int findEnclosingStart(String html, int markerAt) {
        int depth = 0;
        for (int i = markerAt; i >= 0; i--) {
            char c = html.charAt(i);
            if (c == '}' || c == ']') depth++;
            else if (c == '{' || c == '[') {
                if (depth == 0) {
                    // Prefer the outer container when this object sits in an array.
                    int outer = i;
                    for (int j = i - 1; j >= 0 && j > i - 64; j--) {
                        char p = html.charAt(j);
                        if (p == '[') return j;
                        if (!Character.isWhitespace(p)) break;
                    }
                    return outer;
                }
                depth--;
            }
        }
        return -1;
    }

    /** Reads a balanced object/array starting at {@code start}, or null. */
    private static String balancedFrom(String html, int start) {
        char open = html.charAt(start);
        char close = open == '{' ? '}' : ']';
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < html.length(); i++) {
            char c = html.charAt(i);
            if (inString) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') inString = false;
                continue;
            }
            if (c == '"') inString = true;
            else if (c == open) depth++;
            else if (c == close) {
                depth--;
                if (depth == 0) return html.substring(start, i + 1);
            }
        }
        return null;
    }

    private static String str(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    private static Double dbl(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || !v.isNumber()) return null;
        return v.asDouble();
    }

    // ------------------------------------------------------------------
    // records
    // ------------------------------------------------------------------

    /**
     * One cell of the treemap. Sector nodes have a null {@code parent} and no
     * {@code symbol}; equity leaves carry both. {@code weight} is the sizing
     * value (market cap share), {@code performancePercent} the colour.
     */
    public record HeatmapNode(
            String id,
            String name,
            String parent,
            Double weight,
            Double performancePercent,
            String symbol,
            Double marketCap,
            Double currentPrice,
            Double referencePrice,
            String referenceDate) {

        /** True for a sector container rather than a single instrument. */
        public boolean isSector() {
            return parent == null || parent.isBlank();
        }
    }

    /** One ticker's mention aggregate. {@code sentiment} is a closed VADER label. */
    public record MentionCount(
            String symbol,
            String name,
            int mentions,
            Double marketCap,
            Double price,
            Double changePercent,
            String sentiment) {}

    /** One post behind a mention count. */
    public record MentionPost(
            String title,
            String author,
            String url,
            Instant createdAt,
            int comments,
            Double upvoteRatio,
            String flair,
            int tickerMentions) {}
}
