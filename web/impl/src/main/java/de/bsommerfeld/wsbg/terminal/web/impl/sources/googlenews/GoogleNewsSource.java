package de.bsommerfeld.wsbg.terminal.web.impl.sources.googlenews;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import de.bsommerfeld.wsbg.terminal.web.impl.feed.FeedParser;
import de.bsommerfeld.wsbg.terminal.web.impl.text.TextMatch;
import de.bsommerfeld.wsbg.terminal.web.instrument.ResolvedInstrument;
import de.bsommerfeld.wsbg.terminal.web.source.AbstractWebSource;
import de.bsommerfeld.wsbg.terminal.web.source.ArchiveSource;
import de.bsommerfeld.wsbg.terminal.web.source.InstrumentSource;
import de.bsommerfeld.wsbg.terminal.web.source.SearchEngine;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * German financial-press news via Google News' keyless RSS search
 * ({@code news.google.com/rss/search?q=<query>&hl=de&gl=DE&ceid=DE:de}) — one
 * query returns up to ~100 fresh items across the whole German press.
 *
 * <p>Both doors of the new world in one source: as an
 * {@link InstrumentSource} it fans the resolved keys additively — the
 * {@code "<name> Aktie"} query (query bias only; title relevance is still
 * matched against the NAME, precision over recall) beside the ISIN full-text
 * query (NO title filter: an ISIN query is precise by construction, and
 * disclosure titles rarely repeat the ISIN). As a {@link SearchEngine} it
 * carries the free research query verbatim. As an {@link ArchiveSource} it
 * narrows the same name search to a date window via Google News'
 * {@code after:}/{@code before:} query operators - the multi-year press
 * history.
 *
 * <p>Transport {@code DIRECT,BROWSER}: the direct RSS path is what actually
 * delivers (the news.google.com anchor never becomes session-ready behind the
 * consent wall); Google's consent/captcha pages arrive as HTTP 200 HTML, so a
 * non-RSS 200 trips a WARN loud enough to decide the source's removal on
 * evidence instead of starving silently.
 *
 * <p>Politeness: the parsed result is cached per query for 5 minutes — an
 * inquiry may fan the same name from several places in a burst, and Google
 * should see one request for it.
 */
