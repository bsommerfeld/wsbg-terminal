package de.bsommerfeld.wsbg.terminal.prnewswire;

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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Company press releases via PR Newswire UK's keyless all-releases RSS feed
 * ({@code www.prnewswire.co.uk/rss/news-releases-list.rss}) — the newswire
 * niche the press-scanning legs miss: the issuer's OWN announcements
 * (contract wins, milestones, buyback notices), minutes-fresh, 24/7, no
 * wall for HTML/RSS (live-probed 2026-07-14, plain client 200).
 *
 * <p><b>This source is a FIREHOSE, not a search:</b> there is no per-company
 * RSS (per-company HTML pages exist but slugs aren't guessable, and search is
 * a JS shell), so the ONE all-news feed (~40-50 items) is fetched, parsed and
 * cached as a POOL shared across all queries (10-min politeness TTL), and
 * {@link #newsForName} filters that pool by title relevance exactly like
 * GoogleNewsClient (significant words, umlaut-tolerant, precision over
 * recall — a generic name must not flood the pool). {@link #newsFor} and
 * {@link #newsForIsin} stay no-ops: the feed tags neither tickers nor ISINs,
 * companies only surface by name.
 *
 * <p><b>The soft-200 trap (pinned 2026-07-14):</b> unknown {@code /rss/*.rss}
 * paths answer 200-shaped non-feed pages (observed as the identical all-news
 * body OR a 200-delivered 404 HTML shell), so a 200 proves nothing — only
 * content does. The body is validated as RSS before parsing; an HTML answer
 * is a miss that is never cached.
 *
 * <p>Item fields (pinned live 2026-07-14): {@code <link>}/{@code <guid>} are
 * the direct release URL on {@code prnewswire.co.uk} (keyless clean full text
 * — the article digester reads them), {@code <pubDate>} is RFC-1123 with a
 * numeric offset, {@code <dc:contributor>} carries the issuing organisation
 * on every item (that is the publisher; "PR Newswire UK" is only the
 * fallback), {@code <description>} is a CDATA HTML teaser (stripped into
 * {@link RawNewsItem#summary()}).
 */
@Singleton
public class PrNewswireUkClient implements NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(PrNewswireUkClient.class);

    private static final String FEED_URL =
            "https://www.prnewswire.co.uk/rss/news-releases-list.rss";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final String FALLBACK_PUBLISHER = "PR Newswire UK";

    /** Generic words that must never carry the title-relevance match alone. */
    private static final Set<String> NAME_STOP = Set.of(
            "the", "and", "und", "inc", "incorporated", "corp", "corporation", "co",
            "company", "ag", "se", "plc", "ltd", "limited", "nv", "sa", "spa",
            "holding", "holdings", "group", "international", "aktie", "aktien");

    /** Hardened StAX factory (XXE off — this is a remote feed), reused for every parse. */
    private static final XMLInputFactory XML_FACTORY = newHardenedFactory();

    private final String userAgent = BrowserUserAgent.random();
    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(12);

    /** The shared pool: the parsed all-news feed, refreshed at most once per TTL. */
    private volatile CachedPool pool;

    private record CachedPool(Instant fetchedAt, List<RawNewsItem> items) {}

    /** Test/default: plain direct transport. */
    public PrNewswireUkClient() {
        this(new DirectWebFetcher());
    }

    /**
     * Production: the {@code @DirectFirst} seam — the feed answers a bare
     * client with no wall (live-probed 2026-07-14), so this client declares
     * "fine on plain HTTP" through the policy annotation like the other
     * keyless no-wall sources.
     */
    @Inject
    public PrNewswireUkClient(
            @de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String sourceName() {
        return "prnewswire-uk";
    }

    /** No-op: the feed tags no tickers — companies only surface by name. */
    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        return List.of();
    }

    /** No-op: the feed tags no ISINs — companies only surface by name. */
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
        for (RawNewsItem item : currentPool()) {
            if (titleMatches(item.title(), words)) out.add(item);
        }
        return cap(out, limit);
    }

    /**
     * The shared, TTL-cached pool. Synchronized so a burst of queries makes
     * exactly ONE feed request; a non-RSS or failed answer is never cached
     * (the next call retries), and an outage keeps the stale pool.
     */
    private synchronized List<RawNewsItem> currentPool() {
        CachedPool cached = pool;
        if (cached != null && cached.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cached.items();
        }
        try {
            WebResponse resp = fetcher.fetch(FEED_URL,
                    Map.of("User-Agent", userAgent,
                            "Accept", "application/rss+xml, application/xml, text/xml"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) {
                String body = resp.body();
                if (!looksLikeRss(body)) {
                    // The soft-200 trap: unknown /rss paths and error pages answer
                    // 200-shaped HTML — a status proves nothing, only content does.
                    LOG.debug("PR Newswire UK answered a 200 that is not RSS "
                            + "(soft-200 trap) — treating as a miss, not caching");
                    return stale(cached);
                }
                List<RawNewsItem> items = parse(body);
                pool = new CachedPool(Instant.now(), items);
                return items;
            }
            LOG.debug("PR Newswire UK feed answered status {}",
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            LOG.debug("PR Newswire UK feed fetch failed: {}", e.getMessage());
        }
        return stale(cached);
    }

    /** An outage serves the stale pool rather than an empty answer. */
    private static List<RawNewsItem> stale(CachedPool cached) {
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
     * RSS 2.0 items → {@link RawNewsItem}s, unfiltered (the pool caches the
     * whole feed; relevance is applied per query). Garbage yields empty,
     * never throws. Package-private for tests.
     */
    static List<RawNewsItem> parse(String xml) {
        if (xml == null || xml.isBlank()) return List.of();
        List<RawNewsItem> out = new ArrayList<>();
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(xml));
            boolean inItem = false;
            String title = null, link = null, guid = null, pubDate = null;
            String description = null, contributor = null;
            String current = null;
            try {
                while (r.hasNext()) {
                    int event = r.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String ln = r.getLocalName();
                        if ("item".equals(ln)) {
                            inItem = true;
                            title = link = guid = pubDate = description = contributor = null;
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
                            // dc:contributor — the issuing organisation.
                            case "contributor" -> contributor = append(contributor, text);
                            default -> { /* prn:industry, dc:publisher etc. — ignored */ }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        if ("item".equals(r.getLocalName())) {
                            inItem = false;
                            RawNewsItem item =
                                    toItem(title, link, guid, pubDate, description, contributor);
                            if (item != null) out.add(item);
                        }
                        current = inItem ? null : current;
                    }
                }
            } finally {
                r.close();
            }
        } catch (Exception e) {
            LOG.debug("PR Newswire UK RSS parse failed: {}", e.getMessage());
            return List.copyOf(out);
        }
        return out;
    }

    private static String append(String existing, String text) {
        return existing == null ? text : existing + text;
    }

    /** One parsed {@code <item>} → a {@link RawNewsItem}, or null when incomplete. */
    private static RawNewsItem toItem(String title, String link, String guid,
                                      String pubDate, String description, String contributor) {
        if (title == null || title.isBlank() || link == null || link.isBlank()) return null;
        String publisher = contributor == null || contributor.isBlank()
                ? FALLBACK_PUBLISHER : contributor.strip();
        String cleanLink = link.strip();
        String teaser = stripHtml(description);
        return new RawNewsItem(
                guid != null && !guid.isBlank() ? guid.strip() : cleanLink,
                title.strip(),
                publisher,
                cleanLink,
                parsePubDate(pubDate),
                List.of(),
                null,
                teaser == null || teaser.isBlank() ? null : teaser,
                false);
    }

    /** The CDATA teaser's HTML tags stripped, entities decoded, whitespace collapsed. */
    static String stripHtml(String html) {
        if (html == null) return null;
        return html.replaceAll("<[^>]+>", " ")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    /**
     * RFC-1123 pubDate ("Mon, 13 Jul 2026 21:19:00 +0000") → {@link Instant};
     * unparseable → null. Lenient about the day-of-week token (strict RFC-1123
     * rejects a weekday that doesn't match the date — the date wins).
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

    /** True when the title carries at least one significant word of the queried name. */
    static boolean titleMatches(String title, Set<String> nameWords) {
        if (nameWords.isEmpty()) return false;
        String t = normalize(title);
        for (String w : nameWords) {
            if (t.matches(".*\\b" + Pattern.quote(w) + "\\b.*")) return true;
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
