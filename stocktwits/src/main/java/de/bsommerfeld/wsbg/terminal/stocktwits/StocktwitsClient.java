package de.bsommerfeld.wsbg.terminal.stocktwits;

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

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * US retail sentiment from Stocktwits' per-symbol message streams
 * ({@code api.stocktwits.com/api/2/streams/symbol/<SYM>.json}) — the one
 * social venue that ships a MACHINE-READABLE mood label: users tag their own
 * posts Bullish or Bearish ({@code entities.sentiment.basic}), so the mood
 * signal arrives pre-classified instead of inferred.
 *
 * <p><b>Transport: the JOKER carries this source.</b> The API needs no key,
 * but Cloudflare fronts it with a JS challenge (plain client 403 with
 * {@code cf-mitigated: challenge}, probed 2026-07-16) — so this client rides
 * the standard BROWSER-FIRST chain: the hidden CEF tab anchors on the API
 * origin, Chromium solves the challenge during warmup, the clearance cookie
 * lives in the shared store, and the in-page {@code fetch()} returns the raw
 * JSON. The direct transport stays the chain's fallback. No transport code
 * was changed for this — the challenge-tolerant warmup poll predates it.
 *
 * <p><b>Addressing is symbol-only, US shapes only:</b> a symbol with an
 * exchange suffix ({@code RHM.DE}) is a NO-OP — cutting the suffix would
 * query an unrelated US ticker under the same letters (precision over
 * recall); German names reach the room through the other sentiment legs.
 * Yahoo's class separator maps to Stocktwits' ({@code BRK-B} → {@code
 * BRK.B}). An unknown symbol answers 404 — a definitive empty, cached.
 *
 * <p>Response shape (pinned from a REAL archived answer, Wayback snapshot of
 * 2025-05-26 — the live wall blocks bare probes): top level {@code symbol},
 * {@code cursor} ({@code more}/{@code since}/{@code max}), {@code messages[]}
 * with {@code id}, {@code body} (HTML-entity-escaped text), {@code created_at}
 * (ISO-8601 {@code Z}), {@code user.username}, {@code
 * entities.sentiment.basic} = {@code "Bullish"}/{@code "Bearish"} or the
 * whole sentiment node {@code null}. Permalink schema:
 * {@code https://stocktwits.com/<username>/message/<id>}. Re-pin against the
 * first live joker answer if the parse ever comes back empty on a 200.
 */
@Singleton
public class StocktwitsClient implements NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(StocktwitsClient.class);

    private static final String STREAM_URL_PREFIX =
            "https://api.stocktwits.com/api/2/streams/symbol/";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final int MAX_CACHE_ENTRIES = 64;
    private static final int TITLE_MAX = 120;

    /** Stocktwits symbols: letters/digits plus the dot class separator. */
    private static final Pattern SYMBOL_SHAPE = Pattern.compile("[A-Z0-9]{1,12}(\\.[A-Z])?");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String userAgent = BrowserUserAgent.random();
    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(20);

    /** Per-symbol TTL cache, bounded LRU — the fetch is joker-carried, spare it. */
    private final Map<String, CachedStream> cache = new LinkedHashMap<>(32, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CachedStream> eldest) {
            return size() > MAX_CACHE_ENTRIES;
        }
    };

    private record CachedStream(Instant fetchedAt, List<RawNewsItem> items) {}

    /** Test/default: plain direct transport. */
    public StocktwitsClient() {
        this(new DirectWebFetcher());
    }

    /**
     * Production: the UNQUALIFIED browser-first chain — Cloudflare walls the
     * bare client (403 challenge, probed 2026-07-16), the hidden CEF tab
     * passes it; direct stays the fallback. Deliberately NOT {@code
     * @DirectFirst}: the direct leg never answers here, going joker-first
     * spares one doomed request per refresh.
     */
    @Inject
    public StocktwitsClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String sourceName() {
        return "stocktwits";
    }

    /** Room opinion, not reported news — rides the sentiment fan, never the press loom. */
    @Override
    public boolean socialSentiment() {
        return true;
    }

    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        String sym = streamSymbol(symbol);
        if (sym == null || limit <= 0) return List.of();
        List<RawNewsItem> items = cachedStream(sym);
        return items.size() <= limit ? items : List.copyOf(items.subList(0, limit));
    }

    /** No-op: the venue is symbol-keyed; a name query has no exact address here. */
    @Override
    public List<RawNewsItem> newsForName(String companyName, int limit) {
        return List.of();
    }

    /** No-op: Stocktwits knows tickers, not ISINs. */
    @Override
    public List<RawNewsItem> newsForIsin(String isin, int limit) {
        return List.of();
    }

    /**
     * The Stocktwits stream symbol for a house (Yahoo-style) symbol, or null
     * when the paper has no US address here: exchange suffixes are NOT cut
     * (the same letters would hit an unrelated US ticker), only the share
     * class separator is translated ({@code BRK-B} → {@code BRK.B}).
     */
    static String streamSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return null;
        String s = symbol.strip().toUpperCase(Locale.ROOT);
        if (s.contains(".")) return null; // exchange-suffixed (RHM.DE) — not a US address
        s = s.replace('-', '.');
        return SYMBOL_SHAPE.matcher(s).matches() ? s : null;
    }

    private List<RawNewsItem> cachedStream(String sym) {
        synchronized (cache) {
            CachedStream cached = cache.get(sym);
            if (cached != null && cached.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
                return cached.items();
            }
        }
        List<RawNewsItem> fetched = fetchStream(sym);
        if (fetched == null) {
            // Transport failure or garbage — never cached; serve stale if any.
            synchronized (cache) {
                CachedStream cached = cache.get(sym);
                return cached == null ? List.of() : cached.items();
            }
        }
        synchronized (cache) {
            cache.put(sym, new CachedStream(Instant.now(), fetched));
        }
        return fetched;
    }

    /**
     * One stream fetch: a parsed list (possibly empty — 404 means "no such
     * symbol", a definitive empty), or null for a miss that must not be
     * cached (transport failure, or a 200 that is not the stream JSON — the
     * Cloudflare challenge page is exactly such a soft 200).
     */
    private List<RawNewsItem> fetchStream(String sym) {
        try {
            WebResponse resp = fetcher.fetch(STREAM_URL_PREFIX + sym + ".json",
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp == null) return null;
            if (resp.status() == 404) return List.of();
            if (resp.status() != 200) {
                LOG.debug("Stocktwits stream {} answered status {}", sym, resp.status());
                return null;
            }
            List<RawNewsItem> items = parse(resp.body());
            if (items == null) {
                LOG.debug("Stocktwits stream {} answered a 200 that is not the stream "
                        + "JSON (challenge shell?) — treating as a miss, not caching", sym);
                return null;
            }
            return items;
        } catch (Exception e) {
            LOG.debug("Stocktwits stream {} fetch failed: {}", sym, e.getMessage());
            return null;
        }
    }

    /**
     * Stream JSON → items, newest first as delivered. Returns null when the
     * body is not the stream shape (garbage/challenge shell — the caller
     * must not cache that); an empty {@code messages[]} is a valid empty.
     * Package-private for tests.
     */
    static List<RawNewsItem> parse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonNode root = MAPPER.readTree(json);
            JsonNode messages = root.path("messages");
            if (!messages.isArray()) return null;
            List<RawNewsItem> out = new ArrayList<>(messages.size());
            for (JsonNode msg : messages) {
                RawNewsItem item = toItem(msg);
                if (item != null) out.add(item);
            }
            return out;
        } catch (Exception e) {
            LOG.debug("Stocktwits stream parse failed: {}", e.getMessage());
            return null;
        }
    }

    /** One message node → an item, or null when unusable. */
    private static RawNewsItem toItem(JsonNode msg) {
        long id = msg.path("id").asLong(0);
        String body = msg.path("body").asText("");
        if (id <= 0 || body.isBlank()) return null;
        String text = decodeEntities(body).replaceAll("\\s+", " ").strip();
        if (text.isBlank()) return null;

        // The user's OWN mood tag leads the text — that label IS the value here.
        String sentiment = msg.path("entities").path("sentiment").path("basic").asText("");
        String tagged = sentiment.isBlank() ? text : "(" + sentiment + ") " + text;

        String username = msg.path("user").path("username").asText("");
        String link = username.isBlank()
                ? "https://stocktwits.com/message/" + id
                : "https://stocktwits.com/" + username + "/message/" + id;
        Instant at = parseCreatedAt(msg.path("created_at").asText(null));
        String title = tagged.length() <= TITLE_MAX
                ? tagged : tagged.substring(0, TITLE_MAX).stripTrailing() + "…";
        return new RawNewsItem(
                "stocktwits:" + id,
                title,
                username.isBlank() ? "Stocktwits" : "Stocktwits (@" + username + ")",
                link,
                at,
                List.of(),
                null,
                tagged,
                false);
    }

    /** ISO-8601 with Z (pinned shape); unparseable → null, never guessed. */
    static Instant parseCreatedAt(String createdAt) {
        if (createdAt == null || createdAt.isBlank()) return null;
        try {
            return Instant.parse(createdAt.strip());
        } catch (Exception e) {
            return null;
        }
    }

    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?)([0-9a-fA-F]+);");

    /** The entity set seen in the pinned answer plus generic numeric references. */
    static String decodeEntities(String s) {
        if (s == null) return null;
        String out = s
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replace("&nbsp;", " ");
        Matcher m = NUMERIC_ENTITY.matcher(out);
        if (m.find()) {
            StringBuilder sb = new StringBuilder();
            m.reset();
            while (m.find()) {
                try {
                    int cp = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
                    m.appendReplacement(sb, Matcher.quoteReplacement(
                            new String(Character.toChars(cp))));
                } catch (Exception e) {
                    m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
                }
            }
            m.appendTail(sb);
            out = sb.toString();
        }
        return out.replace("&amp;", "&");
    }
}
