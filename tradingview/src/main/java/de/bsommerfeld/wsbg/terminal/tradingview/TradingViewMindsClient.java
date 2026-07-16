package de.bsommerfeld.wsbg.terminal.tradingview;

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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Ticker-Talk from TradingView Minds — the per-symbol social stream
 * ({@code /api/v1/minds/?symbol=<EXCHANGE:SYM>}), keyless JSON, no
 * cookie/no key (live-probed 2026-07-16, plain client 200). These are
 * SOCIAL POSTS, not articles: retail chatter, entry/exit calls, chart
 * links — sentiment material in the Reddit vein, never publishable
 * facts. The aggregator gets them as {@link RawNewsItem}s whose
 * publisher names the posting user.
 *
 * <p><b>Miss behaviour (pinned live 2026-07-16):</b> an unknown or
 * wrong-exchange symbol ({@code NYSE:AAPL}, {@code FOO:BARBAZ}) never
 * errors — the endpoint answers 200 with
 * {@code {"results":[],"meta":{"symbols_info":{}}}}. Only content tells
 * a hit from a miss, so the exchange fallback keys off EMPTY results,
 * not off a status.
 *
 * <p><b>Symbol addressing:</b> house symbols are Yahoo-style, TradingView
 * wants {@code EXCHANGE:SYM}. {@code *.DE} maps to {@code XETR:}; a naked
 * (US) symbol tries {@code NASDAQ:} first, then {@code NYSE:} on empty —
 * the working venue is remembered per symbol so the fallback fires once,
 * not per fetch. Other venue suffixes ({@code .PA}, {@code .L}, …) are an
 * honest no-op: this client only vouches for the mappings it has proven.
 * (A bare symbol also resolves server-side, but unreliably — live probe:
 * bare {@code SAP.DE} resolved to a {@code TRADENATION:} CFD shell with
 * zero minds — so the explicit exchange mapping stays.)
 *
 * <p><b>Text (pinned live 2026-07-16):</b> the post body ships twice —
 * a flat {@code text} string and a structured {@code text_ast} (root →
 * children of plain strings and {@code {type:"symbol"|"url"}} nodes).
 * The flat rendering is inconsistent (US posts render a symbol node as
 * {@code $AAPL}, XETR posts as a quoted {@code "XETR:RHM" RHM}), so the
 * clear text is rendered from {@code text_ast} here — symbol nodes emit
 * {@code params.text} (the bare ticker), url nodes {@code params.url} —
 * and the flat {@code text} is only the fallback. Each result also
 * carries a direct permalink in {@code url}
 * ({@code …/symbols/<EXCH-SYM>/minds/?mind=<uid>}) — that is the link.
 *
 * <p>Transport is {@code @DirectFirst}: currently no wall at all, but
 * TradingView is Cloudflare-fronted, so a captcha/challenge wall may
 * appear any day — if it does, the shared browser-joker chain is the
 * fallback seam.
 */
