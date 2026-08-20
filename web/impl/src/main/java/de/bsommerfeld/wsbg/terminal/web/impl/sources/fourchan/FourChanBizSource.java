package de.bsommerfeld.wsbg.terminal.web.impl.sources.fourchan;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.article.SourceOrigin;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Raw US retail sentiment from 4chan via the OFFICIAL public read-only API
 * ({@code a.4cdn.org/<board>/catalog.json}) — three boards (probed and sized
 * 2026-07-16): {@code /biz/} is culturally the closest living relative of WSB
 * (/smg/ "stock market general" runs around the clock with 150-380 replies,
 * next to per-ticker threads), {@code /news/} carries news-link threads with a
 * real finance/econ-policy share, and {@code /g/} contributes tech-stock talk
 * as cheap by-catch (a Nvidia/Apple thread only surfaces when one exists).
 * {@code /pol/} was measured and deliberately left out: 190 active threads an
 * hour with a finance-vocabulary share of ~0.5% — a name match there would
 * mostly glue toxic politics onto a paper. Catalog shape: plain client 200
 * JSON, an array of pages each carrying {@code threads[]} with {@code no}
 * (thread id), {@code sub} (title, OPTIONAL — only ~43% of threads carry one),
 * {@code com} (OP text as HTML with {@code <br>}/{@code <wbr>}/{@code <span
 * class="quote">}/{@code <a>} tags and {@code &#039;}/{@code &amp;}/{@code
 * &gt;}/{@code &quot;} entities, OPTIONAL — image-only OPs omit it),
 * {@code replies}, {@code time} (Unix OP timestamp), {@code last_modified}.
 *
 * <p><b>Content is unfiltered and RAW</b> — irony, hopium, slurs, garbage and
 * the occasional genuine early signal, exactly as posted. The source delivers
 * evidence; the classification is the downstream model's job (house principle:
 * ingestion wide, the AI judges in context). Reply counts ride along in the
 * summary as engagement signal.
 *
 * <p>Pacing note: the API asks for max 1 request per second. The old client
 * carried its own token bucket for that; in the new world the house fetcher's
 * per-host cooldowns pace requests and the source's {@link #interval()} sets
 * the cadence, so no local limiter rides here.
 *
 * <p>Permalink schema: {@code https://boards.4chan.org/<board>/thread/<no>} —
 * doubles as the item's uuid; the publisher names the board ("4chan /biz/").
 */
@Singleton
public final class FourChanBizSource extends AbstractWebSource implements CollectorSource {

    private static final Logger LOG = LoggerFactory.getLogger(FourChanBizSource.class);

    /** The finance-relevant boards, sized live 2026-07-16 (see class doc). */
    private static final List<String> BOARDS = List.of("biz", "news", "g");
    private static final int TITLE_FALLBACK_CHARS = 100;
    private static final int SUMMARY_CHARS = 500;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Duration requestTimeout = Duration.ofSeconds(12);

    @Inject
    public FourChanBizSource(WebFetcher fetcher) {
        super(fetcher);
    }

    @Override
    public String sourceName() {
        return "fourchan-biz";
    }

    /**
     * Direct-first: the official API answers a bare client with no wall
     * (live-probed 2026-07-16).
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

    @Override
    public SourceOrigin origin() {
        return SourceOrigin.of("en", "US");
    }

    /** High-churn boards, cheap API — brisk cadence. */
    @Override
    public FetchInterval interval() {
        return FetchInterval.of(5, 10);
    }

    @Override
    public List<Article> collect() {
        List<Article> union = new ArrayList<>();
        for (String board : BOARDS) {
            String url = "https://a.4cdn.org/" + board + "/catalog.json";
            if (hostCoolingDown(url)) continue;
            try {
                WebResponse resp = get(url,
                        Map.of("Accept", "application/json"), requestTimeout);
                if (resp.status() != 200) {
                    LOG.debug("4chan /{}/ catalog answered status {}", board, resp.status());
                    continue;
                }
                List<Article> threads = parse(resp.body(), board);
                if (threads.isEmpty()) {
                    // A 200 that parses to nothing (HTML shell, torn body) is a
                    // miss, not an empty board.
                    LOG.debug("4chan /{}/ catalog answered a 200 that is not the "
                            + "catalog JSON — miss", board);
                    continue;
                }
                union.addAll(threads);
            } catch (Exception e) {
                LOG.debug("4chan /{}/ catalog fetch failed: {}", board, e.getMessage());
            }
        }
        return union;
    }

    /**
     * Catalog JSON (array of pages, each with {@code threads[]}) →
     * {@link Article}s, unfiltered (relevance is the downstream model's job).
     * Garbage yields empty, never throws. Package-private for tests.
     */
    static List<Article> parse(String json, String board) {
        if (json == null || json.isBlank()) return List.of();
        List<Article> out = new ArrayList<>();
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!root.isArray()) return List.of();
            for (JsonNode page : root) {
                JsonNode threads = page.path("threads");
                if (!threads.isArray()) continue;
                for (JsonNode thread : threads) {
                    Article t = toThread(thread, board);
                    if (t != null) out.add(t);
                }
            }
        } catch (Exception e) {
            LOG.debug("4chan /{}/ catalog parse failed: {}", board, e.getMessage());
            return List.copyOf(out);
        }
        return out;
    }

    /** One catalog thread node → an {@link Article}, or null when unusable. */
    private static Article toThread(JsonNode thread, String board) {
        long no = thread.path("no").asLong(0);
        if (no <= 0) return null;

        String sub = textOrNull(thread, "sub");
        String com = textOrNull(thread, "com");
        String subClean = sub == null ? null : decodeEntities(sub).strip();
        String comClean = com == null ? null : stripHtml(com);
        if (isBlank(subClean) && isBlank(comClean)) return null; // image-only, no text at all

        String title = !isBlank(subClean) ? subClean : truncate(comClean, TITLE_FALLBACK_CHARS);
        int replies = thread.path("replies").asInt(0);
        String repliesSuffix = "(" + replies + " Antworten)";
        String summary = isBlank(comClean)
                ? repliesSuffix
                : truncate(comClean, SUMMARY_CHARS) + " " + repliesSuffix;

        long time = thread.path("time").asLong(0);
        Instant publishedAt = time > 0 ? Instant.ofEpochSecond(time) : null;

        String link = "https://boards.4chan.org/" + board + "/thread/" + no;
        return new Article(
                link, title, "4chan /" + board + "/", link, publishedAt,
                List.of(), null, summary, false);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n == null || !n.isTextual() ? null : n.asText();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max).stripTrailing() + "…";
    }

    /**
     * OP HTML → plain text: {@code <wbr>} (a zero-width URL break) vanishes
     * without a space so URLs stay whole, {@code <br>} becomes a space, all
     * other tags are dropped, entities are decoded, whitespace collapsed.
     */
    static String stripHtml(String html) {
        if (html == null) return null;
        String s = html
                .replaceAll("(?i)<wbr\\s*/?>", "")
                .replaceAll("(?i)<br\\s*/?>", " ")
                .replaceAll("<[^>]+>", "");
        return decodeEntities(s).replaceAll("\\s+", " ").strip();
    }

    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?)([0-9a-fA-F]+);");

    /** The entity set seen live (2026-07-16) plus generic numeric references. */
    static String decodeEntities(String s) {
        if (s == null) return null;
        String out = s
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#039;", "'")
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
        return out.replace("&amp;", "&"); // last, so "&amp;gt;" stays literal "&gt;"
    }
}
