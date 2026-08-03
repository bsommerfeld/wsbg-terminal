package de.bsommerfeld.wsbg.terminal.cnbc;

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

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * CNBC as the US financial PRESS leg - keyless on every surface, no wall, no
 * cookie, no key (probed 2026-08-02). Two legs with different jobs:
 *
 * <ul>
 *   <li><b>Section RSS</b> ({@code www.cnbc.com/id/<id>/device/rss/rss.html}) -
 *       the live firehose. 30 items per feed with a teaser {@code description},
 *       a stable {@code metadata:id} and CNBC's own {@code metadata:sponsored}
 *       flag. Only {@code subtype: section} feeds carry items at all: the ~24
 *       show franchises (Squawk Box, Fast Money, Closing Bell …) answer 200
 *       with an EMPTY channel or entries from 2013-2019, so they are not
 *       wired - a dead feed costs a fetch and returns nothing.</li>
 *   <li><b>queryly full-text search</b> ({@code api.queryly.com/cnbc/json.aspx})
 *       - the instrument-addressed door AND the multi-year archive. "nvidia"
 *       answers 15.795 hits, {@code endindex} pages deep (probed to 15.000)
 *       back to at least 2016, {@code sort=date} makes it chronological. The
 *       {@code queryly_key} is hardcoded in CNBC's own frontend, not a secret.</li>
 * </ul>
 *
 * <p><b>Paywall handling.</b> CNBC Pro articles answer 200 with an EMPTY body,
 * so a downstream full-text reader would burn a fetch on nothing. Both legs
 * mark them BEFORE the fetch: search results carry
 * {@code cn:contentClassification} ({@code premium}, {@code investingClub},
 * {@code registeredOnly}, {@code exclusive}) and such items get the
 * {@link #PUBLISHER_GATED} publisher, so a reader can skip the body and keep
 * the headline - the headline is the part that carries the signal anyway.
 *
 * <p><b>Video items are dropped.</b> {@code cn:type: cnbcvideo} has no article
 * text; a headline pointing at a player is noise in a press loom.
 *
 * <p>Name-addressed only. CNBC tags no tickers and no ISINs on either surface,
 * so {@link #newsFor} stays a no-op rather than guessing a symbol into a
 * full-text query (a two-letter ticker would drag in every passing mention).
 * The house TITLE-precision cut applies to the pooled feed leg; the search leg
 * is server-side relevance-ranked and additionally cut against title AND teaser.
 */
@Singleton
public class CnbcNewsClient implements NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(CnbcNewsClient.class);

    static final String PUBLISHER = "CNBC";
    /** Publisher marker for Pro/gated pieces - a body fetch on these yields 0 chars. */
    static final String PUBLISHER_GATED = "CNBC Pro";

    static final String FEED_URL = "https://www.cnbc.com/id/%s/device/rss/rss.html";
    static final String SEARCH_URL =
            "https://api.queryly.com/cnbc/json.aspx?queryly_key=31a35d40a9a64ab3"
                    + "&query=%s&endindex=%d&batchsize=%d&sort=%s&timezoneoffset=0";

    /**
     * The finance-relevant SECTION feeds, verified live 2026-08-02 (id → label).
     * A deliberate subset of the 30 populated feeds: these carry the market,
     * macro, earnings and sector news a trading desk reads. The remaining
     * lifestyle/regional sections (Travel, Make It, Real Estate …) stay out of
     * the poll - they are reachable through the search leg whenever a name
     * actually appears there, which is the cheaper door for a long tail.
     */
    static final Map<String, String> SECTION_FEEDS = Map.ofEntries(
            Map.entry("100003114", "US Top News"),
            Map.entry("10001147", "Business"),
            Map.entry("10000664", "Finance"),
            Map.entry("15839069", "Investing"),
            Map.entry("15839135", "Earnings"),
            Map.entry("20910258", "Economy"),
            Map.entry("19854910", "Technology"),
            Map.entry("19836768", "Energy"),
            Map.entry("19794221", "Europe News"),
            Map.entry("19832390", "Asia News"),
            Map.entry("10000113", "Politics"),
            Map.entry("100727362", "International"));

    private static final Duration CACHE_TTL = Duration.ofMinutes(10);

    /** Search pages to walk at most when filling an archive window. */
    private static final int MAX_ARCHIVE_PAGES = 12;
    private static final int SEARCH_BATCH = 50;

    /** Generic words that must never carry the title-relevance match alone. */
    private static final Set<String> NAME_STOP = Set.of(
            "the", "and", "inc", "incorporated", "corp", "corporation", "co",
            "company", "ag", "se", "plc", "ltd", "limited", "nv", "sa", "spa",
            "holding", "holdings", "group", "international", "stock", "stocks", "shares");

    /** Content classifications that mean "body is behind a wall". */
    private static final Set<String> GATED = Set.of(
            "premium", "investingclub", "registeredonly", "exclusive");

    private static final XMLInputFactory XML_FACTORY = newHardenedFactory();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Newest first; items without a timestamp sort to the end. */
    private static final Comparator<RawNewsItem> BY_RECENCY =
            Comparator.comparing(RawNewsItem::publishedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));

    /** {@code Sat, 01 Aug 2026 12:16:13 GMT} - RFC-1123 with a 4-digit year. */
    private static final DateTimeFormatter RSS_DATE = DateTimeFormatter.RFC_1123_DATE_TIME;

    /** {@code 2026-07-27T21:10:17+0000} - queryly's offset carries no colon. */
    private static final DateTimeFormatter SEARCH_DATE = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd'T'HH:mm:ssZ")
            .toFormatter(Locale.ROOT);

    private final String userAgent = BrowserUserAgent.random();
    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(12);

    private volatile Cached pool;

    private record Cached(Instant fetchedAt, List<RawNewsItem> items) {}

    /** Test/default: plain direct transport. */
    public CnbcNewsClient() {
        this(new DirectWebFetcher());
    }

    /**
     * Production: rides the shared standard {@link WebFetcher} chain
     * (browser → direct). CNBC carries no wall today (12 search calls and 8
     * GraphQL calls in a burst all answered 200), but the chain costs nothing
     * at this cadence and survives one growing.
     */
    @Inject
    public CnbcNewsClient(WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String sourceName() {
        return "cnbc";
    }

    /**
     * Dossier leg. CNBC is name-addressed full-text search over a house that
     * publishes all day: for a wire query the same name returns passing
     * mentions and Pro stubs by the dozen, which is archive depth for a
     * dossier and dilution for a headline (2026-08-03).
     */
    @Override
    public boolean dossierOnly() {
        return true;
    }

    /**
     * No-op: CNBC tags neither tickers nor ISINs on the RSS or search surface.
     * Feeding a bare symbol into the full-text search would answer every
     * passing mention of a two-letter word, so the name fan carries this source.
     */
    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        return List.of();
    }

    /**
     * By name: the cached section pool (title/teaser precision-matched) merged
     * with a live full-text search, freshest first. The pool answers instantly
     * and covers what the desk is reading right now; the search reaches the
     * long tail the twelve polled sections never carry.
     */
    @Override
    public List<RawNewsItem> newsForName(String companyName, int limit) {
        if (companyName == null || companyName.isBlank() || limit <= 0) return List.of();
        Set<String> words = significantWords(companyName);
        if (words.isEmpty()) return List.of();

        List<RawNewsItem> fromPool = pool().stream()
                .filter(it -> titleMatches(haystack(it), words))
                .toList();
        List<RawNewsItem> fromSearch = search(companyName, 0, SEARCH_BATCH, "date").stream()
                .filter(it -> titleMatches(haystack(it), words))
                .toList();

        return mergeByLink(fromPool, fromSearch).stream()
                .sorted(BY_RECENCY)
                .limit(limit)
                .toList();
    }

    /**
     * ARCHIVE window over the search leg: {@code sort=date} makes the result
     * chronological, so paging walks backwards until the window's lower bound
     * falls out of view (capped at {@link #MAX_ARCHIVE_PAGES} - a name with a
     * five-figure hit count must not turn one dossier into a crawl).
     */
    @Override
    public List<RawNewsItem> newsForNameWindow(String companyName, String isin,
                                               String fromIsoDate, String toIsoDateExclusive,
                                               int limit) {
        if (companyName == null || companyName.isBlank() || limit <= 0) return List.of();
        Instant from = startOfDay(fromIsoDate);
        Instant toExcl = startOfDay(toIsoDateExclusive);
        if (from == null || toExcl == null) return List.of();
        Set<String> words = significantWords(companyName);
        if (words.isEmpty()) return List.of();

        List<RawNewsItem> out = new ArrayList<>();
        for (int page = 0; page < MAX_ARCHIVE_PAGES && out.size() < limit; page++) {
            List<RawNewsItem> batch = search(companyName, page * SEARCH_BATCH, SEARCH_BATCH, "date");
            if (batch.isEmpty()) break;
            boolean reachedBefore = false;
            for (RawNewsItem it : batch) {
                Instant at = it.publishedAt();
                if (at == null) continue;
                if (at.isBefore(from)) {
                    reachedBefore = true;
                    continue;
                }
                if (!at.isBefore(toExcl)) continue; // still newer than the window
                if (titleMatches(haystack(it), words)) out.add(it);
            }
            if (reachedBefore) break; // paged past the window's lower bound
        }
        return out.stream().sorted(BY_RECENCY).limit(limit).toList();
    }

    /**
     * GENERAL stream: the freshest items across every wired section, newest
     * first - the instrument-free door into this source. Served from the same
     * TTL pool the name fan uses, so calling it costs no extra fetch.
     *
     * <p>Nothing in the terminal calls this today; it exists so the general
     * wire can be mobilised later without reopening the source (house mandate
     * 2026-08-02: every source is connected BOTH per instrument and generally).
     */
    public List<RawNewsItem> latest(int limit) {
        if (limit <= 0) return List.of();
        return pool().stream().sorted(BY_RECENCY).limit(limit).toList();
    }

    /**
     * GENERAL stream, one section at a time - {@code sectionId} is a key of
     * {@link #SECTION_FEEDS} (e.g. {@code "15839135"} for Earnings). Unlike
     * {@link #latest} this reaches PAST the wired twelve: any of CNBC's ~30
     * populated feed ids answers, so a caller can pull Retail or Real Estate
     * on demand without them riding in the standing poll.
     *
     * <p>Currently unused - see {@link #latest}.
     */
    public List<RawNewsItem> section(String sectionId, int limit) {
        if (sectionId == null || sectionId.isBlank() || limit <= 0) return List.of();
        return fetchFeed(sectionId.trim()).stream().sorted(BY_RECENCY).limit(limit).toList();
    }

    /**
     * GENERAL full-text search, relevance-ranked - the raw search leg without
     * the name-precision cut {@link #newsForName} applies. Useful for themes
     * rather than instruments ("tariffs", "rate cut"), which is why it takes a
     * free query and does no title filtering.
     *
     * <p>Currently unused - see {@link #latest}.
     */
    public List<RawNewsItem> searchByRelevance(String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) return List.of();
        return search(query, 0, Math.min(limit, SEARCH_BATCH), "").stream()
                .limit(limit)
                .toList();
    }

    /** The section feeds this source polls, id → label. Read-only. */
    public Map<String, String> sections() {
        return SECTION_FEEDS;
    }

    /** The merged, cached section pool; refreshed at most once per TTL. */
    private List<RawNewsItem> pool() {
        Cached c = pool;
        if (c != null && c.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return c.items();
        }
        synchronized (this) {
            c = pool;
            if (c != null && c.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
                return c.items();
            }
            List<RawNewsItem> merged = new ArrayList<>();
            for (Map.Entry<String, String> feed : SECTION_FEEDS.entrySet()) {
                merged.addAll(fetchFeed(feed.getKey()));
            }
            List<RawNewsItem> deduped = mergeByLink(merged, List.of());
            if (deduped.isEmpty() && c != null) {
                deduped = c.items(); // outage: keep the stale pool over an empty one
            }
            pool = new Cached(Instant.now(), deduped);
            return deduped;
        }
    }

    private List<RawNewsItem> fetchFeed(String feedId) {
        String url = String.format(FEED_URL, feedId);
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", userAgent,
                            "Accept", "application/rss+xml, application/xml, text/xml"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) {
                return parseFeed(resp.body());
            }
            LOG.debug("CNBC feed {} answered status {}", feedId,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            LOG.debug("CNBC feed {} failed: {}", feedId, e.getMessage());
        }
        return List.of();
    }

    /** One search page; {@code sort} is {@code date} or blank for relevance. */
    private List<RawNewsItem> search(String query, int endindex, int batchsize, String sort) {
        String url = String.format(SEARCH_URL,
                URLEncoder.encode(query, StandardCharsets.UTF_8), endindex, batchsize, sort);
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", userAgent, "Accept", "application/json"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) {
                return parseSearch(resp.body());
            }
            LOG.debug("CNBC search answered status {}", resp == null ? "null" : resp.status());
        } catch (Exception e) {
            LOG.debug("CNBC search failed: {}", e.getMessage());
        }
        return List.of();
    }

    /**
     * Parses a section feed. {@code metadata:id} is the stable identity (the
     * {@code guid} is not a permalink), {@code metadata:sponsored} is CNBC's own
     * paid-placement flag and {@code metadata:type} filters video entries out.
     * Package-private for tests.
     */
    static List<RawNewsItem> parseFeed(String xml) {
        if (xml == null || xml.isBlank()) return List.of();
        List<RawNewsItem> out = new ArrayList<>();
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(xml));
            boolean inItem = false;
            String title = null, link = null, id = null, pubDate = null,
                    description = null, type = null, sponsored = null;
            try {
                while (r.hasNext()) {
                    int event = r.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String ln = r.getLocalName();
                        if ("item".equals(ln)) {
                            inItem = true;
                            title = link = id = pubDate = description = type = sponsored = null;
                        } else if (inItem) {
                            switch (ln) {
                                case "title" -> title = textOf(r);
                                case "link" -> link = textOf(r);
                                case "id" -> id = textOf(r);
                                case "pubDate" -> pubDate = textOf(r);
                                case "description" -> description = textOf(r);
                                case "type" -> type = textOf(r);
                                case "sponsored" -> sponsored = textOf(r);
                                default -> { /* guid, creator - not needed */ }
                            }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT
                            && "item".equals(r.getLocalName())) {
                        inItem = false;
                        if (title == null || title.isEmpty() || link == null || link.isEmpty()) {
                            continue;
                        }
                        if (isVideo(type)) continue;
                        String uuid = id != null && !id.isEmpty() ? id : link;
                        out.add(new RawNewsItem(uuid, title, PUBLISHER, link,
                                parseRssDate(pubDate), List.of(), null,
                                blankToNull(description), "true".equalsIgnoreCase(sponsored),
                                null));
                    }
                }
            } finally {
                r.close();
            }
        } catch (Exception e) {
            LOG.warn("Unparseable CNBC feed: {}", e.getMessage());
            return List.of();
        }
        return out;
    }

    /**
     * Parses a queryly search page ({@code results[]}). Gated pieces keep their
     * headline but are published under {@link #PUBLISHER_GATED} so a body
     * reader can skip them; video entries drop out. Package-private for tests.
     */
    static List<RawNewsItem> parseSearch(String json) {
        if (json == null || json.isBlank()) return List.of();
        List<RawNewsItem> out = new ArrayList<>();
        try {
            JsonNode results = MAPPER.readTree(json).path("results");
            for (JsonNode n : results) {
                String title = text(n, "cn:title");
                String url = text(n, "url");
                if (url == null) url = text(n, "cn:liveURL");
                if (title == null || url == null) continue;
                if (isVideo(text(n, "cn:type"))) continue;

                String id = text(n, "@id");
                String summary = text(n, "summary");
                if (summary == null) summary = text(n, "description");
                out.add(new RawNewsItem(
                        id != null ? id : url,
                        title,
                        isGated(n) ? PUBLISHER_GATED : PUBLISHER,
                        url,
                        parseSearchDate(text(n, "datePublished")),
                        List.of(),
                        null,
                        summary,
                        false,
                        text(n, "cn:promoImage")));
            }
        } catch (Exception e) {
            LOG.warn("Unparseable CNBC search payload: {}", e.getMessage());
            return List.of();
        }
        return out;
    }

    /** True when {@code cn:contentClassification} marks the body as walled. */
    static boolean isGated(JsonNode item) {
        JsonNode cls = item.path("cn:contentClassification");
        if (cls.isArray()) {
            for (JsonNode c : cls) {
                if (GATED.contains(c.asText("").toLowerCase(Locale.ROOT))) return true;
            }
        }
        // A PRO section carries the same wall even when the classification is empty.
        String section = item.path("section").asText("");
        return section.startsWith("PRO:") || section.equals("Investing Club");
    }

    static boolean isVideo(String type) {
        return type != null && type.toLowerCase(Locale.ROOT).contains("video");
    }

    /** Dedupes by article URL, first occurrence wins. Package-private for tests. */
    static List<RawNewsItem> mergeByLink(List<RawNewsItem> first, List<RawNewsItem> second) {
        Map<String, RawNewsItem> byLink = new LinkedHashMap<>();
        for (RawNewsItem it : first) byLink.putIfAbsent(cleanLink(it.link()), it);
        for (RawNewsItem it : second) byLink.putIfAbsent(cleanLink(it.link()), it);
        return List.copyOf(byLink.values());
    }

    /** The article URL without its tracking query - the identity/merge key. */
    static String cleanLink(String link) {
        if (link == null) return "";
        int q = link.indexOf('?');
        return q >= 0 ? link.substring(0, q) : link;
    }

    /** Title plus teaser - CNBC headlines abbreviate, the teaser prints the full name. */
    private static String haystack(RawNewsItem it) {
        return it.summary() == null ? it.title() : it.title() + " " + it.summary();
    }

    /** {@code Sat, 01 Aug 2026 12:16:13 GMT} → {@link Instant}; unparseable → null. */
    static Instant parseRssDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return ZonedDateTime.parse(s.trim(), RSS_DATE).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    /** {@code 2026-07-27T21:10:17+0000} → {@link Instant}; unparseable → null. */
    static Instant parseSearchDate(String s) {
        if (s == null || s.isBlank()) return null;
        String v = s.trim();
        try {
            return OffsetDateTime.parse(v, SEARCH_DATE).toInstant();
        } catch (Exception e) {
            try {
                return OffsetDateTime.parse(v).toInstant();
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    /** ISO date → UTC start of day; unparseable → null. */
    static Instant startOfDay(String isoDate) {
        if (isoDate == null || isoDate.isBlank()) return null;
        try {
            return LocalDate.parse(isoDate.trim()).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (Exception e) {
            return null;
        }
    }

    /** True when the text carries at least one significant word of the queried name. */
    static boolean titleMatches(String text, Set<String> nameWords) {
        if (text == null || nameWords.isEmpty()) return false;
        String t = normalize(text);
        for (String w : nameWords) {
            if (t.matches(".*\\b" + Pattern.quote(w) + "\\b.*")) return true;
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

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return null;
        String s = v.asText("").trim();
        return s.isEmpty() ? null : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String textOf(XMLStreamReader r) throws Exception {
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
