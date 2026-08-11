package de.bsommerfeld.wsbg.terminal.web.impl.sources.handelsblatt;

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

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Handelsblatt AND WirtschaftsWoche - the German business-press leg, read
 * through the publisher's own headless CMS API (probed 2026-08-02). Both
 * mastheads run the identical API on two hosts, so they are two LEGS of one
 * client rather than two modules; see {@link HandelsblattBrand}.
 *
 * <p>Everything below is keyless: the {@code content.www.*} hosts answer
 * without a {@code User-Agent}, without a cookie and without a {@code Referer},
 * and serve no {@code robots.txt} at all. The one header the client sends is
 * {@code Accept} - the "nur Accept, kein UA" quirk of this API is deliberate
 * and stays.
 *
 * <h2>The surfaces</h2>
 * <ul>
 *   <li><b>Ticker</b> {@code /api/ticker/} - the 100 newest pieces of a
 *       masthead with their {@code contentAccessCategory}. The pooled firehose
 *       leg of the instrument fan.</li>
 *   <li><b>Topic pages</b> {@code /api/content/eager/?url=/themen/<slug>} -
 *       1.411 curated topics at Handelsblatt, 937 at WirtschaftsWoche, companies
 *       AND people, 50 teasers per page and roughly 300 pieces / 15 months deep.
 *       An ENTITY match on the topic slug beats any full-text search, so a name
 *       query walks this door first ({@link #topicSlugs(HandelsblattBrand)}).</li>
 *   <li><b>Search</b> {@code /api/search/?searchTerm=…&page=n} - 16 hits per
 *       page ("Siemens" answers {@code count: 590}). ⚠️ The index is a ROLLING
 *       12-MONTH WINDOW and {@code fromDate} is silently ignored.</li>
 * </ul>
 *
 * <h2>The wall - a deliberate house rule</h2>
 * {@code meta.classification} is {@code FREE}, {@code METERED} or
 * {@code PREMIUM}. Technically the CMS hands out the COMPLETE text of a
 * {@code METERED} piece - the metering happens in the client. <b>This house
 * does not take it.</b> Gated items keep their headline on every teaser surface
 * but are published under the brand's {@link HandelsblattBrand#gatedPublisher()}
 * byline, so a downstream full-text reader can skip the body fetch and keep the
 * signal. That is a decision of the project owner, not an oversight - do not
 * "fix" it.
 */
@Singleton
public class HandelsblattNewsClient extends AbstractWebSource
        implements InstrumentSource, ArchiveSource {

    private static final Logger LOG = LoggerFactory.getLogger(HandelsblattNewsClient.class);

    static final String TICKER_PATH = "/api/ticker/";
    static final String SEARCH_PATH = "/api/search/?searchTerm=%s&page=%d";
    static final String EAGER_PATH = "/api/content/eager/?url=%s";
    static final String TOPICS_SITEMAP_PATH = "/sitemapExternal/sitemaps_topics.xml";

    /** {@code contentAccessCategory} of a piece that carries no wall. */
    static final String ACCESS_FREE = "NONE";

    /**
     * The publisher's rolling search index: twelve months, {@code fromDate} is
     * silently ignored by the API (probed 2026-08-02).
     */
    static final Duration SEARCH_HORIZON = Duration.ofDays(366);

    /** Pages to walk at most per brand when filling an archive window. */
    private static final int MAX_ARCHIVE_PAGES = 12;

    /** Teaser types that point at a player, not at an article. */
    private static final Set<String> NON_ARTICLE_TEASERS = Set.of("podcastteaser", "videoteaser");

    /** The topic catalogue changes by the week, not by the minute. */
    private static final Duration TOPICS_TTL = Duration.ofHours(12);

    /** Generic words that must never carry the title-relevance match alone. */
    private static final Set<String> NAME_STOP = Set.of(
            "the", "and", "der", "die", "das", "und", "inc", "incorporated", "corp",
            "corporation", "co", "company", "ag", "kgaa", "gmbh", "se", "plc", "ltd",
            "limited", "nv", "sa", "spa", "holding", "holdings", "group", "gruppe",
            "konzern", "international", "stock", "stocks", "shares", "aktie", "aktien");

    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>");

    private static final XMLInputFactory XML_FACTORY = newHardenedFactory();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Newest first; items without a timestamp sort to the end. */
    private static final Comparator<Article> BY_RECENCY =
            Comparator.comparing(Article::publishedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));

    private final Duration requestTimeout = Duration.ofSeconds(12);

    private final Map<HandelsblattBrand, CachedSlugs> topicCache =
            new EnumMap<>(HandelsblattBrand.class);

    private record CachedSlugs(Instant fetchedAt, Set<String> slugs) {}

    /**
     * No wall was seen on any of the probed surfaces; browser-first is the
     * house standard chain the old client rode, and it survives one growing.
     */
    @Inject
    public HandelsblattNewsClient(WebFetcher fetcher) {
        super(fetcher);
    }

    @Override
    public String sourceName() {
        return "handelsblatt";
    }

    /** German business press - German language, German press sphere. */
    @Override
    public SourceOrigin origin() {
        return SourceOrigin.of("de", "DE");
    }

    @Override
    public FetchUtil[] mode() {
        return new FetchUtil[] {FetchUtil.BROWSER, FetchUtil.DIRECT};
    }

    /**
     * By NAME, three doors merged freshest-first: the live ticker pool of both
     * mastheads, the curated TOPIC page when the name maps onto a topic slug
     * (the precise entity door), and one live search page per masthead for the
     * long tail. Everything is cut against the house title-precision filter
     * afterwards. Neither masthead tags ticker symbols or ISINs on its teaser
     * surfaces, so the name is the one key this source can be asked with.
     */
    @Override
    public List<Article> newsFor(ResolvedInstrument instrument, int limit) {
        String companyName = instrument.name();
        if (companyName == null || companyName.isBlank() || limit <= 0) return List.of();
        Set<String> words = significantWords(companyName);
        if (words.isEmpty()) return List.of();

        List<Article> merged = new ArrayList<>(pool().stream()
                .filter(it -> titleMatches(haystack(it), words))
                .toList());

        for (HandelsblattBrand brand : HandelsblattBrand.values()) {
            String slug = topicSlugFor(brand, companyName);
            if (slug != null) merged.addAll(topicTeasers(brand, slug, 1, limit));
            merged.addAll(searchPage(brand, companyName, 1).stream()
                    .filter(it -> titleMatches(haystack(it), words))
                    .toList());
        }
        return mergeByLink(merged, List.of()).stream()
                .sorted(BY_RECENCY)
                .limit(limit)
                .toList();
    }

    /**
     * ARCHIVE window. The topic page is walked first where a slug exists (50
     * teasers per page, strictly chronological, ~15 months deep); the search
     * leg fills in for names without a topic.
     *
     * <p>⚠️ Both legs are bounded by the publisher's rolling
     * {@link #SEARCH_HORIZON} of twelve months - {@code fromDate} is ignored by
     * the API, so a window that ends BEFORE the horizon is answered with an
     * empty list instead of dozens of pointless fetches. A window that straddles
     * the horizon is served for its reachable part. Name-keyed like every other
     * fan of this source (neither masthead tags symbols or ISINs).
     */
    @Override
    public List<Article> newsForWindow(ResolvedInstrument instrument,
            LocalDate fromDate, LocalDate toDateExclusive, int limit) {
        if (instrument == null || limit <= 0) return List.of();
        String companyName = instrument.name();
        if (companyName == null || companyName.isBlank()) return List.of();
        Instant from = startOfDay(fromDate);
        Instant toExcl = startOfDay(toDateExclusive);
        if (from == null || toExcl == null || !from.isBefore(toExcl)) return List.of();
        Instant horizon = Instant.now().minus(SEARCH_HORIZON);
        if (!toExcl.isAfter(horizon)) return List.of(); // entirely behind the wall of time
        if (from.isBefore(horizon)) from = horizon;     // serve the reachable part

        Set<String> words = significantWords(companyName);
        if (words.isEmpty()) return List.of();

        List<Article> out = new ArrayList<>();
        for (HandelsblattBrand brand : HandelsblattBrand.values()) {
            String slug = topicSlugFor(brand, companyName);
            Set<String> seen = new LinkedHashSet<>();
            for (int page = 1; page <= MAX_ARCHIVE_PAGES; page++) {
                List<Article> batch = slug != null
                        ? topicTeasers(brand, slug, page, Integer.MAX_VALUE)
                        : searchPage(brand, companyName, page);
                if (batch.isEmpty()) break;
                boolean fresh = false;
                for (Article it : batch) fresh |= seen.add(cleanLink(it.link()));
                if (!fresh) break; // the pager ran out and started repeating itself
                boolean pagedPast = false;
                for (Article it : batch) {
                    Instant at = it.publishedAt();
                    if (at == null) continue;
                    if (at.isBefore(from)) {
                        pagedPast = true;
                        continue;
                    }
                    if (!at.isBefore(toExcl)) continue; // still newer than the window
                    if (titleMatches(haystack(it), words)) out.add(it);
                }
                if (pagedPast) break;
            }
        }
        return mergeByLink(out, List.of()).stream().sorted(BY_RECENCY).limit(limit).toList();
    }

    /**
     * The masthead's curated TOPIC slugs from
     * {@code sitemapExternal/sitemaps_topics.xml} - 1.411 at Handelsblatt, 937
     * at WirtschaftsWoche, companies and people alike (probed 2026-08-02).
     * Cached for {@link #TOPICS_TTL}; the catalogue is the precise entity index
     * of this publisher.
     */
    Set<String> topicSlugs(HandelsblattBrand brand) {
        if (brand == null) return Set.of();
        synchronized (topicCache) {
            CachedSlugs c = topicCache.get(brand);
            if (c != null && c.fetchedAt().plus(TOPICS_TTL).isAfter(Instant.now())) {
                return c.slugs();
            }
            Set<String> slugs = parseTopicSitemap(
                    fetchBody(brand.siteBase() + TOPICS_SITEMAP_PATH, "application/xml"));
            if (slugs.isEmpty() && c != null) slugs = c.slugs(); // outage: keep the catalogue
            topicCache.put(brand, new CachedSlugs(Instant.now(), slugs));
            return slugs;
        }
    }

    /**
     * The teasers of one topic page, {@code page} 1-based (50 per page, roughly
     * six pages / 15 months deep). The precise instrument door: a hit here was
     * assigned by the newsroom, not guessed by a matcher.
     */
    List<Article> topicTeasers(HandelsblattBrand brand, String slug, int page, int limit) {
        if (brand == null || slug == null || slug.isBlank() || limit <= 0) return List.of();
        String path = "/themen/" + slug.trim() + (page > 1 ? "?page=" + page : "");
        String body = fetchBody(brand.apiBase() + String.format(EAGER_PATH, encode(path)),
                "application/json");
        List<Article> items = parseTeaserPage(body, brand);
        return limit == Integer.MAX_VALUE ? items : items.stream().limit(limit).toList();
    }

    // ------------------------------------------------------------- innards

    /** The merged ticker pool of both mastheads, fetched live. */
    private List<Article> pool() {
        List<Article> merged = new ArrayList<>();
        for (HandelsblattBrand brand : HandelsblattBrand.values()) {
            merged.addAll(parseTicker(
                    fetchBody(brand.apiBase() + TICKER_PATH, "application/json"), brand));
        }
        return mergeByLink(merged, List.of());
    }

    private List<Article> searchPage(HandelsblattBrand brand, String query, int page) {
        String url = brand.apiBase() + String.format(SEARCH_PATH,
                URLEncoder.encode(query, StandardCharsets.UTF_8), Math.max(1, page));
        return parseTeaserPage(fetchBody(url, "application/json"), brand);
    }

    /** The topic slug for a company name, or {@code null} when the masthead has none. */
    private String topicSlugFor(HandelsblattBrand brand, String companyName) {
        String slug = slugify(companyName);
        if (slug.isEmpty()) return null;
        return topicSlugs(brand).contains(slug) ? slug : null;
    }

    /**
     * The one-header fetch: this API answers on a bare {@code Accept} and
     * nothing else was ever required (the "nur Accept, kein UA" quirk).
     */
    private String fetchBody(String url, String accept) {
        try {
            WebResponse resp = get(url, Map.of("Accept", accept), requestTimeout);
            if (resp.status() == 200) return resp.body();
            LOG.debug("Handelsblatt {} answered status {}", url, resp.status());
        } catch (Exception e) {
            LOG.debug("Handelsblatt {} failed: {}", url, e.getMessage());
        }
        return null;
    }

    // ------------------------------------------------------------- parsers

    /**
     * The {@code /api/ticker/} payload - a bare ARRAY of 100 teasers.
     * Package-private for tests.
     */
    static List<Article> parseTicker(String json, HandelsblattBrand brand) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return teasers(MAPPER.readTree(json), brand);
        } catch (Exception e) {
            LOG.warn("Unparseable Handelsblatt ticker: {}", e.getMessage());
            return List.of();
        }
    }

    /**
     * A page carrying a {@code teasers[]} array - both {@code /api/search/} and
     * the {@code eager} topic page use the identical teaser shape.
     * Package-private for tests.
     */
    static List<Article> parseTeaserPage(String json, HandelsblattBrand brand) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return teasers(MAPPER.readTree(json).path("teasers"), brand);
        } catch (Exception e) {
            LOG.warn("Unparseable Handelsblatt teaser page: {}", e.getMessage());
            return List.of();
        }
    }

    private static List<Article> teasers(JsonNode array, HandelsblattBrand brand) {
        if (array == null || !array.isArray()) return List.of();
        List<Article> out = new ArrayList<>();
        for (JsonNode t : array) {
            String headline = text(t, "headline");
            String href = text(t.path("url"), "href");
            if (headline == null || href == null) continue;
            String type = text(t, "type");
            if (type != null && NON_ARTICLE_TEASERS.contains(type.toLowerCase(Locale.ROOT))) {
                continue; // a player has no article text
            }
            String access = textOr(t, "contentAccessCategory", ACCESS_FREE);
            String id = text(t, "id");
            String kicker = text(t, "kicker");
            String lead = stripHtml(text(t, "leadText"));
            String summary = kicker == null ? lead
                    : (lead == null ? kicker : kicker + " - " + lead);
            out.add(new Article(
                    id != null ? id : brand.siteBase() + href,
                    headline,
                    isGated(access) ? brand.gatedPublisher() : brand.publisher(),
                    brand.siteBase() + href,
                    parseIsoInstant(text(t.path("dates"), "published")),
                    List.of(),
                    null,
                    summary,
                    false,
                    imageUrl(t.path("image"))));
        }
        return out;
    }

    /**
     * The topic sitemap → the bare slugs ({@code /themen/siemens-energy} →
     * {@code siemens-energy}). Package-private for tests.
     */
    static Set<String> parseTopicSitemap(String xml) {
        if (xml == null || xml.isBlank()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(xml));
            try {
                while (r.hasNext()) {
                    if (r.next() == XMLStreamConstants.START_ELEMENT
                            && "loc".equals(r.getLocalName())) {
                        String loc = elementText(r);
                        int marker = loc == null ? -1 : loc.indexOf("/themen/");
                        if (marker >= 0) {
                            String slug = loc.substring(marker + "/themen/".length());
                            int q = slug.indexOf('?');
                            if (q >= 0) slug = slug.substring(0, q);
                            slug = slug.replaceAll("/+$", "");
                            if (!slug.isEmpty()) out.add(slug.toLowerCase(Locale.ROOT));
                        }
                    }
                }
            } finally {
                r.close();
            }
        } catch (Exception e) {
            LOG.warn("Unparseable Handelsblatt topic sitemap: {}", e.getMessage());
            return Set.of();
        }
        return out;
    }

    // ------------------------------------------------------------- helpers

    /** True for every access category other than {@code NONE}. */
    static boolean isGated(String accessCategory) {
        return accessCategory != null && !accessCategory.isBlank()
                && !ACCESS_FREE.equalsIgnoreCase(accessCategory.trim());
    }

    /**
     * The renderable image URL. The teaser's own {@code url.href} is a
     * deliberate decoy ({@code …/do/not/use/this/url/…}); the real one is built
     * from the image id on the CDN base, exactly as the RSS enclosures do.
     */
    static String imageUrl(JsonNode image) {
        if (image == null || image.isMissingNode()) return null;
        String id = text(image, "id");
        String base = text(image.path("baseUrl"), "href");
        if (id == null || base == null) return null;
        String name = textOr(image, "urlFriendlyTitle", "image");
        String ext = textOr(image, "fileExtension", "jpg");
        return base + "/" + id + "/cover/900/506/0/0/0/0/0.5/0.5/" + name + "." + ext;
    }

    /** Dedupes by article URL, first occurrence wins. Package-private for tests. */
    static List<Article> mergeByLink(List<Article> first, List<Article> second) {
        Map<String, Article> byLink = new LinkedHashMap<>();
        for (Article it : first) byLink.putIfAbsent(cleanLink(it.link()), it);
        for (Article it : second) byLink.putIfAbsent(cleanLink(it.link()), it);
        return List.copyOf(byLink.values());
    }

    /** The article URL without its tracking query - the identity/merge key. */
    static String cleanLink(String link) {
        if (link == null) return "";
        int q = link.indexOf('?');
        return q >= 0 ? link.substring(0, q) : link;
    }

    /** Headline plus kicker plus lead - the kicker often carries the company. */
    private static String haystack(Article it) {
        return it.summary() == null ? it.title() : it.title() + " " + it.summary();
    }

    /** {@code 2026-08-02T19:26:43.706Z} → {@link Instant}; unparseable → null. */
    static Instant parseIsoInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** UTC start of day, or null for a missing bound. */
    static Instant startOfDay(LocalDate day) {
        return day == null ? null : day.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** True when the text carries at least one significant word of the queried name. */
    static boolean titleMatches(String text, Set<String> nameWords) {
        if (text == null || nameWords.isEmpty()) return false;
        String t = normalize(text);
        for (String w : nameWords) {
            if (t.matches("(?s).*\\b" + Pattern.quote(w) + "\\b.*")) return true;
        }
        return false;
    }

    /** Significant (length ≥ 3, non-generic) words of the queried name. */
    static Set<String> significantWords(String name) {
        Set<String> out = new LinkedHashSet<>();
        for (String w : normalize(name).split("[^a-z0-9]+")) {
            if (w.length() >= 3 && !NAME_STOP.contains(w)) out.add(w);
        }
        return out;
    }

    /** {@code Siemens Energy AG} → {@code siemens-energy} - the topic-slug shape. */
    static String slugify(String name) {
        if (name == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String w : normalize(name).split("[^a-z0-9]+")) {
            if (w.isEmpty() || NAME_STOP.contains(w)) continue;
            if (sb.length() > 0) sb.append('-');
            sb.append(w);
        }
        return sb.toString();
    }

    /** HTML → plain text, entities decoded, whitespace collapsed. */
    static String stripHtml(String html) {
        if (html == null) return null;
        String s = HTML_TAG.matcher(html.replace("</p>", "</p>\n")).replaceAll(" ");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">");
        return s.replaceAll("[ \\t]+", " ").replaceAll("\\s*\\n\\s*", "\n").trim();
    }

    private static String encode(String path) {
        return URLEncoder.encode(path, StandardCharsets.UTF_8);
    }

    private static String text(JsonNode n, String field) {
        if (n == null) return null;
        JsonNode v = n.get(field);
        if (v == null || v.isNull() || !v.isValueNode()) return null;
        String s = v.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    private static String textOr(JsonNode n, String field, String fallback) {
        String v = text(n, field);
        return v == null ? fallback : v;
    }

    private static String elementText(XMLStreamReader r) throws Exception {
        String t = r.getElementText();
        return t != null ? t.trim() : null;
    }

    private static String normalize(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
    }

    private static XMLInputFactory newHardenedFactory() {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        return f;
    }
}
