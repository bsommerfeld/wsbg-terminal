package de.bsommerfeld.wsbg.terminal.lemmy;

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
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Fediverse-Community-Sentiment via Lemmy's keyless {@code /api/v3/post/list}
 * — primär die deutsche Diskussions-Community {@code !finanzen@feddit.org}:
 * klein, aber ECHT (~1-2 Posts/Tag, 121 aktive Nutzer/Monat, gepinnt live
 * 2026-07-16). Der Wert ist das Diskussions-Echo, nicht das Volumen — wenn
 * eine deutsche Community einen Titel diskutiert ("IBM-CEO schockiert
 * Aktionäre, Aktie kracht", 8 Kommentare), ist das ein Retail-Stimmungssignal,
 * das keine Newswire trägt. Zweites Bein: {@code !stocks@lemmy.world}
 * (englisch, US-lastig). Der Endpoint antwortet anonym 200 JSON, kein Key,
 * keine Wall (beide Instanzen live geprobt 2026-07-16).
 *
 * <p><b>Firehose, nicht Suche:</b> Lemmy hat keine brauchbare
 * per-Instrument-Adresse, also wird pro Community EIN Listing
 * ({@code sort=New&limit=50}) geholt, geparst und als POOL gecacht
 * (10-min-TTL, ein Fetch pro Community pro TTL, Burst-sicher), und
 * {@link #newsForName} filtert den vereinigten Pool per Präzisionsfilter
 * gegen Titel UND Post-Body (signifikante Namensworte, umlaut-tolerant —
 * Community-Posts nennen das Instrument oft nur im Fließtext).
 * {@link #newsFor} und {@link #newsForIsin} bleiben No-ops: Lemmy taggt
 * weder Ticker noch ISINs.
 *
 * <p><b>Antwortform (gepinnt live 2026-07-16, beide Instanzen):</b> Top-Level
 * {@code {posts: [...], next_page}}; je Eintrag {@code post.name} (= Titel),
 * {@code post.body} (Markdown — kann FEHLEN, leer oder gefüllt sein),
 * {@code post.url} (Linkpost-Ziel, kann {@code null} sein),
 * {@code post.published} (ISO-8601 mit Mikrosekunden + {@code Z}),
 * {@code post.ap_id} (= Permalink; bei föderierten Posts eine FREMDE
 * Instanz-URL wie {@code reddthat.com} — trotzdem der stabile Permalink und
 * damit uuid UND link), {@code creator.name}, {@code counts.comments} und
 * {@code counts.score}. Der gestrippte Body (~500 Zeichen) plus
 * "(n Kommentare)" wird zur Summary; Publisher ist das Community-Label
 * ("Lemmy (!finanzen@feddit.org)").
 */
@Singleton
public class LemmyClient implements NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(LemmyClient.class);

    /** Instance + community pairs this source listens to. */
    static final List<Community> COMMUNITIES = List.of(
            new Community("feddit.org", "finanzen"),
            new Community("lemmy.world", "stocks"));

    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final int LIST_LIMIT = 50;
    private static final int SUMMARY_BODY_CAP = 500;

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Generic words that must never carry the relevance match alone. */
    private static final Set<String> NAME_STOP = Set.of(
            "the", "and", "und", "inc", "incorporated", "corp", "corporation", "co",
            "company", "ag", "se", "plc", "ltd", "limited", "nv", "sa", "spa",
            "holding", "holdings", "group", "international", "aktie", "aktien");

    private final String userAgent = BrowserUserAgent.random();
    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(12);

    /** Per-community pools, each refreshed at most once per TTL. */
    private final Map<Community, CachedPool> pools = new HashMap<>();

    /** One instance + community pair, e.g. {@code !finanzen@feddit.org}. */
    record Community(String instance, String community) {
        String listUrl() {
            return "https://" + instance + "/api/v3/post/list?community_name="
                    + community + "&sort=New&limit=" + LIST_LIMIT;
        }

        String publisher() {
            return "Lemmy (!" + community + "@" + instance + ")";
        }
    }

    /** A pooled post: the emitted item plus the normalised title+body match text. */
    record PooledPost(RawNewsItem item, String matchText) {}

    private record CachedPool(Instant fetchedAt, List<PooledPost> posts) {}

    /** Test/default: plain direct transport. */
    public LemmyClient() {
        this(new DirectWebFetcher());
    }

    /**
     * Production: the {@code @DirectFirst} seam — both instances answer a bare
     * anonymous client 200 with no wall (live-probed 2026-07-16), so this
     * client declares "fine on plain HTTP" through the policy annotation like
     * the other keyless no-wall sources.
     */
    @Inject
    public LemmyClient(
            @de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String sourceName() {
        return "lemmy";
    }

    /** Room opinion, not reported news — rides the sentiment fan, never the press loom. */
    @Override
    public boolean socialSentiment() {
        return true;
    }

    /** No-op: Lemmy tags no tickers — instruments only surface by name. */
    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        return List.of();
    }

    /** No-op: Lemmy tags no ISINs — instruments only surface by name. */
    @Override
    public List<RawNewsItem> newsForIsin(String isin, int limit) {
        return List.of();
    }

    @Override
    public List<RawNewsItem> newsForName(String companyName, int limit) {
        if (companyName == null || companyName.isBlank() || limit <= 0) return List.of();
        Set<String> words = significantWords(companyName);
        if (words.isEmpty()) return List.of();
        List<RawNewsItem> out = new ArrayList<>();
        for (PooledPost post : currentPool()) {
            if (out.size() >= limit) break;
            if (matches(post.matchText(), words)) out.add(post.item());
        }
        return List.copyOf(out);
    }

    /**
     * The unioned per-community pools. Synchronized so a burst of queries makes
     * exactly ONE listing request per community; a failed or non-Lemmy answer
     * is never cached (the next call retries), and an outage keeps the stale
     * pool for that community.
     */
    private synchronized List<PooledPost> currentPool() {
        List<PooledPost> union = new ArrayList<>();
        for (Community community : COMMUNITIES) {
            union.addAll(communityPool(community));
        }
        return union;
    }

    private List<PooledPost> communityPool(Community community) {
        CachedPool cached = pools.get(community);
        if (cached != null && cached.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cached.posts();
        }
        try {
            WebResponse resp = fetcher.fetch(community.listUrl(),
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) {
                List<PooledPost> posts = parse(resp.body(), community);
                if (posts != null) {
                    pools.put(community, new CachedPool(Instant.now(), posts));
                    return posts;
                }
                // A 200 that is not a Lemmy post list (HTML shell, torn JSON)
                // is a miss, never cached — the next call retries.
                LOG.debug("{} answered a 200 that is not a post list — miss",
                        community.publisher());
            } else {
                LOG.debug("{} answered status {}", community.publisher(),
                        resp == null ? "null" : resp.status());
            }
        } catch (Exception e) {
            LOG.debug("{} fetch failed: {}", community.publisher(), e.getMessage());
        }
        return cached == null ? List.of() : cached.posts();
    }

    /**
     * Parses one {@code /api/v3/post/list} answer into pooled posts,
     * unfiltered (relevance is applied per query). Returns {@code null} when
     * the body is not a Lemmy post list at all (so the miss is never cached);
     * garbage inside individual entries is skipped, never thrown.
     * Package-private for tests.
     */
    static List<PooledPost> parse(String body, Community community) {
        if (body == null || body.isBlank()) return null;
        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (Exception e) {
            LOG.debug("{} parse failed: {}", community.publisher(), e.getMessage());
            return null;
        }
        JsonNode posts = root.path("posts");
        if (!posts.isArray()) return null;
        List<PooledPost> out = new ArrayList<>();
        for (JsonNode entry : posts) {
            try {
                PooledPost post = toPost(entry, community);
                if (post != null) out.add(post);
            } catch (Exception e) {
                LOG.debug("{} entry skipped: {}", community.publisher(), e.getMessage());
            }
        }
        return out;
    }

    /** One {@code posts[]} entry → a pooled post, or null when incomplete. */
    private static PooledPost toPost(JsonNode entry, Community community) {
        JsonNode post = entry.path("post");
        String title = post.path("name").asText("").strip();
        // ap_id is the fediverse permalink — the stable identity even when it
        // points at a FOREIGN instance (federated cross-posts, pinned 2026-07-16).
        String apId = post.path("ap_id").asText("").strip();
        if (title.isEmpty() || apId.isEmpty()) return null;
        // body is Markdown and OPTIONAL: missing on federated link posts,
        // empty string on lemmy.world link posts (pinned 2026-07-16).
        String rawBody = post.path("body").asText("");
        int comments = entry.path("counts").path("comments").asInt(0);
        return new PooledPost(
                new RawNewsItem(
                        apId,
                        title,
                        community.publisher(),
                        apId,
                        parsePublished(post.path("published").asText(null)),
                        List.of(),
                        null,
                        summary(rawBody, comments),
                        false),
                normalize(title + " " + rawBody));
    }

    /**
     * The stripped, capped body plus the comment count — the count IS part of
     * the signal (a discussed post outweighs a dropped link). Never null: a
     * bodyless link post still carries "(n Kommentare)".
     */
    static String summary(String markdownBody, int comments) {
        String stripped = stripMarkdown(markdownBody);
        if (stripped.length() > SUMMARY_BODY_CAP) {
            stripped = stripped.substring(0, SUMMARY_BODY_CAP).stripTrailing() + "…";
        }
        String echo = "(" + comments + (comments == 1 ? " Kommentar)" : " Kommentare)");
        return stripped.isEmpty() ? echo : stripped + " " + echo;
    }

    /** Lemmy bodies are Markdown — links, emphasis and quote markers stripped. */
    static String stripMarkdown(String markdown) {
        if (markdown == null) return "";
        return markdown
                .replaceAll("!?\\[([^\\]]*)\\]\\([^)]*\\)", "$1") // [text](url) / images
                .replaceAll("(?m)^\\s{0,3}(?:>|#{1,6})\\s*", "")  // quote/heading markers
                .replaceAll("[*_`~]+", "")                          // emphasis/code fences
                .replaceAll("\\s+", " ")
                .strip();
    }

    /**
     * Lemmy's {@code published} is ISO-8601 with microseconds and a {@code Z}
     * (pinned live 2026-07-16); older instances have emitted the same shape
     * WITHOUT the zone suffix, which is then UTC. Unparseable → null, never a
     * guessed timestamp.
     */
    static Instant parsePublished(String published) {
        if (published == null || published.isBlank()) return null;
        String s = published.strip();
        try {
            return Instant.parse(s);
        } catch (Exception withZone) {
            try {
                return LocalDateTime.parse(s).toInstant(ZoneOffset.UTC);
            } catch (Exception e) {
                return null;
            }
        }
    }

    /** True when title-or-body text carries at least one significant name word. */
    static boolean matches(String matchText, Set<String> nameWords) {
        if (nameWords.isEmpty()) return false;
        for (String w : nameWords) {
            if (matchText.matches("(?s).*\\b" + Pattern.quote(w) + "\\b.*")) return true;
        }
        return false;
    }

    /** Significant (length ≥ 3, non-generic) words of the queried name, umlaut-normalised. */
    static Set<String> significantWords(String name) {
        if (name == null || name.isBlank()) return Set.of();
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
