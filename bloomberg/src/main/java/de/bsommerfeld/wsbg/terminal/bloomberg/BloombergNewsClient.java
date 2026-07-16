package de.bsommerfeld.wsbg.terminal.bloomberg;

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
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Bloomberg wire headlines via the keyless vertical RSS feeds on
 * {@code feeds.bloomberg.com} (markets, economics, technology, politics,
 * wealth — all live-probed 2026-07-16, plain client 200 after a same-scheme
 * 301 the transport follows). The flagship US newswire the press-scanning
 * legs were missing: sourced reporting ("according to people familiar"),
 * minutes-fresh, with a real one-sentence teaser per item; articles
 * themselves are paywalled — the headline + teaser are the value.
 *
 * <p><b>This source is a FIREHOSE, not a search:</b> Bloomberg exposes no
 * per-instrument feed, so each vertical (~20-30 items) is fetched, parsed and
 * cached as a per-feed POOL shared across all queries (10-min politeness
 * TTL), and {@link #newsForName} filters the pooled union by relevance
 * against title AND teaser (significant words, umlaut-tolerant, precision
 * over recall) — the teaser matters: Bloomberg headlines often carry the
 * colloquial handle ("Citi", "Ladbrokes Owner") while the teaser prints the
 * legal name ("Citigroup", "Entain Plc"). A story syndicated into several
 * verticals is deduplicated by its wire id. {@link #newsFor} and
 * {@link #newsForIsin} stay no-ops: the feed tags neither tickers nor ISINs.
 *
 * <p>Item fields (pinned live 2026-07-16): CDATA {@code <title>} and
 * {@code <description>} (plain-text teaser, no HTML inside), {@code <link>}
 * is the direct article URL, {@code <guid isPermaLink="false">} is the wire's
 * own story id (the dedupe key across verticals), {@code <pubDate>} is
 * RFC-1123 GMT, {@code <dc:creator>} the byline.
 */
@Singleton
public class BloombergNewsClient implements NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(BloombergNewsClient.class);

    /** The finance-relevant verticals, live-probed 2026-07-16 (see class doc). */
    private static final List<String> FEEDS = List.of(
            "https://feeds.bloomberg.com/markets/news.rss",
            "https://feeds.bloomberg.com/economics/news.rss",
            "https://feeds.bloomberg.com/technology/news.rss",
            "https://feeds.bloomberg.com/politics/news.rss",
            "https://feeds.bloomberg.com/wealth/news.rss");
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final String PUBLISHER = "Bloomberg";

    /** Generic words that must never carry the relevance match alone. */
    private static final Set<String> NAME_STOP = Set.of(
            "the", "and", "und", "inc", "incorporated", "corp", "corporation", "co",
            "company", "ag", "se", "plc", "ltd", "limited", "nv", "sa", "spa",
            "holding", "holdings", "group", "international", "aktie", "aktien");

    /** Hardened StAX factory (XXE off — this is a remote feed), reused for every parse. */
    private static final XMLInputFactory XML_FACTORY = newHardenedFactory();

    private final String userAgent = BrowserUserAgent.random();
    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(12);

    /** The shared pools: one parsed feed per vertical, refreshed at most once per TTL. */
    private final Map<String, CachedPool> pools = new java.util.concurrent.ConcurrentHashMap<>();

    private record CachedPool(Instant fetchedAt, List<WireItem> items) {}

    /**
     * One wire story: the emitted item plus the searchable text (title +
     * teaser) the name matcher runs against — the teaser carries the legal
     * name the headline abbreviates.
     */
    record WireItem(RawNewsItem item, String searchText) {}

    /** Test/default: plain direct transport. */
    public BloombergNewsClient() {
        this(new DirectWebFetcher());
    }

    /**
     * Production: the {@code @DirectFirst} seam — the feed host answers a
     * bare client with no wall (live-probed 2026-07-16), so this client
     * declares "fine on plain HTTP" through the policy annotation like the
     * other keyless no-wall sources.
     */
    @Inject
    public BloombergNewsClient(
            @de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String sourceName() {
        return "bloomberg";
    }

    /** No-op: the feeds tag no tickers — companies only surface by name. */
    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        return List.of();
    }

    /** No-op: the feeds tag no ISINs — companies only surface by name. */
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
        for (WireItem w : currentPool()) {
            if (textMatches(w.searchText(), words)) out.add(w.item());
        }
        return cap(out, limit);
    }

    /**
     * The union of all vertical pools, each TTL-cached, deduplicated by wire
     * id (a story syndicates into several verticals). Synchronized so a burst
     * of queries makes at most ONE request per feed; a non-RSS or failed
     * answer is never cached (the next call retries), and an outage keeps
     * serving that vertical's stale pool without touching the others.
     */
    private synchronized List<WireItem> currentPool() {
        List<WireItem> union = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String feed : FEEDS) {
            for (WireItem w : feedPool(feed)) {
                if (seen.add(w.item().uuid())) union.add(w);
            }
        }
        return union;
    }

    private List<WireItem> feedPool(String url) {
        CachedPool cached = pools.get(url);
        if (cached != null && cached.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cached.items();
        }
        try {
            WebResponse resp = fetcher.fetch(url,
                    Map.of("User-Agent", userAgent,
                            "Accept", "application/rss+xml, application/xml, text/xml"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) {
                String body = resp.body();
                if (!looksLikeRss(body)) {
                    LOG.debug("Bloomberg feed {} answered a 200 that is not RSS — "
                            + "treating as a miss, not caching", url);
                    return stale(cached);
                }
                List<WireItem> items = parse(body);
                pools.put(url, new CachedPool(Instant.now(), items));
                return items;
            }
            LOG.debug("Bloomberg feed {} answered status {}", url,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            LOG.debug("Bloomberg feed {} fetch failed: {}", url, e.getMessage());
        }
        return stale(cached);
    }

    /** An outage serves the stale pool rather than an empty answer. */
    private static List<WireItem> stale(CachedPool cached) {
        return cached == null ? List.of() : cached.items();
    }

    private static List<RawNewsItem> cap(List<RawNewsItem> items, int limit) {
        return items.size() <= limit ? items : List.copyOf(items.subList(0, limit));
    }

    /** A 200 is only a feed when the body is actually RSS/XML — error shells are HTML. */
    static boolean looksLikeRss(String body) {
        if (body == null) return false;
        String head = body.stripLeading();
        if (head.length() > 512) head = head.substring(0, 512);
        String lower = head.toLowerCase(Locale.ROOT);
        return lower.startsWith("<?xml") || lower.startsWith("<rss");
    }

    /**
     * RSS 2.0 items → {@link WireItem}s, unfiltered (the pool caches the
     * whole feed; relevance is applied per query). Garbage yields empty,
     * never throws. Package-private for tests.
     */
    static List<WireItem> parse(String xml) {
        if (xml == null || xml.isBlank()) return List.of();
        List<WireItem> out = new ArrayList<>();
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(xml));
            boolean inItem = false;
            String title = null, link = null, guid = null, pubDate = null, description = null;
            String current = null;
            try {
                while (r.hasNext()) {
                    int event = r.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String ln = r.getLocalName();
                        if ("item".equals(ln)) {
                            inItem = true;
                            title = link = guid = pubDate = description = null;
                        }
                        current = inItem ? ln : null;
                    } else if (event == XMLStreamConstants.CHARACTERS
                            || event == XMLStreamConstants.CDATA) {
                        if (!inItem || current == null) continue;
                        String text = r.getText();
                        switch (current) {
                            case "title" -> title = append(title, text);
                            case "link" -> link = append(link, text);
                            case "guid" -> guid = append(guid, text);
                            case "pubDate" -> pubDate = append(pubDate, text);
                            case "description" -> description = append(description, text);
                            default -> { /* dc:creator etc. — ignored */ }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        if ("item".equals(r.getLocalName())) {
                            inItem = false;
                            WireItem item = toItem(title, link, guid, pubDate, description);
                            if (item != null) out.add(item);
                        }
                        current = inItem ? null : current;
                    }
                }
            } finally {
                r.close();
            }
        } catch (Exception e) {
            LOG.debug("Bloomberg RSS parse failed: {}", e.getMessage());
            return List.copyOf(out);
        }
        return out;
    }

    private static String append(String existing, String text) {
        return existing == null ? text : existing + text;
    }

    /** One parsed {@code <item>} → a {@link WireItem}, or null when incomplete. */
    private static WireItem toItem(String title, String link, String guid,
                                   String pubDate, String description) {
        if (title == null || title.isBlank() || link == null || link.isBlank()) return null;
        String cleanTitle = collapse(title);
        String teaser = collapse(description);
        String cleanLink = link.strip();
        RawNewsItem item = new RawNewsItem(
                guid != null && !guid.isBlank() ? guid.strip() : cleanLink,
                cleanTitle,
                PUBLISHER,
                cleanLink,
                parsePubDate(pubDate),
                List.of(),
                null,
                teaser == null || teaser.isBlank() ? null : teaser,
                false);
        String searchText = teaser == null ? cleanTitle : cleanTitle + " " + teaser;
        return new WireItem(item, searchText);
    }

    private static String collapse(String s) {
        return s == null ? null : s.replaceAll("\\s+", " ").strip();
    }

    /**
     * RFC-1123 pubDate ("Thu, 16 Jul 2026 16:40:16 GMT") → {@link Instant};
     * unparseable → null. Lenient about the day-of-week token (strict
     * RFC-1123 rejects a weekday that doesn't match the date — the date wins).
     */
    static Instant parsePubDate(String pubDate) {
        if (pubDate == null || pubDate.isBlank()) return null;
        String s = pubDate.trim();
        try {
            return ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (Exception strict) {
            try {
                int comma = s.indexOf(',');
                return ZonedDateTime.parse(comma >= 0 ? s.substring(comma + 1).trim() : s,
                        RFC_1123_NO_DOW).toInstant();
            } catch (Exception e) {
                return null;
            }
        }
    }

    /** RFC-1123 without the day-of-week prefix, English month names. */
    private static final DateTimeFormatter RFC_1123_NO_DOW =
            DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss zzz", Locale.ENGLISH);

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

    private static XMLInputFactory newHardenedFactory() {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        return f;
    }
}
