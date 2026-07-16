package de.bsommerfeld.wsbg.terminal.bluesky;

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
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Global social sentiment from Bluesky via the keyless AppView post search
 * ({@code app.bsky.feed.searchPosts}) — anonymous JSON, no OAuth wall, the
 * open-firehose counterweight to the ticket-gated Reddit API. <b>These are
 * SOCIAL POSTS, not articles:</b> what comes back is retail chatter, activist
 * noise and bot-bridged headlines around an instrument — sentiment texture,
 * never a fact source. The mapping says so: title = the post's first line,
 * summary = the full post text, publisher = {@code "Bluesky (@handle)"}.
 *
 * <p><b>Host quirk (pinned live 2026-07-16):</b> the officially documented
 * public AppView host {@code public.api.bsky.app} answers {@code searchPosts}
 * from Germany with a WAF 403 — {@code api.bsky.app} serves the identical
 * XRPC anonymously. The host therefore deliberately deviates from the docs
 * (see {@link #HOST}).
 *
 * <p><b>This source is a SEARCH, not a firehose:</b> unlike the pool sources
 * (PR Newswire, Ariva) there is no one feed to cache — every query is its own
 * request, so a small per-query TTL cache (5 min, bounded) keeps bursts to
 * one fetch per query without a global pool.
 *
 * <p>{@link #newsFor} searches the CASHTAG ({@code $NVDA}; for suffixed
 * symbols like {@code RHM.DE} the base part before the dot) — cashtag hits
 * are precise by construction. {@link #newsForName} searches the name's
 * significant words, but Bluesky's search matches loosely (Swedish
 * "ABB-aktien" chatter answers a German query), so hits are additionally
 * pushed through the house precision filter against {@code record.text}:
 * only posts that actually carry a significant word of the queried name
 * survive — precision over recall. {@link #newsForIsin} stays a no-op:
 * nobody prints ISINs in social posts.
 *
 * <p>Answer shape (pinned live 2026-07-16): {@code posts[]} with
 * {@code uri} ({@code at://<did>/app.bsky.feed.post/<rkey>}), {@code cid},
 * {@code author{handle, displayName}}, {@code record{text, createdAt}} and
 * engagement counts. {@code record.createdAt} arrives in THREE shapes
 * (client-written, not normalised): micros + {@code +00:00} offset, plain
 * seconds + offset, and millis + {@code Z}. Bridged accounts
 * ({@code *.web.brid.gy}) post EMPTY text — such posts are skipped.
 */
@Singleton
public class BlueskyNewsClient implements NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(BlueskyNewsClient.class);

    /**
     * NOT the documented {@code public.api.bsky.app}: that host WAF-403s
     * {@code searchPosts} from German egress (live-probed 2026-07-16), while
     * {@code api.bsky.app} answers the same XRPC anonymously.
     */
    static final String HOST = "https://api.bsky.app";

    private static final String SEARCH_PATH = "/xrpc/app.bsky.feed.searchPosts";
    private static final String POST_LINK_BASE = "https://bsky.app/profile/";

    /** How many posts one search fetches — queries cap on return, the cache keeps the rest. */
    private static final int SEARCH_FETCH_LIMIT = 25;

    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final int CACHE_MAX_QUERIES = 64;
    private static final int TITLE_MAX_CHARS = 120;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Generic words that must never carry the text-relevance match alone. */
    private static final Set<String> NAME_STOP = Set.of(
            "the", "and", "und", "inc", "incorporated", "corp", "corporation", "co",
            "company", "ag", "se", "plc", "ltd", "limited", "nv", "sa", "spa",
            "holding", "holdings", "group", "international", "aktie", "aktien");

    private final String userAgent = BrowserUserAgent.random();
    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(10);

    /** Overridable in tests (e.g. to assert the built query URL); defaults to the live host. */
    String searchUrl = HOST + SEARCH_PATH;

    private record CachedQuery(Instant fetchedAt, List<RawNewsItem> items) {}

    /**
     * query → parsed posts, insertion-bounded so a wide watchlist can't grow
     * the map without limit. All access under the client's monitor.
     */
    private final LinkedHashMap<String, CachedQuery> cache =
            new LinkedHashMap<>(32, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedQuery> eldest) {
                    return size() > CACHE_MAX_QUERIES;
                }
            };

    /** Test/default: plain direct transport. */
    public BlueskyNewsClient() {
        this(new DirectWebFetcher());
    }

    /**
     * Production: the {@code @DirectFirst} seam — the AppView answers a bare
     * client with no wall (live-probed 2026-07-16), so this client declares
     * "fine on plain HTTP" through the policy annotation like the other
     * keyless no-wall sources.
     */
    @Inject
    public BlueskyNewsClient(
            @de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String sourceName() {
        return "bluesky";
    }

    /** Room opinion, not reported news — rides the sentiment fan, never the press loom. */
    @Override
    public boolean socialSentiment() {
        return true;
    }

    /** Cashtag search: {@code $NVDA}; suffixed symbols use the base before the dot. */
    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        if (limit <= 0) return List.of();
        String cashtag = cashtagFor(symbol);
        if (cashtag == null) return List.of();
        return cap(search(cashtag), limit);
    }

    /** No-op: nobody prints ISINs in social posts — Bluesky is cashtag/name territory. */
    @Override
    public List<RawNewsItem> newsForIsin(String isin, int limit) {
        return List.of();
    }

    @Override
    public List<RawNewsItem> newsForName(String companyName, int limit) {
        if (companyName == null || companyName.isBlank() || limit <= 0) return List.of();
        String query = nameQuery(companyName);
        Set<String> words = significantWords(companyName);
        if (query.isEmpty() || words.isEmpty()) return List.of();
        List<RawNewsItem> out = new ArrayList<>();
        for (RawNewsItem item : search(query)) {
            // Bluesky's search matches loosely (Swedish "aktien" chatter answers
            // a German name) — precision over recall: the post's TEXT must
            // actually carry a significant word of the queried name.
            if (textMatches(item.summary(), words)) out.add(item);
        }
        return cap(out, limit);
    }

    /**
     * The per-query TTL cache. Synchronized so a burst on the same query makes
     * exactly ONE search request; a garbage or failed answer is never cached
     * (the next call retries), and an outage serves the stale entry.
     */
    private synchronized List<RawNewsItem> search(String query) {
        CachedQuery cached = cache.get(query);
        if (cached != null && cached.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cached.items();
        }
        try {
            WebResponse resp = fetcher.fetch(
                    searchUrl + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                            + "&sort=latest&limit=" + SEARCH_FETCH_LIMIT,
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) {
                List<RawNewsItem> items = parse(resp.body());
                if (items != null) {
                    cache.put(query, new CachedQuery(Instant.now(), items));
                    return items;
                }
                // A 200 that isn't a searchPosts answer (HTML shell, torn body)
                // is a miss, never cached — only content proves anything.
                LOG.debug("Bluesky answered a 200 that is not a searchPosts body "
                        + "for '{}' — treating as a miss, not caching", query);
            } else {
                LOG.debug("Bluesky search for '{}' answered status {}",
                        query, resp == null ? "null" : resp.status());
            }
        } catch (Exception e) {
            LOG.debug("Bluesky search for '{}' failed: {}", query, e.getMessage());
        }
        return cached == null ? List.of() : cached.items();
    }

    private static List<RawNewsItem> cap(List<RawNewsItem> items, int limit) {
        return items.size() <= limit ? items : List.copyOf(items.subList(0, limit));
    }

    /**
     * {@code posts[]} → {@link RawNewsItem}s, or {@code null} when the body is
     * not a searchPosts answer (garbage/HTML — the caller must not cache that).
     * Empty-text posts (bridged accounts) and post-shaped fragments missing
     * uri/handle are skipped, never thrown on. Package-private for tests.
     */
    static List<RawNewsItem> parse(String body) {
        if (body == null || body.isBlank()) return null;
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (Exception e) {
            return null;
        }
        if (root == null || !root.path("posts").isArray()) return null;
        List<RawNewsItem> out = new ArrayList<>();
        for (JsonNode post : root.path("posts")) {
            try {
                RawNewsItem item = toItem(post);
                if (item != null) out.add(item);
            } catch (Exception e) {
                LOG.debug("Bluesky post fragment skipped: {}", e.getMessage());
            }
        }
        return out;
    }

    /** One {@code posts[]} entry → a {@link RawNewsItem}, or null when unusable. */
    private static RawNewsItem toItem(JsonNode post) {
        String uri = post.path("uri").asText("").trim();
        String handle = post.path("author").path("handle").asText("").trim();
        String text = post.path("record").path("text").asText("").trim();
        // Bridged accounts (*.web.brid.gy) post empty text — nothing to say, skip.
        if (uri.isEmpty() || handle.isEmpty() || text.isEmpty()) return null;
        String link = postLink(uri, handle);
        if (link == null) return null;
        return new RawNewsItem(
                uri,
                titleOf(text),
                "Bluesky (@" + handle + ")",
                link,
                parseCreatedAt(post.path("record").path("createdAt").asText(null)),
                List.of(),
                null,
                text,
                false);
    }

    /**
     * {@code at://<did>/app.bsky.feed.post/<rkey>} → the public web permalink
     * {@code https://bsky.app/profile/<handle>/post/<rkey>}; null when the
     * at-uri doesn't carry an rkey. Package-private for tests.
     */
    static String postLink(String atUri, String handle) {
        if (atUri == null || handle == null || handle.isBlank()) return null;
        int slash = atUri.lastIndexOf('/');
        if (slash < 0 || slash == atUri.length() - 1) return null;
        String rkey = atUri.substring(slash + 1).trim();
        if (rkey.isEmpty()) return null;
        return POST_LINK_BASE + handle + "/post/" + rkey;
    }

    /**
     * A post has no headline — the title is its first line, capped at
     * {@value #TITLE_MAX_CHARS} chars (cut at a word boundary, ellipsis).
     * Package-private for tests.
     */
    static String titleOf(String text) {
        String firstLine = text;
        int nl = text.indexOf('\n');
        if (nl >= 0) firstLine = text.substring(0, nl);
        // Live quirk: posts carry non-breaking spaces (U+00A0) that
        // String.strip() doesn't count as whitespace — the title mustn't.
        firstLine = firstLine.replace(' ', ' ').strip();
        if (firstLine.length() <= TITLE_MAX_CHARS) return firstLine;
        String cut = firstLine.substring(0, TITLE_MAX_CHARS - 1);
        int space = cut.lastIndexOf(' ');
        if (space > TITLE_MAX_CHARS / 2) cut = cut.substring(0, space);
        return cut.stripTrailing() + "…";
    }

    /**
     * {@code record.createdAt} is CLIENT-written, not normalised — three shapes
     * pinned live 2026-07-16: micros + {@code +00:00}, seconds + {@code +00:00},
     * millis + {@code Z}. Unparseable → null, never a guessed timestamp.
     * Package-private for tests.
     */
    static Instant parseCreatedAt(String createdAt) {
        if (createdAt == null || createdAt.isBlank()) return null;
        try {
            return OffsetDateTime.parse(createdAt.trim()).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * {@code "RHM.DE"} → {@code "$RHM"}: the base part before the dot,
     * uppercased. Null (= no-op) when the base isn't a plain A-Z0-9 token —
     * a hyphenated share class or an index symbol is not a cashtag.
     * Package-private for tests.
     */
    static String cashtagFor(String symbol) {
        if (symbol == null || symbol.isBlank()) return null;
        String base = symbol.trim();
        int dot = base.indexOf('.');
        if (dot >= 0) base = base.substring(0, dot);
        base = base.toUpperCase(Locale.ROOT);
        if (base.isEmpty() || !base.matches("[A-Z0-9]+")) return null;
        return "$" + base;
    }

    /**
     * The name's significant words as the search query, original casing kept
     * (the umlaut-normalised forms are for MATCHING, not for searching).
     * Package-private for tests.
     */
    static String nameQuery(String companyName) {
        String cut = companyName.contains(",")
                ? companyName.substring(0, companyName.indexOf(',')) : companyName;
        List<String> words = new ArrayList<>();
        for (String w : cut.trim().split("\\s+")) {
            String norm = normalize(w).replaceAll("[^a-z0-9]", "");
            if (norm.length() >= 3 && !NAME_STOP.contains(norm)) words.add(w);
        }
        return String.join(" ", words);
    }

    /** True when the post text carries at least one significant word of the queried name. */
    static boolean textMatches(String text, Set<String> nameWords) {
        if (text == null || nameWords.isEmpty()) return false;
        String t = normalize(text);
        for (String w : nameWords) {
            if (t.matches("(?s).*\\b" + Pattern.quote(w) + "\\b.*")) return true;
        }
        return false;
    }

    /** Significant (length ≥ 3, non-generic) words of the queried name, umlaut-normalised. */
    static Set<String> significantWords(String name) {
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String w : normalize(name).split("[^a-z0-9]+")) {
            if (w.length() >= 3 && !NAME_STOP.contains(w)) out.add(w);
        }
        return out;
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
    }
}
