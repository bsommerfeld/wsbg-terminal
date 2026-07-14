package de.bsommerfeld.wsbg.terminal.nasdaq;

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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Per-ticker news via NASDAQ's keyless outbound RSS
 * ({@code www.nasdaq.com/feed/rssoutbound?symbol=<T>}) — an aggregation
 * surface pooling Motley Fool, Zacks, MarketBeat and the other syndication
 * partners under one symbol query, ~15-25 same-day items per liquid US name
 * (live-probed 2026-07-14).
 *
 * <p><b>Unlike {@code api.nasdaq.com} this feed answers a plain client</b> —
 * no Akamai header dance, a bare UA + Accept gets the RSS (probed same day).
 * The Origin/Referer recipe of {@link NasdaqCompanyClient} is NOT needed here.
 *
 * <p>Item shape (pinned live): {@code <nasdaq:tickers>} carries the article's
 * tagged symbols comma-separated WITH duplicates and no exchange prefix
 * ("TSM,TSM,AAPL,NVDA"), {@code <dc:creator>} is the originating publisher
 * ("The Motley Fool", "Zacks"), {@code <description>} a teaser sentence,
 * {@code <pubDate>} RFC-1123. Links/guids are direct {@code nasdaq.com}
 * article URLs the digester can read.
 *
 * <p>US symbol shapes only (the {@link NasdaqCompanyClient} gate): a suffixed
 * ({@code RHM.DE}), caret, future or crypto symbol returns empty with ZERO
 * network. Per-symbol 10-minute politeness cache, misses cached too; a
 * non-RSS 200 (bot-wall shell) is a miss that is never cached, and an outage
 * keeps the stale pool.
 */
