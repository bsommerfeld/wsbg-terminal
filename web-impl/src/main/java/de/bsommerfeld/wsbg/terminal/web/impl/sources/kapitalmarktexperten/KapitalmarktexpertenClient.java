package de.bsommerfeld.wsbg.terminal.web.impl.sources.kapitalmarktexperten;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.article.SourceOrigin;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.source.AbstractWebSource;
import de.bsommerfeld.wsbg.terminal.web.source.ArchiveSource;
import de.bsommerfeld.wsbg.terminal.web.source.InstrumentSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

/**
 * kapitalmarktexperten.de - the most OPEN API of the whole 2026-08 source wave
 * and, at the same time, the one whose content must never be trusted at face
 * value. Both halves of that sentence shape this client.
 *
 * <p><b>The surface</b> (probed live 2026-08-02): a plain WordPress REST API at
 * {@code /wp-json/wp/v2/}. No paywall, no bot wall, no key, no rate limit,
 * {@code robots.txt} with an empty {@code Disallow:}. {@code /posts} answers
 * {@code X-WP-Total: 19460}, {@code _fields} slims a 100-post page to 27 KB in
 * 0,54 s, and {@code content.rendered} carries the COMPLETE article body
 * (2.500-4.100 characters) - no second fetch needed to read a piece.
 *
 * <p><b>The catch:</b> roughly 60 articles a day, around the clock, under four
 * rotating author names - machine-generated single-stock copy written second
 * hand out of exactly the wires this terminal already taps directly. Per the
 * house fischernetz principle the source therefore comes IN, but every item it
 * emits carries {@link #PUBLISHER} - literally {@code "Kapitalmarktexperten
 * (KI-generiert)"}. That marking is not decoration: it is the hook a fact check
 * further up the stack keys off, so such a line can never pass as a first-hand
 * report. {@link #socialSentiment()} stays {@code false}: these are articles,
 * not forum posts - their cadence is not a room's attention, it is a
 * generator's schedule.
 *
 * <p><b>The killer feature: {@code isin} is a TOP-LEVEL property on every
 * post</b> (e.g. {@code US0378331005}), so this source can answer an ISIN
 * EXACTLY instead of fuzzily. One trap, probed and confirmed:
 * {@code ?search=<ISIN>} returns 0 hits - the ISIN is <b>not</b> full-text
 * indexed, it is only usable as a FIELD. The ISIN leg therefore reads the
 * recent firehose and cuts on the field client-side.
 *
 * <p><b>Two ways in for a NAME, and when each wins.</b> 4.616 of the site's
 * tags are company names ({@code siemens} = 92 posts, {@code siemens-energy} =
 * 178):
 *
 * <ul>
 *   <li><b>Tag route</b> ({@code tags?search=<name>} → {@code posts?tags=<id>})
 *       - PRECISE. The site itself decided the article is about that company,
 *       so no passing mention can slip in. Costs one extra (memoised) lookup
 *       and fails only when the company has no tag. This is the preferred
 *       route for the archive window ({@link #newsForWindow} - the date
 *       window is what {@code after}/{@code before} were made for).</li>
 *   <li><b>Search route</b> ({@code posts?search=<name>}) - BROAD. The server
 *       matches full text, so it over-returns badly: "Siemens Energy" answers
 *       247 posts including Vulcan Energy pieces (probed). It therefore always
 *       runs behind the house TITLE-precision filter. It is the fallback for
 *       untagged companies.</li>
 * </ul>
 *
 * <p><b>Deliberately NOT used:</b> {@code /feed/} answers 200 but carries only
 * 10 items spanning 2,6 hours - at 60 posts a day that drops most of the
 * stream on the floor, so the REST firehose replaces it. The per-category
 * feeds answer <b>410 Gone</b> outright. Neither is worth a fetch.
 */
