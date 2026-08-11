package de.bsommerfeld.wsbg.terminal.web.impl.sources.lemmy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import de.bsommerfeld.wsbg.terminal.web.schedule.FetchInterval;
import de.bsommerfeld.wsbg.terminal.web.source.AbstractWebSource;
import de.bsommerfeld.wsbg.terminal.web.source.CollectorSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fediverse-Community-Sentiment via Lemmy's keyless {@code /api/v3/post/list}
 * — primär die deutsche Diskussions-Community {@code !finanzen@feddit.org}:
 * klein, aber ECHT (~1-2 Posts/Tag, 121 aktive Nutzer/Monat, gepinnt live
 * 2026-07-16). Der Wert ist das Diskussions-Echo, nicht das Volumen — wenn
 * eine deutsche Community einen Titel diskutiert ("IBM-CEO schockiert
 * Aktionäre, Aktie kracht", 8 Kommentare), ist das ein Retail-Stimmungssignal,
 * das keine Newswire trägt. Zweites Bein: {@code !stocks@lemmy.world}
 * (englisch, US-lastig). Der Endpoint antwortet anonym 200 JSON, kein Key,
 * keine Wall (beide Instanzen live geprobt 2026-07-16). JSON, kein Feed —
 * darum ein handgeschriebener Sammler neben den kuratierten Feed-Zeilen.
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
public final class LemmySource extends AbstractWebSource implements CollectorSource {

    private static final Logger LOG = LoggerFactory.getLogger(LemmySource.class);

    /** Instance + community pairs this source listens to. */
    static final List<Community> COMMUNITIES = List.of(
            new Community("feddit.org", "finanzen"),
            new Community("lemmy.world", "stocks"));

    private static final int LIST_LIMIT = 50;
    private static final int SUMMARY_BODY_CAP = 500;

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Duration requestTimeout = Duration.ofSeconds(12);

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

    @Inject
    public LemmySource(WebFetcher fetcher) {
        super(fetcher);
    }

    @Override
    public String sourceName() {
        return "lemmy";
    }

    /**
     * Direct-first: both instances answer a bare anonymous client 200 with no
     * wall (live-probed 2026-07-16).
     */
    @Override
    public FetchUtil[] mode() {
        return new FetchUtil[] {FetchUtil.DIRECT, FetchUtil.BROWSER};
    }

    /** Room opinion, not reported news — rides the sentiment fan, never the press loom. */
    @Override
    public boolean socialSentiment() {
        return true;
    }

    /** Tiny communities, low volume — unhurried cadence. */
    @Override
    public FetchInterval interval() {
        return FetchInterval.of(10, 20);
    }

    @Override
    public List<Article> collect() {
        List<Article> union = new ArrayList<>();
        for (Community community : COMMUNITIES) {
            if (hostCoolingDown(community.listUrl())) continue;
            try {
                WebResponse resp = get(community.listUrl(),
                        Map.of("Accept", "application/json"), requestTimeout);
                if (resp.status() != 200) {
                    LOG.debug("{} answered status {}", community.publisher(), resp.status());
                    continue;
                }
                List<Article> posts = parse(resp.body(), community);
                if (posts == null) {
                    // A 200 that is not a Lemmy post list (HTML shell, torn
                    // JSON) is a miss, not an empty community.
                    LOG.debug("{} answered a 200 that is not a post list — miss",
                            community.publisher());
                    continue;
                }
                union.addAll(posts);
            } catch (Exception e) {
                LOG.debug("{} fetch failed: {}", community.publisher(), e.getMessage());
            }
        }
        return union;
    }

    /**
     * Parses one {@code /api/v3/post/list} answer into articles, unfiltered
     * (relevance is the downstream model's job). Returns {@code null} when
     * the body is not a Lemmy post list at all (so the caller can tell a miss
     * from an empty community); garbage inside individual entries is skipped,
     * never thrown. Package-private for tests.
     */
    static List<Article> parse(String body, Community community) {
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
        List<Article> out = new ArrayList<>();
        for (JsonNode entry : posts) {
            try {
                Article post = toPost(entry, community);
                if (post != null) out.add(post);
            } catch (Exception e) {
                LOG.debug("{} entry skipped: {}", community.publisher(), e.getMessage());
            }
        }
        return out;
    }

    /** One {@code posts[]} entry → an {@link Article}, or null when incomplete. */
    private static Article toPost(JsonNode entry, Community community) {
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
        return new Article(
                apId,
                title,
                community.publisher(),
                apId,
                parsePublished(post.path("published").asText(null)),
                List.of(),
                null,
                summary(rawBody, comments),
                false);
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
}
