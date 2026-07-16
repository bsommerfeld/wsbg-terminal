package de.bsommerfeld.wsbg.terminal.reuters;

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
import java.util.regex.Pattern;

/**
 * Reuters wire headlines via the Arc news-sitemap
 * ({@code www.reuters.com/arc/outboundfeeds/news-sitemap/?outputType=xml}) —
 * the one keyless door left into the flagship global newswire: the classic
 * RSS endpoints are dead (401/404) and the article site is bot-walled, but
 * the Google-News sitemap the site publishes for crawlers answers a plain
 * client 200 (live-probed 2026-07-16) with the freshest ~50 stories: article
 * URL, exact publication instant and the full headline as
 * {@code <news:title>}. No teaser — the headline is the value.
 *
 * <p><b>This source is a FIREHOSE, not a search:</b> the ONE sitemap is
 * fetched, parsed and cached as a POOL shared across all queries (10-min
 * politeness TTL), and {@link #newsForName} filters that pool by title
 * relevance (significant words, umlaut-tolerant, precision over recall).
 * Deliberately UNCURATED beyond that (house principle: ingestion wide, the
 * model judges): the sitemap mixes business/markets with world, sports and
 * the non-English desks — a name that only trends on the Brazilian
 * commodities desk still surfaces. {@link #newsFor} and {@link #newsForIsin}
 * stay no-ops: a sitemap tags neither tickers nor ISINs.
 *
 * <p>Entry fields (pinned live 2026-07-16): {@code <loc>} is the article URL
 * (doubles as the item's uuid; the desk sits in its path — /business/,
 * /world/, /pt/ …), {@code <news:title>} is CDATA, {@code
 * <news:publication_date>} is ISO-8601 with fractional seconds; an
 * {@code <image:image>} block carries its own {@code <image:loc>}, so the
 * article URL is strictly the FIRST loc of an entry.
 */
@Singleton
public class ReutersNewsClient implements NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(ReutersNewsClient.class);

    private static final String SITEMAP_URL =
            "https://www.reuters.com/arc/outboundfeeds/news-sitemap/?outputType=xml";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final String PUBLISHER = "Reuters";

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

    /** The shared pool: the parsed sitemap, refreshed at most once per TTL. */
    private volatile CachedPool pool;

    private record CachedPool(Instant fetchedAt, List<RawNewsItem> items) {}

    /** Test/default: plain direct transport. */
    public ReutersNewsClient() {
        this(new DirectWebFetcher());
    }

    /**
     * Production: the {@code @DirectFirst} seam — the crawler sitemap answers
     * a bare client with no wall (live-probed 2026-07-16; only the ARTICLE
     * pages sit behind the bot wall), so this client declares "fine on plain
     * HTTP" through the policy annotation like the other keyless no-wall
     * sources.
     */
    @Inject
    public ReutersNewsClient(
            @de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String sourceName() {
        return "reuters";
    }

    /** No-op: a sitemap tags no tickers — companies only surface by name. */
    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        return List.of();
    }

    /** No-op: a sitemap tags no ISINs — companies only surface by name. */
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
     * exactly ONE sitemap request; a non-sitemap or failed answer is never
     * cached (the next call retries), and an outage keeps the stale pool.
     */
    private synchronized List<RawNewsItem> currentPool() {
        CachedPool cached = pool;
        if (cached != null && cached.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cached.items();
        }
        try {
            WebResponse resp = fetcher.fetch(SITEMAP_URL,
                    Map.of("User-Agent", userAgent,
                            "Accept", "application/xml, text/xml"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) {
                String body = resp.body();
                if (!looksLikeSitemap(body)) {
                    // The bot wall answers 200-shaped HTML challenges — a
                    // status proves nothing, only content does.
                    LOG.debug("Reuters news-sitemap answered a 200 that is not a "
                            + "sitemap — treating as a miss, not caching");
                    return stale(cached);
                }
                List<RawNewsItem> items = parse(body);
                pool = new CachedPool(Instant.now(), items);
                return items;
            }
            LOG.debug("Reuters news-sitemap answered status {}",
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            LOG.debug("Reuters news-sitemap fetch failed: {}", e.getMessage());
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

    /** A 200 is only the sitemap when the body is actually a urlset — walls answer HTML. */
    static boolean looksLikeSitemap(String body) {
        if (body == null) return false;
        String head = body.stripLeading();
        if (head.length() > 512) head = head.substring(0, 512);
        String lower = head.toLowerCase(Locale.ROOT);
        return (lower.startsWith("<?xml") || lower.startsWith("<urlset"))
                && lower.contains("<urlset");
    }

    /**
     * Google-News sitemap {@code <url>} entries → {@link RawNewsItem}s,
     * unfiltered (the pool caches the whole sitemap; relevance is applied per
     * query). Garbage yields empty, never throws. Package-private for tests.
     */
    static List<RawNewsItem> parse(String xml) {
        if (xml == null || xml.isBlank()) return List.of();
        List<RawNewsItem> out = new ArrayList<>();
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(xml));
            boolean inUrl = false;
            String loc = null, title = null, pubDate = null;
            String current = null;
            try {
                while (r.hasNext()) {
                    int event = r.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String ln = r.getLocalName();
                        if ("url".equals(ln)) {
                            inUrl = true;
                            loc = title = pubDate = null;
                        }
                        current = inUrl ? ln : null;
                    } else if (event == XMLStreamConstants.CHARACTERS
                            || event == XMLStreamConstants.CDATA) {
                        if (!inUrl || current == null) continue;
                        String text = r.getText();
                        switch (current) {
                            // FIRST wins: the article <loc> precedes the
                            // <image:loc> (same local name, image namespace).
                            case "loc" -> loc = loc == null ? text : loc;
                            case "title" -> title = append(title, text);
                            case "publication_date" -> pubDate = append(pubDate, text);
                            default -> { /* keywords, language, image — ignored */ }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        if ("url".equals(r.getLocalName())) {
                            inUrl = false;
                            RawNewsItem item = toItem(loc, title, pubDate);
                            if (item != null) out.add(item);
                        }
                        current = inUrl ? null : current;
                    }
                }
            } finally {
                r.close();
            }
        } catch (Exception e) {
            LOG.debug("Reuters news-sitemap parse failed: {}", e.getMessage());
            return List.copyOf(out);
        }
        return out;
    }

    private static String append(String existing, String text) {
        return existing == null ? text : existing + text;
    }

    /** One parsed {@code <url>} entry → a {@link RawNewsItem}, or null when incomplete. */
    private static RawNewsItem toItem(String loc, String title, String pubDate) {
        if (title == null || title.isBlank() || loc == null || loc.isBlank()) return null;
        String cleanLoc = loc.strip();
        return new RawNewsItem(
                cleanLoc,
                title.replaceAll("\\s+", " ").strip(),
                PUBLISHER,
                cleanLoc,
                parseDate(pubDate),
                List.of(),
                null,
                null,
                false);
    }

    /** ISO-8601 with fractional seconds ("2026-07-16T16:54:42.616Z") → {@link Instant}. */
    static Instant parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return java.time.OffsetDateTime.parse(s.trim()).toInstant();
        } catch (Exception e) {
            return null;
        }
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
