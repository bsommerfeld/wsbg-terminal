package de.bsommerfeld.wsbg.terminal.fourchan;

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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 * <p><b>This source is a FIREHOSE, not a search:</b> one catalog answer
 * carries every live thread on its board (~150-200), so each board is fetched
 * once, parsed and cached as a per-board POOL shared across all queries with a
 * 5-minute TTL; a token bucket paces the refresh fetches to the API's rule of
 * max 1 request per second (three boards back to back would otherwise burst).
 * Addressing is TWO-track: {@link #newsFor} word-boundary-matches the
 * ticker symbol (as {@code SYMBOL} or {@code $SYMBOL}, case-insensitive)
 * against title + OP text — but ONLY for symbols of length &gt;= 2:
 * one-letter US tickers (F, C, T, ...) are ordinary English words/letters and
 * would match essentially every thread, so they stay a no-op.
 * {@link #newsForName} applies the house precision filter (significant words,
 * umlaut-tolerant) against title + OP text. {@link #newsForIsin} is a no-op —
 * anons don't post ISINs.
 *
 * <p><b>Content is unfiltered and RAW</b> — irony, hopium, slurs, garbage and
 * the occasional genuine early signal, exactly as posted. The source delivers
 * evidence; the classification is the downstream model's job (house principle:
 * ingestion wide, the AI judges in context). Reply counts ride along in the
 * summary as engagement signal.
 *
 * <p>Permalink schema: {@code https://boards.4chan.org/<board>/thread/<no>} —
 * doubles as the item's uuid; the publisher names the board ("4chan /biz/").
 */
@Singleton
public class FourChanBizClient implements NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(FourChanBizClient.class);

    /** The finance-relevant boards, sized live 2026-07-16 (see class doc). */
    private static final List<String> BOARDS = List.of("biz", "news", "g");
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);
    private static final int TITLE_FALLBACK_CHARS = 100;
    private static final int SUMMARY_CHARS = 500;

    /** Generic words that must never carry the name-relevance match alone. */
    private static final Set<String> NAME_STOP = Set.of(
            "the", "and", "und", "inc", "incorporated", "corp", "corporation", "co",
            "company", "ag", "se", "plc", "ltd", "limited", "nv", "sa", "spa",
            "holding", "holdings", "group", "international", "aktie", "aktien");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String userAgent = BrowserUserAgent.random();
    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(12);

    /** Paces catalog refreshes to the API's 1-request-per-second rule. */
    private final de.bsommerfeld.wsbg.terminal.source.net.TokenBucketRateLimiter
            catalogPacer = new de.bsommerfeld.wsbg.terminal.source.net.TokenBucketRateLimiter(1, 1);

    /** The shared pools: one parsed catalog per board, refreshed at most once per TTL. */
    private final Map<String, CachedPool> pools = new java.util.concurrent.ConcurrentHashMap<>();

    private record CachedPool(Instant fetchedAt, List<BizThread> threads) {}

    /**
     * One catalog thread: the emitted item plus the FULL stripped text
     * (sub + com, uncapped) the symbol/name matchers search — the capped
     * summary alone would lose matches deep in a long OP.
     */
    record BizThread(RawNewsItem item, String searchText) {}

    /** Test/default: plain direct transport. */
    public FourChanBizClient() {
        this(new DirectWebFetcher());
    }

    /**
     * Production: the {@code @DirectFirst} seam — the official API answers a
     * bare client with no wall (live-probed 2026-07-16), so this client
     * declares "fine on plain HTTP" through the policy annotation like the
     * other keyless no-wall sources.
     */
    @Inject
    public FourChanBizClient(
            @de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String sourceName() {
        return "fourchan-biz";
    }

    /** Room opinion, not reported news — rides the sentiment fan, never the press loom. */
    @Override
    public boolean socialSentiment() {
        return true;
    }

    /**
     * Word-boundary match of {@code SYMBOL} / {@code $SYMBOL} against title +
     * OP text. Exchange suffixes are cut ("BRK.B" queries as "BRK"); symbols
     * shorter than 2 characters after that are a NO-OP without a fetch —
     * one-letter US tickers (F, C, T) are ordinary words and would match
     * essentially every thread on the board.
     */
    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        String bare = bareSymbol(symbol);
        if (bare.length() < 2 || limit <= 0) return List.of();
        Pattern p = symbolPattern(bare);
        List<RawNewsItem> out = new ArrayList<>();
        for (BizThread t : currentPool()) {
            if (p.matcher(t.searchText()).find()) out.add(t.item());
        }
        return cap(out, limit);
    }

    /** No-op: anons don't post ISINs. */
    @Override
    public List<RawNewsItem> newsForIsin(String isin, int limit) {
        return List.of();
    }

    /** House precision filter (significant words) against title + OP text. */
    @Override
    public List<RawNewsItem> newsForName(String companyName, int limit) {
        if (companyName == null || companyName.isBlank() || limit <= 0) return List.of();
        Set<String> words = significantWords(companyName);
        if (words.isEmpty()) return List.of();
        List<RawNewsItem> out = new ArrayList<>();
        for (BizThread t : currentPool()) {
            if (textMatches(t.searchText(), words)) out.add(t.item());
        }
        return cap(out, limit);
    }

    /**
     * The union of all board pools, each TTL-cached. Synchronized so a burst
     * of queries makes at most ONE catalog request per board, and the token
     * bucket paces those to the API's 1-req/s rule; a non-JSON or failed
     * answer is never cached (the next call retries), and an outage keeps
     * serving that board's stale pool without touching the others.
     */
    private synchronized List<BizThread> currentPool() {
        List<BizThread> union = new ArrayList<>();
        for (String board : BOARDS) union.addAll(boardPool(board));
        return union;
    }

    private List<BizThread> boardPool(String board) {
        CachedPool cached = pools.get(board);
        if (cached != null && cached.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cached.threads();
        }
        try {
            catalogPacer.acquire();
            WebResponse resp = fetcher.fetch(
                    "https://a.4cdn.org/" + board + "/catalog.json",
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) {
                List<BizThread> threads = parse(resp.body(), board);
                if (threads.isEmpty()) {
                    // A 200 that parses to nothing (HTML shell, torn body) is a
                    // miss, not an empty board — never cached.
                    LOG.debug("4chan /{}/ catalog answered a 200 that is not the "
                            + "catalog JSON — treating as a miss, not caching", board);
                    return stale(cached);
                }
                pools.put(board, new CachedPool(Instant.now(), threads));
                return threads;
            }
            LOG.debug("4chan /{}/ catalog answered status {}", board,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            LOG.debug("4chan /{}/ catalog fetch failed: {}", board, e.getMessage());
        }
        return stale(cached);
    }

    /** An outage serves the stale pool rather than an empty answer. */
    private static List<BizThread> stale(CachedPool cached) {
        return cached == null ? List.of() : cached.threads();
    }

    private static List<RawNewsItem> cap(List<RawNewsItem> items, int limit) {
        return items.size() <= limit ? items : List.copyOf(items.subList(0, limit));
    }

    /**
     * Catalog JSON (array of pages, each with {@code threads[]}) →
     * {@link BizThread}s, unfiltered (the pool caches the whole board;
     * relevance is applied per query). Garbage yields empty, never throws.
     * Package-private for tests.
     */
    static List<BizThread> parse(String json, String board) {
        if (json == null || json.isBlank()) return List.of();
        List<BizThread> out = new ArrayList<>();
        try {
            JsonNode root = MAPPER.readTree(json);
            if (!root.isArray()) return List.of();
            for (JsonNode page : root) {
                JsonNode threads = page.path("threads");
                if (!threads.isArray()) continue;
                for (JsonNode thread : threads) {
                    BizThread t = toThread(thread, board);
                    if (t != null) out.add(t);
                }
            }
        } catch (Exception e) {
            LOG.debug("4chan /{}/ catalog parse failed: {}", board, e.getMessage());
            return List.copyOf(out);
        }
        return out;
    }

    /** One catalog thread node → a {@link BizThread}, or null when unusable. */
    private static BizThread toThread(JsonNode thread, String board) {
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
        RawNewsItem item = new RawNewsItem(
                link, title, "4chan /" + board + "/", link, publishedAt,
                List.of(), null, summary, false);
        String searchText = (isBlank(subClean) ? "" : subClean + " ")
                + (isBlank(comClean) ? "" : comClean);
        return new BizThread(item, searchText.strip());
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

    /** The exchange suffix cut: the symbol part before the first dot, trimmed. */
    static String bareSymbol(String symbol) {
        if (symbol == null) return "";
        String s = symbol.strip();
        int dot = s.indexOf('.');
        if (dot >= 0) s = s.substring(0, dot);
        return s.strip();
    }

    /**
     * {@code SYMBOL} or {@code $SYMBOL}, case-insensitive, on word boundaries
     * ("AGMEX" must not answer a GME query). {@code \b} misbehaves around the
     * {@code $} sigil, so the boundaries are explicit alphanumeric lookarounds.
     */
    static Pattern symbolPattern(String bareSymbol) {
        return Pattern.compile(
                "(?<![A-Za-z0-9$])\\$?" + Pattern.quote(bareSymbol) + "(?![A-Za-z0-9])",
                Pattern.CASE_INSENSITIVE);
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

    /** True when the text carries at least one significant word of the queried name. */
    static boolean textMatches(String text, Set<String> nameWords) {
        if (nameWords.isEmpty()) return false;
        String t = normalize(text);
        for (String w : nameWords) {
            if (t.matches("(?s).*\\b" + Pattern.quote(w) + "\\b.*")) return true;
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