@Singleton
public class TradingViewMindsClient implements NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(TradingViewMindsClient.class);

    private static final String API_URL = "https://www.tradingview.com/api/v1/minds/?symbol=";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final int MAX_CACHED_SYMBOLS = 64;
    private static final int TITLE_MAX = 120;
    private static final String PUBLISHER_PREFIX = "TradingView Minds";

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String userAgent = BrowserUserAgent.random();
    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(10);

    /** Per HOUSE symbol: the parsed minds, refreshed at most once per TTL. Bounded LRU. */
    private final Map<String, CachedMinds> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedMinds> eldest) {
            return size() > MAX_CACHED_SYMBOLS;
        }
    };

    /**
     * Per HOUSE symbol: the TradingView symbol that actually answered with
     * minds — remembered beyond the item TTL so the NASDAQ→NYSE fallback
     * fires once per symbol, not on every refetch. Bounded LRU.
     */
    private final Map<String, String> resolvedTvSymbol = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
            return size() > MAX_CACHED_SYMBOLS;
        }
    };

    private record CachedMinds(Instant fetchedAt, List<RawNewsItem> items) {}

    /** Test/default: plain direct transport. */
    public TradingViewMindsClient() {
        this(new DirectWebFetcher());
    }

    /**
     * Production: the {@code @DirectFirst} seam — the endpoint answers a bare
     * client keyless with no wall (live-probed 2026-07-16), so this client
     * declares "fine on plain HTTP" like the other keyless no-wall sources.
     * Captcha risk: TradingView sits behind Cloudflare; should a challenge
     * wall appear, the shared browser chain is the fallback.
     */
    @Inject
    public TradingViewMindsClient(
            @de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String sourceName() {
        return "tradingview-minds";
    }

    /** Room opinion, not reported news — rides the sentiment fan, never the press loom. */
    @Override
    public boolean socialSentiment() {
        return true;
    }

    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        if (symbol == null || symbol.isBlank() || limit <= 0) return List.of();
        String house = symbol.trim().toUpperCase(Locale.ROOT);
        List<String> candidates = tvSymbolCandidates(house);
        if (candidates.isEmpty()) return List.of(); // unproven venue — honest no-op, no fetch
        return cap(mindsFor(house, candidates), limit);
    }

    /** Minds are symbol-addressed — a company name means nothing to the endpoint. */
    @Override
    public List<RawNewsItem> newsForName(String companyName, int limit) {
        return List.of();
    }

    /** Minds are symbol-addressed — the endpoint has no ISIN surface. */
    @Override
    public List<RawNewsItem> newsForIsin(String isin, int limit) {
        return List.of();
    }

    /**
     * House (Yahoo-style) symbol → TradingView {@code EXCHANGE:SYM} candidates,
     * in try-order. Empty means "venue unproven — do not fetch".
     * Package-private for tests.
     */
    static List<String> tvSymbolCandidates(String house) {
        if (house.indexOf(':') >= 0) return List.of(house); // already TradingView-style
        int dot = house.indexOf('.');
        if (dot >= 0) {
            String base = house.substring(0, dot);
            String suffix = house.substring(dot);
            if (".DE".equals(suffix) && !base.isEmpty()) return List.of("XETR:" + base);
            return List.of(); // .PA, .L, … — unproven mappings stay a no-op
        }
        return List.of("NASDAQ:" + house, "NYSE:" + house);
    }

    /**
     * The TTL-cached minds for one house symbol. Synchronized so a burst makes
     * exactly ONE request round; a transport failure is never cached (the next
     * call retries, an outage serves the stale entry), while a clean empty
     * answer from every candidate IS cached — the miss shape is a 200 with
     * empty {@code results}, and re-asking it every tick would hammer the API.
     */
    private synchronized List<RawNewsItem> mindsFor(String house, List<String> candidates) {
        CachedMinds cached = cache.get(house);
        if (cached != null && cached.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cached.items();
        }

        // The remembered venue goes first so the exchange fallback fires once.
        String remembered = resolvedTvSymbol.get(house);
        List<String> order = new ArrayList<>();
        if (remembered != null) order.add(remembered);
        for (String c : candidates) {
            if (!c.equals(remembered)) order.add(c);
        }

        boolean allAnsweredClean = true;
        for (String tvSymbol : order) {
            List<RawNewsItem> items = fetchMinds(tvSymbol);
            if (items == null) { // transport failure / garbage — retry next call
                allAnsweredClean = false;
                continue;
            }
            if (!items.isEmpty()) {
                resolvedTvSymbol.put(house, tvSymbol);
                cache.put(house, new CachedMinds(Instant.now(), items));
                return items;
            }
        }
        if (allAnsweredClean) {
            // Every candidate answered the pinned empty-results miss — cache it.
            cache.put(house, new CachedMinds(Instant.now(), List.of()));
            return List.of();
        }
        return cached == null ? List.of() : cached.items(); // outage: stale over empty
    }

    /**
     * One minds fetch. Returns the parsed items ({@code []} = the pinned
     * clean empty-results miss), or {@code null} for a transport failure or
     * a non-JSON body — the caller must not cache that.
     */
    private List<RawNewsItem> fetchMinds(String tvSymbol) {
        try {
            WebResponse resp = fetcher.fetch(
                    API_URL + URLEncoder.encode(tvSymbol, StandardCharsets.UTF_8),
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp == null || resp.status() != 200) {
                LOG.debug("TradingView minds for {} answered status {}",
                        tvSymbol, resp == null ? "null" : resp.status());
                return null;
            }
            return parse(resp.body());
        } catch (Exception e) {
            LOG.debug("TradingView minds fetch failed for {}: {}", tvSymbol, e.getMessage());
            return null;
        }
    }

    /**
     * The minds JSON → {@link RawNewsItem}s. Garbage (HTML shells, torn JSON,
     * a body without a {@code results} array) yields {@code null} = "not a
     * minds answer"; a well-shaped answer with zero results yields the empty
     * list = the pinned miss. Package-private for tests.
     */
    static List<RawNewsItem> parse(String body) {
        if (body == null || body.isBlank()) return null;
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (Exception e) {
            return null;
        }
        JsonNode results = root.path("results");
        if (!results.isArray()) return null; // a 200 that is not a minds answer
        List<RawNewsItem> out = new ArrayList<>();
        for (JsonNode r : results) {
            RawNewsItem item = toItem(r);
            if (item != null) out.add(item);
        }
        return out;
    }

    /** One minds result → a {@link RawNewsItem}, or null when incomplete. */
    private static RawNewsItem toItem(JsonNode r) {
        String uid = r.path("uid").asText("").trim();
        if (uid.isEmpty()) return null;
        String text = renderPlainText(r.path("text_ast"));
        if (text == null || text.isBlank()) {
            text = r.path("text").asText("").trim(); // flat-text fallback
        }
        if (text.isBlank()) return null;
        String username = r.path("author").path("username").asText("").trim();
        String publisher = username.isEmpty()
                ? PUBLISHER_PREFIX : PUBLISHER_PREFIX + " (" + username + ")";
        String link = r.path("url").asText("").trim(); // the direct post permalink
        if (link.isEmpty()) return null;
        return new RawNewsItem(
                uid,
                makeTitle(text),
                publisher,
                link,
                parseCreated(r.path("created").asText(null)),
                List.of(),
                null,
                text,
                false);
    }

    /**
     * {@code text_ast} → clear text: string children verbatim, symbol nodes
     * as their bare ticker ({@code params.text}), url nodes as the URL.
     * Unknown node shapes are skipped, never a crash. Null on no text.
     * Package-private for tests.
     */
    static String renderPlainText(JsonNode ast) {
        if (ast == null || ast.isMissingNode()) return null;
        StringBuilder sb = new StringBuilder();
        appendNode(ast, sb, 0);
        String text = sb.toString().strip();
        return text.isEmpty() ? null : text;
    }

    private static void appendNode(JsonNode node, StringBuilder sb, int depth) {
        if (depth > 16 || node == null) return; // torn/hostile nesting stays bounded
        if (node.isTextual()) {
            sb.append(node.asText());
            return;
        }
        if (!node.isObject()) return;
        String type = node.path("type").asText("");
        JsonNode params = node.path("params");
        switch (type) {
            case "symbol" -> {
                String t = params.path("text").asText("");
                sb.append(t.isEmpty() ? params.path("symbol").asText("") : t);
            }
            case "url" -> {
                String u = params.path("url").asText("");
                sb.append(u.isEmpty() ? params.path("linkText").asText("") : u);
            }
            default -> {
                for (JsonNode child : node.path("children")) {
                    appendNode(child, sb, depth + 1);
                }
            }
        }
    }

    /**
     * First ~{@value #TITLE_MAX} chars of the clear text as the headline —
     * newlines collapsed, cut at a word boundary with an ellipsis.
     * Package-private for tests.
     */
    static String makeTitle(String text) {
        String flat = text.replaceAll("\\s+", " ").strip();
        if (flat.length() <= TITLE_MAX) return flat;
        int cut = flat.lastIndexOf(' ', TITLE_MAX);
        if (cut < TITLE_MAX / 2) cut = TITLE_MAX; // one giant token — hard cut
        return flat.substring(0, cut).stripTrailing() + "…";
    }

    /**
     * {@code created} is ISO-8601 with microseconds and a numeric offset
     * ({@code 2026-07-16T02:21:52.942629+00:00}) — parsed as an offset
     * datetime; unparseable yields null, never a guessed timestamp.
     * Package-private for tests.
     */
    static Instant parseCreated(String created) {
        if (created == null || created.isBlank()) return null;
        try {
            return OffsetDateTime.parse(created.trim()).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    private static List<RawNewsItem> cap(List<RawNewsItem> items, int limit) {
        return items.size() <= limit ? items : List.copyOf(items.subList(0, limit));
    }
}