@Singleton
public final class GoogleNewsSource extends AbstractWebSource
        implements InstrumentSource, SearchEngine, ArchiveSource {

    private static final Logger LOG = LoggerFactory.getLogger(GoogleNewsSource.class);

    private static final String SEARCH_URL = "https://news.google.com/rss/search";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    /** Hardened StAX factory (XXE off — this is a remote feed), reused for every parse. */
    private static final XMLInputFactory XML_FACTORY = newHardenedFactory();

    /** Per-query politeness cache: parsed, relevance-filtered, uncapped items. */
    private final Map<String, CachedResult> cache = new ConcurrentHashMap<>();

    private record CachedResult(Instant fetchedAt, List<Article> items) {}

    @Inject
    public GoogleNewsSource(WebFetcher fetcher) {
        super(fetcher);
    }

    @Override
    public String sourceName() {
        return "google-news";
    }

    /**
     * The HOME edition declares NO sphere on purpose: its members are German
     * houses weighed as independent of each other; stamping them all "DE"
     * would silently re-read every second German confirmation as an echo.
     */
    @Override
    public FetchUtil[] mode() {
        return new FetchUtil[] {FetchUtil.DIRECT, FetchUtil.BROWSER};
    }

    /** Additive key fan: name query beside ISIN query, de-duplicated. */
    @Override
    public List<Article> newsFor(ResolvedInstrument instrument, int limit) {
        if (limit <= 0) return List.of();
        Map<String, Article> merged = new LinkedHashMap<>();
        String name = instrument.name();
        if (name != null && !name.isBlank()) {
            for (Article a : query(cleanName(name) + " Aktie", name, limit)) {
                merged.putIfAbsent(a.uuid(), a);
            }
        }
        instrument.isin().ifPresent(isin -> {
            for (Article a : query(isin.value(), null, limit)) {
                merged.putIfAbsent(a.uuid(), a);
            }
        });
        List<Article> out = new ArrayList<>(merged.values());
        return out.size() <= limit ? out : List.copyOf(out.subList(0, limit));
    }

    /**
     * Multi-year press HISTORY: the same search narrowed to a date window via
     * Google News' {@code after:}/{@code before:} query operators - the
     * long-term dossier needs the years, not just the weeks (user mandate
     * 2026-07-16). Same transport, cache and consent tripwire as every other
     * query; the window rides in the cache key. Name-addressed like the rest
     * of the search - the archive leg takes no ISIN query.
     */
    @Override
    public List<Article> newsForWindow(ResolvedInstrument instrument,
            LocalDate fromDate, LocalDate toDateExclusive, int limit) {
        String name = instrument.name();
        if (name == null || name.isBlank() || limit <= 0) return List.of();
        String q = cleanName(name) + " Aktie after:" + fromDate
                + " before:" + toDateExclusive;
        return query(q, name, limit);
    }

    /** Free research query, verbatim, unfiltered — the caller phrased it. */
    @Override
    public List<Article> search(String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) return List.of();
        return query(query, null, limit);
    }

    private List<Article> query(String query, String relevanceName, int limit) {
        String cacheKey = query.toLowerCase(Locale.ROOT);
        CachedResult cached = cache.get(cacheKey);
        if (cached != null && cached.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cap(cached.items(), limit);
        }
        try {
            WebResponse resp = get(
                    SEARCH_URL + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                            + "&hl=de&gl=DE&ceid=DE:de",
                    Map.of("Accept", "application/rss+xml, application/xml, text/xml"),
                    Duration.ofSeconds(12));
            if (resp.status() == 200) {
                String body = resp.body();
                if (!FeedParser.looksLikeFeed(body)) {
                    // Consent/captcha pages arrive as HTTP 200 — without this
                    // tripwire the source would starve silently.
                    LOG.warn("Google News answered a 200 that is not RSS for '{}' — "
                            + "consent/captcha wall, source is starving", query);
                    return List.of();
                }
                List<Article> items = parse(body, relevanceName);
                cache.put(cacheKey, new CachedResult(Instant.now(), items));
                return cap(items, limit);
            }
            LOG.debug("Google News search for '{}' answered status {}", query, resp.status());
        } catch (Exception e) {
            LOG.debug("Google News search failed for '{}': {}", query, e.getMessage());
        }
        return List.of();
    }

    private static List<Article> cap(List<Article> items, int limit) {
        return items.size() <= limit ? items : List.copyOf(items.subList(0, limit));
    }

    /**
     * Cleaned query form of a canonical name: comma-cut ("Amazon.com, Inc." →
     * "Amazon.com") and trailing legal suffixes stripped — a full legal name
     * plus " Aktie" is a query no headline writer ever phrases.
     */
    static String cleanName(String name) {
        String raw = name.trim();
        String cut = raw.contains(",") ? raw.substring(0, raw.indexOf(',')).trim() : raw;
        String[] words = cut.split("\\s+");
        int end = words.length;
        while (end > 1 && TextMatch.generic(words[end - 1])) {
            end--;
        }
        String cleaned = String.join(" ", java.util.Arrays.copyOfRange(words, 0, end)).trim();
        return cleaned.isEmpty() ? raw : cleaned;
    }

    /**
     * Google-specific parse: {@code <source>} is the per-item publisher, and
     * the {@code " - <publisher>"} tail Google appends to every title is
     * stripped when it matches. Garbage yields empty, never throws.
     * Package-private for tests.
     */
    static List<Article> parse(String xml, String relevanceName) {
        if (xml == null || xml.isBlank()) return List.of();
        Set<String> words = TextMatch.significantWords(relevanceName);
        List<Article> out = new ArrayList<>();
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(xml));
            boolean inItem = false;
            String title = null, link = null, guid = null, pubDate = null, source = null;
            try {
                while (r.hasNext()) {
                    int event = r.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String ln = r.getLocalName();
                        if ("item".equals(ln)) {
                            inItem = true;
                            title = link = guid = pubDate = source = null;
                        } else if (inItem) {
                            switch (ln) {
                                case "title" -> title = textOf(r);
                                case "link" -> link = textOf(r);
                                case "guid" -> guid = textOf(r);
                                case "pubDate" -> pubDate = textOf(r);
                                case "source" -> source = textOf(r);
                                default -> { /* description etc. — ignored */ }
                            }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT
                            && "item".equals(r.getLocalName())) {
                        inItem = false;
                        Article item = toArticle(title, link, guid, pubDate, source, words);
                        if (item != null) out.add(item);
                    }
                }
            } finally {
                r.close();
            }
        } catch (Exception e) {
            LOG.warn("Unparseable Google News feed for '{}': {}", relevanceName, e.getMessage());
            return List.of();
        }
        return out;
    }

    /** One parsed {@code <item>} → an {@link Article}, or null when incomplete/off-topic. */
    private static Article toArticle(String title, String link, String guid,
            String pubDate, String source, Set<String> nameWords) {
        if (title == null || title.isEmpty() || link == null || link.isEmpty()) return null;
        String publisher = source == null ? "" : source;
        // Google appends " - <publisher>" to every title; strip it when it matches.
        if (!publisher.isEmpty() && title.endsWith(" - " + publisher)) {
            title = title.substring(0, title.length() - publisher.length() - 3).trim();
        }
        if (!titleMatches(title, nameWords)) return null; // precision: the title must name the company
        return new Article(
                guid != null && !guid.isEmpty() ? guid : link,
                title,
                publisher,
                link,
                FeedParser.parseDate(pubDate),
                List.of());
    }

    /**
     * True when the title carries a significant word of the queried name.
     * Empty words = NO filter requested (the ISIN and free queries are precise
     * by construction). A title in another SCRIPT cannot be tested against a
     * Latin name — where the test cannot be applied, the QUERY carried the
     * precision (Google matched the name in the article's full text to return
     * it at all).
     */
    static boolean titleMatches(String title, Set<String> nameWords) {
        if (nameWords.isEmpty()) return true;
        if (TextMatch.matchesAny(title, nameWords)) return true;
        return !hasLatinLetter(title);
    }

    /** True when the string carries at least one a-z/A-Z letter. */
    private static boolean hasLatinLetter(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) return true;
        }
        return false;
    }

    /** Reads the element's text content, trimmed. Safe for text-only elements. */
    private static String textOf(XMLStreamReader r) throws Exception {
        String t = r.getElementText();
        return t != null ? t.trim() : null;
    }

    private static XMLInputFactory newHardenedFactory() {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        return f;
    }
}