@Singleton
public class KapitalmarktexpertenClient extends AbstractWebSource
        implements InstrumentSource, ArchiveSource {

    private static final Logger LOG =
            LoggerFactory.getLogger(KapitalmarktexpertenClient.class);

    /** Short, stable source id. */
    static final String SOURCE = "kapitalmarktexperten";

    /**
     * The ONE publisher string this source ever emits. The origin marking is
     * carried in-band deliberately: everything here is machine-generated
     * second-hand copy, and a consumer that only ever reads {@code publisher}
     * must still be able to see that before it prints the line.
     */
    public static final String PUBLISHER = "Kapitalmarktexperten (KI-generiert)";

    static final String BASE = "https://www.kapitalmarktexperten.de/wp-json/wp/v2/";

    /**
     * Full post projection incl. the article body - used by the instrument
     * fans, where the body is the point (a DD reader gets the full text without
     * a second fetch).
     */
    static final String FIELDS_FULL = "id,date_gmt,link,title,isin,tags,excerpt,content";
    /**
     * Slim projection without bodies - used by the recent firehose, where 100
     * posts must stay a 27 KB request.
     */
    static final String FIELDS_SLIM = "id,date_gmt,link,title,isin,tags,excerpt";

    /** Server-side maximum for {@code per_page}. */
    static final int PAGE_SIZE = 100;
    /** Pages the recent firehose reaches back (~5 days at ~60 posts/day). */
    static final int POOL_PAGES = 3;
    /** Pages walked at most when filling an archive window. */
    static final int MAX_WINDOW_PAGES = 5;
    /** Fetch depth per name query before the title-precision cut bites. */
    static final int SEARCH_FETCH_SIZE = 50;
    /** Cap for the lead text derived from {@code content.rendered}. */
    static final int SUMMARY_MAX = 400;

    /** Generic words that must never carry a title match on their own. */
    private static final Set<String> NAME_STOP = Set.of(
            "the", "and", "und", "inc", "incorporated", "corp", "corporation", "co",
            "company", "ag", "se", "plc", "ltd", "limited", "nv", "sa", "spa",
            "holding", "holdings", "group", "international", "aktie", "aktien",
            "stock", "stocks", "shares");

    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(\\d+);");
    private static final Pattern HEX_ENTITY = Pattern.compile("&#[xX]([0-9a-fA-F]+);");
    /** A well-formed ISIN: 2 country letters, 9 alphanumerics, 1 check digit. */
    private static final Pattern ISIN = Pattern.compile("[A-Z]{2}[A-Z0-9]{9}[0-9]");

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Newest first; undated items sort to the end. */
    private static final Comparator<Article> BY_RECENCY =
            Comparator.comparing(Article::publishedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));

    /** Resolved company-name → tag id, {@code -1} for "this site has no tag". */
    private final Map<String, Integer> tagIdCache = new ConcurrentHashMap<>();

    /**
     * One term of the WordPress tag taxonomy with the number of posts filed
     * under it.
     */
    public record Term(int id, String name, String slug, int count) {}

    /**
     * An open, keyless JSON API with no wall of any kind is exactly the case
     * the old {@code @DirectFirst} binding described - direct first, the
     * browser joker behind it.
     */
    @Inject
    public KapitalmarktexpertenClient(WebFetcher fetcher) {
        super(fetcher);
    }

    @Override
    public String sourceName() {
        return SOURCE;
    }

    /** German-language generator writing for the German retail sphere. */
    @Override
    public SourceOrigin origin() {
        return SourceOrigin.of("de", "DE");
    }

    @Override
    public FetchUtil[] mode() {
        return new FetchUtil[] {FetchUtil.DIRECT, FetchUtil.BROWSER};
    }

    /** Articles, not forum posts - this source never rides the sentiment fan. */
    @Override
    public boolean socialSentiment() {
        return false;
    }

    /**
     * Additive key fan: the NAME leg (tag route first - the site's own "this
     * piece is about X" - search route behind the title-precision filter as the
     * fallback) beside the ISIN leg (the recent firehose cut EXACTLY on the
     * site's top-level {@code isin} field - routing around the probed trap that
     * {@code ?search=<ISIN>} answers 0 hits). The site tags no ticker symbols;
     * that key is ignored. Every item is marked as generated second-hand copy.
     */
    @Override
    public List<Article> newsFor(ResolvedInstrument instrument, int limit) {
        if (limit <= 0) return List.of();
        List<Article> out = new ArrayList<>();

        String companyName = instrument.name();
        if (companyName != null && !companyName.isBlank()) {
            Set<String> words = significantWords(companyName);
            if (!words.isEmpty()) {
                int tagId = resolveTagId(companyName);
                if (tagId > 0) {
                    out.addAll(fetchPosts("tags=" + tagId + "&per_page="
                            + Math.min(limit, PAGE_SIZE) + "&_fields=" + FIELDS_FULL));
                }
                if (out.size() < limit) {
                    for (Article it : fetchPosts("search=" + enc(companyName)
                            + "&per_page=" + SEARCH_FETCH_SIZE + "&_fields=" + FIELDS_FULL)) {
                        if (titleMatches(it.title(), words)) out.add(it);
                    }
                }
            }
        }
        instrument.isin().ifPresent(isin -> {
            String wanted = isin.value().toUpperCase(Locale.ROOT);
            if (ISIN.matcher(wanted).matches()) {
                for (Article it : recentPosts()) {
                    if (wanted.equalsIgnoreCase(it.isin())) out.add(it);
                }
            }
        });
        return dedupe(out).stream().sorted(BY_RECENCY).limit(limit).toList();
    }

    /**
     * The ARCHIVE door - the date window is what {@code after}/{@code before}
     * were made for, and the long tail of German small/micro caps is where
     * this site is genuinely additive. {@code after}/{@code before} narrow
     * server-side, the exact {@code [from, to)} cut runs on {@code date_gmt}
     * client-side (the server window is site-local time).
     *
     * <p>Route choice: the tag route when the company has a tag (precise, no
     * title guessing), the search route otherwise. When an ISIN is resolved it
     * acts as an additional ACCEPT on the search route - an exact field hit is
     * kept even if the headline abbreviates the name away.
     */
    @Override
    public List<Article> newsForWindow(ResolvedInstrument instrument,
            LocalDate fromDate, LocalDate toDateExclusive, int limit) {
        String companyName = instrument.name();
        String isin = instrument.isin()
                .map(i -> i.value()).orElse(null);
        if (companyName == null || companyName.isBlank() || limit <= 0) return List.of();
        Instant from = fromDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant toExcl = toDateExclusive.atStartOfDay(ZoneOffset.UTC).toInstant();
        if (!from.isBefore(toExcl)) return List.of();
        Set<String> words = significantWords(companyName);
        if (words.isEmpty()) return List.of();

        int tagId = resolveTagId(companyName);
        String selector = tagId > 0
                ? "tags=" + tagId
                : "search=" + enc(companyName);
        String window = "&after=" + fromDate + "T00:00:00"
                + "&before=" + toDateExclusive + "T00:00:00";

        List<Article> out = new ArrayList<>();
        for (int page = 1; page <= MAX_WINDOW_PAGES && out.size() < limit; page++) {
            List<Article> batch = fetchPosts(selector + window
                    + "&per_page=" + PAGE_SIZE + "&page=" + page
                    + "&_fields=" + FIELDS_FULL);
            if (batch.isEmpty()) break;
            for (Article it : batch) {
                Instant at = it.publishedAt();
                if (at == null || at.isBefore(from) || !at.isBefore(toExcl)) continue;
                boolean exactIsin = isin != null && !isin.isBlank()
                        && isin.strip().equalsIgnoreCase(it.isin());
                if (tagId <= 0 && !exactIsin && !titleMatches(it.title(), words)) continue;
                out.add(it);
            }
            if (batch.size() < PAGE_SIZE) break;
        }
        List<Article> result = dedupe(out).stream()
                .sorted(BY_RECENCY).limit(limit).toList();
        if (!result.isEmpty()) {
            LOG.info("[{}] '{}' → {} generated piece(s) in {}..{} (via {})", SOURCE,
                    companyName, result.size(), fromDate, toDateExclusive,
                    tagId > 0 ? "tag " + tagId : "search");
        }
        return result;
    }

    // ---------------------------------------------------------------
    // internals
    // ---------------------------------------------------------------

    /** The recent firehose (~5 days deep), fetched live with the slim projection. */
    private List<Article> recentPosts() {
        List<Article> merged = new ArrayList<>();
        for (int page = 1; page <= POOL_PAGES; page++) {
            List<Article> batch = fetchPosts("per_page=" + PAGE_SIZE
                    + "&page=" + page + "&_fields=" + FIELDS_SLIM);
            merged.addAll(batch);
            if (batch.size() < PAGE_SIZE) break;
        }
        return dedupe(merged);
    }

    /**
     * Company name → tag id, memoised (including the misses). Exact name match
     * wins; otherwise the most specific tag whose own words are all contained
     * in the queried name - so "Siemens" never resolves to "Siemens Energy",
     * while "Siemens Energy AG" does.
     */
    int resolveTagId(String companyName) {
        String key = normalize(companyName).strip();
        Integer cached = tagIdCache.get(key);
        if (cached != null) return cached;
        int resolved = pickTag(tags(companyName, 10), companyName);
        tagIdCache.put(key, resolved);
        return resolved;
    }

    /**
     * The TAG index narrowed by a search term - the lookup behind the precise
     * tag route.
     */
    List<Term> tags(String search, int limit) {
        if (limit <= 0) return List.of();
        String q = search == null || search.isBlank() ? "" : "search=" + enc(search) + "&";
        return fetchTerms("tags?" + q + "per_page=" + Math.min(limit, PAGE_SIZE)
                + "&orderby=count&order=desc&_fields=id,name,slug,count");
    }

    /** The best tag for a queried name, or {@code -1}. Package-private for tests. */
    static int pickTag(List<Term> candidates, String companyName) {
        if (candidates == null || candidates.isEmpty()) return -1;
        String wanted = normalize(companyName).strip();
        Set<String> queryWords = significantWords(companyName);
        Term best = null;
        int bestWords = 0;
        for (Term t : candidates) {
            if (t.name() == null) continue;
            if (normalize(t.name()).strip().equals(wanted)) return t.id();
            Set<String> tagWords = significantWords(t.name());
            if (tagWords.isEmpty() || !queryWords.containsAll(tagWords)) continue;
            if (best == null || tagWords.size() > bestWords
                    || (tagWords.size() == bestWords && t.count() > best.count())) {
                best = t;
                bestWords = tagWords.size();
            }
        }
        return best == null ? -1 : best.id();
    }

    /** One {@code /posts} request; any failure answers empty, never throws. */
    private List<Article> fetchPosts(String query) {
        String body = fetchBody("posts?" + query);
        return body == null ? List.of() : parsePosts(body);
    }

    /** One taxonomy request; any failure answers empty, never throws. */
    private List<Term> fetchTerms(String path) {
        String body = fetchBody(path);
        return body == null ? List.of() : parseTerms(body);
    }

    private String fetchBody(String path) {
        String url = BASE + path;
        try {
            WebResponse resp = get(url, Map.of("Accept", "application/json"));
            if (resp.status() != 200 || resp.body() == null) {
                LOG.debug("[{}] {} answered status {}", SOURCE, path, resp.status());
                return null;
            }
            return resp.body();
        } catch (Exception e) {
            LOG.debug("[{}] {} failed: {}", SOURCE, path, e.getMessage());
            return null;
        }
    }

    /**
     * The {@code wp/v2/posts} array → items, unfiltered (precision cuts belong
     * to the fan). {@code title.rendered} and the body arrive as rendered HTML,
     * so tags are stripped and entities decoded here. {@code isin} is the
     * site's top-level field and travels straight into {@link Article#isin()}.
     * A non-array answer (WP error object, HTML shell) or garbage yields empty,
     * never throws. Package-private for tests.
     */
    static List<Article> parsePosts(String json) {
        if (json == null || json.isBlank()) return List.of();
        List<Article> out = new ArrayList<>();
        try {
            JsonNode root = JSON.readTree(json);
            if (!root.isArray()) return List.of();
            for (JsonNode post : root) {
                String title = stripHtml(post.path("title").path("rendered").asText(null));
                String link = post.path("link").asText(null);
                if (title == null || title.isBlank() || link == null || link.isBlank()) continue;
                String id = post.path("id").asText("");
                out.add(new Article(
                        id.isEmpty() ? link : SOURCE + "-" + id,
                        title,
                        PUBLISHER,
                        link,
                        parseStamp(post.path("date_gmt").asText(null)),
                        List.of(),
                        blankToNull(post.path("isin").asText(null)),
                        leadText(post),
                        false));
            }
        } catch (Exception e) {
            LOG.debug("[{}] post parse failed: {}", SOURCE, e.getMessage());
            return List.of();
        }
        return List.copyOf(out);
    }

    /**
     * A taxonomy array ({@code /tags}) → terms. Garbage yields empty.
     * Package-private for tests.
     */
    static List<Term> parseTerms(String json) {
        if (json == null || json.isBlank()) return List.of();
        List<Term> out = new ArrayList<>();
        try {
            JsonNode root = JSON.readTree(json);
            if (!root.isArray()) return List.of();
            for (JsonNode t : root) {
                String name = stripHtml(t.path("name").asText(null));
                if (name == null || name.isBlank()) continue;
                out.add(new Term(t.path("id").asInt(), name,
                        t.path("slug").asText(null), t.path("count").asInt()));
            }
        } catch (Exception e) {
            LOG.debug("[{}] term parse failed: {}", SOURCE, e.getMessage());
            return List.of();
        }
        return List.copyOf(out);
    }

    /**
     * The teaser: the article body's opening, capped at {@link #SUMMARY_MAX}
     * on a sentence boundary where possible - preferred over the WP excerpt,
     * which is hard-truncated mid-word with an ellipsis entity. Falls back to
     * the excerpt when the body wasn't requested (slim projection).
     */
    static String leadText(JsonNode post) {
        String body = stripHtml(post.path("content").path("rendered").asText(null));
        if (body == null || body.isBlank()) {
            return blankToNull(stripHtml(post.path("excerpt").path("rendered").asText(null)));
        }
        if (body.length() <= SUMMARY_MAX) return body;
        String cut = body.substring(0, SUMMARY_MAX);
        int stop = Math.max(cut.lastIndexOf(". "), cut.lastIndexOf("? "));
        return stop > SUMMARY_MAX / 2 ? cut.substring(0, stop + 1).strip() : cut.strip() + "…";
    }

    /** Dedupes by item id, first occurrence wins. Package-private for tests. */
    static List<Article> dedupe(List<Article> items) {
        Map<String, Article> byId = new LinkedHashMap<>();
        for (Article it : items) byId.putIfAbsent(it.uuid(), it);
        return List.copyOf(byId.values());
    }

    /** {@code date_gmt} ("2026-08-02T20:12:05", zoneless UTC) → Instant; bad input → null. */
    static Instant parseStamp(String dateGmt) {
        if (dateGmt == null || dateGmt.isBlank()) return null;
        try {
            return LocalDateTime.parse(dateGmt.strip()).toInstant(ZoneOffset.UTC);
        } catch (Exception e) {
            return null;
        }
    }

    /** True when the title carries at least one significant word of the queried name. */
    static boolean titleMatches(String title, Set<String> nameWords) {
        if (title == null || nameWords.isEmpty()) return false;
        String t = normalize(title);
        for (String w : nameWords) {
            if (t.matches(".*\\b" + Pattern.quote(w) + "\\b.*")) return true;
        }
        return false;
    }

    /** Significant (length ≥ 3, non-generic) words of a name, umlaut-normalised. */
    static Set<String> significantWords(String name) {
        Set<String> out = new LinkedHashSet<>();
        if (name == null) return out;
        for (String w : normalize(name).split("[^a-z0-9]+")) {
            if (w.length() >= 3 && !NAME_STOP.contains(w)) out.add(w);
        }
        return out;
    }

    /** Rendered HTML → plain text: tags out, entities decoded, whitespace collapsed. */
    static String stripHtml(String html) {
        if (html == null) return null;
        return decodeEntities(html.replaceAll("<[^>]+>", " "))
                .replaceAll("\\s+", " ")
                .strip();
    }

    /**
     * HTML entities → text: numeric/hex first, then the named set WordPress
     * actually emits in {@code rendered} fields, {@code &amp;} last.
     */
    static String decodeEntities(String s) {
        if (s == null) return null;
        String out = NUMERIC_ENTITY.matcher(s).replaceAll(
                (MatchResult m) -> new String(Character.toChars(Integer.parseInt(m.group(1)))));
        out = HEX_ENTITY.matcher(out).replaceAll(
                (MatchResult m) -> new String(Character.toChars(Integer.parseInt(m.group(1), 16))));
        return out
                .replace("&hellip;", "…").replace("&ndash;", "–").replace("&mdash;", "—")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#039;", "'").replace("&nbsp;", " ")
                .replace("&amp;", "&");
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s.strip(), StandardCharsets.UTF_8);
    }
}
