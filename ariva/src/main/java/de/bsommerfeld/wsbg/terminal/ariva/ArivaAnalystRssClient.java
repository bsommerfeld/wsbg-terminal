package de.bsommerfeld.wsbg.terminal.ariva;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sell-side ANALYST RATINGS via Ariva's keyless dpa-AFX-Analyser RSS
 * ({@code www.ariva.de/news/analysen/rss}) — a news GENRE no other source
 * carries as a feed: price-target changes and up/downgrades for German and
 * European names ("Berenberg hebt Ziel für Richemont auf 170 Franken"),
 * minutes-fresh, German-language (live-probed 2026-07-16, plain client 200).
 *
 * <p><b>This source is a FIREHOSE, not a search:</b> the one analyst feed
 * (~25 items) is fetched, parsed and cached as a POOL shared across all
 * queries (10-min politeness TTL). <b>Dual addressing:</b> the item link's
 * {@code utm_content} parameter carries the covered instrument's ISIN
 * (pinned live 2026-07-16), so {@link #newsForIsin} matches exactly; titles
 * name the company ("… für Richemont …"), so {@link #newsForName} applies
 * the house precision filter (significant words, umlaut-tolerant) as a
 * fallback for subjects that resolved without an ISIN. {@link #newsFor}
 * stays a no-op (no ticker tagging).
 *
 * <p>Item fields (pinned live 2026-07-16): {@code <title>} = the rating
 * headline, {@code <description>} = the study teaser ending in an
 * {@code <a …>[weiter]</a>} link (stripped from the summary), {@code <link>}
 * = article URL with utm tracking (the query part is cut — the clean URL is
 * the uuid, and utm_content is mined for the ISIN first), {@code <pubDate>}
 * = "16 Jul 2026 13:13:08 +0200" (RFC-1123 without the day-of-week token),
 * {@code <enclosure>} = article image.
 */
@Singleton
public class ArivaAnalystRssClient implements NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(ArivaAnalystRssClient.class);

    private static final String FEED_URL = "https://www.ariva.de/news/analysen/rss";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final String PUBLISHER = "dpa-AFX Analyser";

    /** The covered instrument's ISIN rides the link's utm_content parameter. */
    private static final Pattern LINK_ISIN =
            Pattern.compile("[?&]utm_content=([A-Z]{2}[A-Z0-9]{9}[0-9])(?:[&#]|$)");

    /** The trailing "[weiter]" read-more anchor inside the description HTML. */
    private static final Pattern READ_MORE =
            Pattern.compile("<a\\s[^>]*>\\s*\\[weiter]\\s*</a>", Pattern.CASE_INSENSITIVE);

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

    /** The shared pool: the parsed analyst feed, refreshed at most once per TTL. */
    private volatile CachedPool pool;

    private record CachedPool(Instant fetchedAt, List<RawNewsItem> items) {}

    /** Test/default: plain direct transport. */
    public ArivaAnalystRssClient() {
        this(new DirectWebFetcher());
    }

    /**
     * Production: the {@code @DirectFirst} seam — the feed answers a bare
     * client with no wall (live-probed 2026-07-16), so this client declares
     * "fine on plain HTTP" like the other keyless no-wall sources.
     */
    @Inject
    public ArivaAnalystRssClient(
            @de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String sourceName() {
        return "ariva-analysten";
    }

    /** No-op: the feed tags ISINs (via utm_content), never ticker symbols. */
    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        return List.of();
    }

    @Override
    public List<RawNewsItem> newsForIsin(String isin, int limit) {
        if (isin == null || isin.isBlank() || limit <= 0) return List.of();
        String wanted = isin.strip().toUpperCase(Locale.ROOT);
        List<RawNewsItem> out = new ArrayList<>();
        for (RawNewsItem item : currentPool()) {
            if (wanted.equals(item.isin())) {
                out.add(item);
                if (out.size() >= limit) break;
            }
        }
        return List.copyOf(out);
    }

    @Override
    public List<RawNewsItem> newsForName(String companyName, int limit) {
        if (companyName == null || companyName.isBlank() || limit <= 0) return List.of();
        Set<String> words = significantWords(companyName);
        if (words.isEmpty()) return List.of();
        List<RawNewsItem> out = new ArrayList<>();
        for (RawNewsItem item : currentPool()) {
            // Title AND study teaser: the headline carries the rating verb,
            // the teaser the analyst's reasoning — a name can sit in either
            // (mandate 2026-07-16: scan everything a source hands over).
            String search = item.summary() == null
                    ? item.title() : item.title() + " " + item.summary();
            if (titleMatches(search, words)) {
                out.add(item);
                if (out.size() >= limit) break;
            }
        }
        return List.copyOf(out);
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
                    LOG.debug("Ariva analyst feed answered a 200 that is not RSS — "
                            + "treating as a miss, not caching");
                    return stale(cached);
                }
                List<RawNewsItem> items = parse(body);
                pool = new CachedPool(Instant.now(), items);
                return items;
            }
            LOG.debug("Ariva analyst feed answered status {}",
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            LOG.debug("Ariva analyst feed fetch failed: {}", e.getMessage());
        }
        return stale(cached);
    }

    /** An outage serves the stale pool rather than an empty answer. */
    private static List<RawNewsItem> stale(CachedPool cached) {
        return cached == null ? List.of() : cached.items();
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
     * whole feed; relevance/ISIN filters are applied per query). Garbage
     * yields empty, never throws. Package-private for tests.
     */
    static List<RawNewsItem> parse(String xml) {
        if (xml == null || xml.isBlank()) return List.of();
        List<RawNewsItem> out = new ArrayList<>();
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(xml));
            boolean inItem = false;
            String title = null, link = null, pubDate = null, description = null;
            String imageUrl = null;
            String current = null;
            try {
                while (r.hasNext()) {
                    int event = r.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String ln = r.getLocalName();
                        if ("item".equals(ln)) {
                            inItem = true;
                            title = link = pubDate = description = imageUrl = null;
                        } else if (inItem && "enclosure".equals(ln)) {
                            String url = r.getAttributeValue(null, "url");
                            if (url != null && !url.isBlank()) imageUrl = url.strip();
                        }
                        current = inItem ? ln : null;
                    } else if (event == XMLStreamConstants.CHARACTERS
                            || event == XMLStreamConstants.CDATA) {
                        if (!inItem || current == null) continue;
                        String text = r.getText();
                        switch (current) {
                            case "title" -> title = append(title, text);
                            case "link" -> link = append(link, text);
                            case "pubDate" -> pubDate = append(pubDate, text);
                            case "description" -> description = append(description, text);
                            default -> { /* ignored */ }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        if ("item".equals(r.getLocalName())) {
                            inItem = false;
                            RawNewsItem item =
                                    toItem(title, link, pubDate, description, imageUrl);
                            if (item != null) out.add(item);
                        }
                        current = inItem ? null : current;
                    }
                }
            } finally {
                r.close();
            }
        } catch (Exception e) {
            LOG.debug("Ariva analyst RSS parse failed: {}", e.getMessage());
            return List.copyOf(out);
        }
        return out;
    }

    private static String append(String existing, String text) {
        return existing == null ? text : existing + text;
    }

    /** One parsed {@code <item>} → a {@link RawNewsItem}, or null when incomplete. */
    private static RawNewsItem toItem(String title, String link, String pubDate,
                                      String description, String imageUrl) {
        if (title == null || title.isBlank() || link == null || link.isBlank()) return null;
        String rawLink = link.strip();
        String isin = extractIsin(rawLink);
        String cleanLink = stripQuery(rawLink);
        String teaser = stripHtml(READ_MORE.matcher(
                description == null ? "" : description).replaceAll(""));
        return new RawNewsItem(
                cleanLink,
                title.strip(),
                PUBLISHER,
                cleanLink,
                ArivaForumRssClient.parsePubDate(pubDate),
                List.of(),
                isin,
                teaser == null || teaser.isBlank() ? null : teaser,
                false,
                imageUrl);
    }

    /** The covered instrument's ISIN from the link's utm_content, or null. */
    static String extractIsin(String link) {
        if (link == null) return null;
        Matcher m = LINK_ISIN.matcher(link);
        return m.find() ? m.group(1) : null;
    }

    /** The utm tracking query cut — the clean article URL is link and uuid. */
    static String stripQuery(String link) {
        if (link == null) return null;
        int q = link.indexOf('?');
        return q < 0 ? link : link.substring(0, q);
    }

    /** The teaser's HTML tags stripped, entities decoded, whitespace collapsed. */
    static String stripHtml(String html) {
        if (html == null) return null;
        return html.replaceAll("<[^>]+>", " ")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

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