@Singleton
public class NasdaqNewsRssClient implements NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(NasdaqNewsRssClient.class);

    private static final String FEED_URL =
            "https://www.nasdaq.com/feed/rssoutbound?symbol=";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
    private static final String FALLBACK_PUBLISHER = "Nasdaq";

    /** The US listing shape — everything else is a guaranteed miss, never fetched. */
    private static final Pattern US_SYMBOL = Pattern.compile("[A-Z]{1,5}(\\.[A-Z])?");

    private static final XMLInputFactory XML_FACTORY = newHardenedFactory();

    private final String userAgent = BrowserUserAgent.random();
    private final WebFetcher fetcher;
    private final Duration requestTimeout = Duration.ofSeconds(12);

    private final Map<String, CachedFeed> cache = new ConcurrentHashMap<>();

    private record CachedFeed(Instant fetchedAt, List<RawNewsItem> items) {}

    /** Test/default: plain direct transport. */
    public NasdaqNewsRssClient() {
        this(new DirectWebFetcher());
    }

    /** Production: the {@code @DirectFirst} seam — the feed has no wall (probed 2026-07-14). */
    @Inject
    public NasdaqNewsRssClient(
            @de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String sourceName() {
        return "nasdaq-rss";
    }

    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        if (symbol == null || limit <= 0) return List.of();
        String sym = symbol.strip().toUpperCase(Locale.ROOT);
        if (!US_SYMBOL.matcher(sym).matches()) return List.of();

        CachedFeed cached = cache.get(sym);
        if (cached != null && cached.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cap(cached.items(), limit);
        }
        try {
            WebResponse resp = fetcher.fetch(FEED_URL + sym,
                    Map.of("User-Agent", userAgent,
                            "Accept", "application/rss+xml, application/xml, text/xml"),
                    requestTimeout);
            if (resp != null && resp.status() == 200) {
                String body = resp.body();
                if (!looksLikeRss(body)) {
                    // A 200-shaped wall/error shell is HTML — a miss, never cached.
                    LOG.debug("Nasdaq RSS for {} answered a non-RSS 200 — miss, not cached", sym);
                    return cap(stale(cached), limit);
                }
                List<RawNewsItem> items = parse(body);
                cache.put(sym, new CachedFeed(Instant.now(), items));
                return cap(items, limit);
            }
            LOG.debug("Nasdaq RSS for {} answered status {}", sym,
                    resp == null ? "null" : resp.status());
        } catch (Exception e) {
            LOG.debug("Nasdaq RSS fetch for {} failed: {}", sym, e.getMessage());
        }
        return cap(stale(cached), limit);
    }

    private static List<RawNewsItem> stale(CachedFeed cached) {
        return cached == null ? List.of() : cached.items();
    }

    private static List<RawNewsItem> cap(List<RawNewsItem> items, int limit) {
        return items.size() <= limit ? items : List.copyOf(items.subList(0, limit));
    }

    /** A 200 is only a feed when the body is actually RSS/XML. */
    static boolean looksLikeRss(String body) {
        if (body == null) return false;
        String head = body.stripLeading();
        if (head.length() > 512) head = head.substring(0, 512);
        String lower = head.toLowerCase(Locale.ROOT);
        return lower.startsWith("<?xml") || lower.startsWith("<rss");
    }

    /**
     * RSS items → {@link RawNewsItem}s. {@code <nasdaq:tickers>} is matched by
     * local name (the channel declares the namespace), deduplicated preserving
     * order. Garbage yields empty, never throws. Package-private for tests.
     */
    static List<RawNewsItem> parse(String xml) {
        if (xml == null || xml.isBlank()) return List.of();
        List<RawNewsItem> out = new ArrayList<>();
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(xml));
            boolean inItem = false;
            String title = null, link = null, guid = null, pubDate = null;
            String description = null, creator = null, tickers = null;
            String current = null;
            try {
                while (r.hasNext()) {
                    int event = r.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String ln = r.getLocalName();
                        if ("item".equals(ln)) {
                            inItem = true;
                            title = link = guid = pubDate = null;
                            description = creator = tickers = null;
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
                            // dc:creator — the originating publisher.
                            case "creator" -> creator = append(creator, text);
                            // nasdaq:tickers — comma-separated, duplicates included.
                            case "tickers" -> tickers = append(tickers, text);
                            default -> { /* category etc. — ignored */ }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        if ("item".equals(r.getLocalName())) {
                            inItem = false;
                            RawNewsItem item = toItem(title, link, guid, pubDate,
                                    description, creator, tickers);
                            if (item != null) out.add(item);
                        }
                        current = inItem ? null : current;
                    }
                }
            } finally {
                r.close();
            }
        } catch (Exception e) {
            LOG.debug("Nasdaq RSS parse failed: {}", e.getMessage());
            return List.copyOf(out);
        }
        return out;
    }

    private static String append(String existing, String text) {
        return existing == null ? text : existing + text;
    }

    private static RawNewsItem toItem(String title, String link, String guid, String pubDate,
            String description, String creator, String tickers) {
        if (title == null || title.isBlank() || link == null || link.isBlank()) return null;
        String publisher = creator == null || creator.isBlank()
                ? FALLBACK_PUBLISHER : creator.strip();
        String teaser = description == null ? null
                : description.replaceAll("\\s+", " ").strip();
        return new RawNewsItem(
                guid != null && !guid.isBlank() ? guid.strip() : link.strip(),
                title.strip(),
                publisher,
                link.strip(),
                parsePubDate(pubDate),
                splitTickers(tickers),
                null,
                teaser == null || teaser.isBlank() ? null : teaser,
                false);
    }

    /** "TSM,TSM,AAPL,NVDA" → [TSM, AAPL, NVDA] — deduplicated, order kept. */
    static List<String> splitTickers(String tickers) {
        if (tickers == null || tickers.isBlank()) return List.of();
        Set<String> out = new LinkedHashSet<>();
        for (String t : tickers.split(",")) {
            String s = t.strip().toUpperCase(Locale.ROOT);
            if (!s.isEmpty()) out.add(s);
        }
        return List.copyOf(out);
    }

    /** RFC-1123 with the lenient no-day-of-week fallback; unparseable → null. */
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

    private static final DateTimeFormatter RFC_1123_NO_DOW =
            DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss zzz", Locale.ENGLISH);

    private static XMLInputFactory newHardenedFactory() {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        return f;
    }
}
