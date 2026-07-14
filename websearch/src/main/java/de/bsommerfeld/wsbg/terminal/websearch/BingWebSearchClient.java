package de.bsommerfeld.wsbg.terminal.websearch;

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
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The GENERAL web search leg ("&lt;Subjekt&gt; News", user mandate 2026-07-13):
 * Bing's search RSS ({@code bing.com/search?q=…&format=rss}) is the one
 * keyless, wall-less web search a bare client can ride — live-probed
 * 2026-07-13: the HTML SERP answers a Cloudflare Turnstile and Google's SERP a
 * JS wall (both 200-shaped, both joker-only), while the RSS variant answered
 * clean organic results with DIRECT target URLs across repeated calls. DDG
 * HTML works too but trips an anomaly wall after three quick requests — noted
 * as a possible fallback, not built.
 *
 * <p>Name-addressed ({@link #newsForName}): the query becomes
 * {@code "<name> News"}. The publisher is the target link's HOST (the real
 * outlet — better attribution than "Bing"), the published-at stays {@code null}
 * (Bing's pubDate is the crawl date, not the article date — a lying date is
 * worse than none). Results ride the same triage/digest lanes as every other
 * source. Per-query politeness cache; an outage answers empty, never throws.
 */
public class BingWebSearchClient implements NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(BingWebSearchClient.class);

    private static final String SEARCH_URL = "https://www.bing.com/search";
    private static final Duration CACHE_TTL = Duration.ofMinutes(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36";

    private static final XMLInputFactory XML_FACTORY = XMLInputFactory.newFactory();

    static {
        XML_FACTORY.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        XML_FACTORY.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    }

    private record CachedResult(Instant fetchedAt, List<RawNewsItem> items) {
    }

    private final WebFetcher fetcher;
    private final Map<String, CachedResult> cache = new ConcurrentHashMap<>();

    /** Test/CLI convenience — plain direct HTTP (the RSS path has no wall). */
    public BingWebSearchClient() {
        this(new DirectWebFetcher());
    }

    @com.google.inject.Inject
    public BingWebSearchClient(
            @de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String sourceName() {
        return "bing-web";
    }

    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        return List.of();
    }

    @Override
    public List<RawNewsItem> newsForName(String name, int limit) {
        if (name == null || name.isBlank() || limit <= 0) return List.of();
        String query = name.strip() + " News";
        String cacheKey = query.toLowerCase(Locale.ROOT);

        CachedResult cached = cache.get(cacheKey);
        if (cached != null && cached.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cap(cached.items(), limit);
        }
        try {
            WebResponse resp = fetcher.fetch(
                    SEARCH_URL + "?q=" + URLEncoder.encode(query, StandardCharsets.UTF_8)
                            + "&format=rss&setlang=de&cc=de",
                    Map.of("User-Agent", USER_AGENT,
                            "Accept-Language", "de-DE,de;q=0.9",
                            "Accept", "application/rss+xml, application/xml, text/xml"),
                    REQUEST_TIMEOUT);
            if (resp != null && resp.status() == 200 && looksLikeRss(resp.body())) {
                List<RawNewsItem> items = parse(resp.body());
                cache.put(cacheKey, new CachedResult(Instant.now(), items));
                return cap(items, limit);
            }
            LOG.debug("Bing web search for '{}' answered status {} (rss={})", query,
                    resp == null ? "null" : resp.status(),
                    resp != null && looksLikeRss(resp.body()));
        } catch (Exception e) {
            LOG.debug("Bing web search failed for '{}': {}", query, e.getMessage());
        }
        return List.of();
    }

    static boolean looksLikeRss(String body) {
        if (body == null) return false;
        String start = body.substring(0, Math.min(body.length(), 300));
        return start.contains("<rss") || start.contains("<?xml");
    }

    private static List<RawNewsItem> cap(List<RawNewsItem> items, int limit) {
        return items.size() <= limit ? items : List.copyOf(items.subList(0, limit));
    }

    /**
     * RSS items → {@link RawNewsItem}s: title, DIRECT target link, snippet as
     * teaser; the publisher is the link's host, the date deliberately null
     * (Bing's pubDate is the crawl date). Garbage yields empty, never throws.
     */
    static List<RawNewsItem> parse(String xml) {
        if (xml == null || xml.isBlank()) return List.of();
        List<RawNewsItem> out = new ArrayList<>();
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(xml));
            boolean inItem = false;
            String title = null;
            String link = null;
            String description = null;
            String current = null;
            try {
                while (r.hasNext()) {
                    int event = r.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String ln = r.getLocalName();
                        if ("item".equals(ln)) {
                            inItem = true;
                            title = link = description = null;
                        }
                        current = inItem ? ln : null;
                    } else if (event == XMLStreamConstants.CHARACTERS
                            || event == XMLStreamConstants.CDATA) {
                        if (!inItem || current == null) continue;
                        String text = r.getText();
                        switch (current) {
                            case "title" -> title = append(title, text);
                            case "link" -> link = append(link, text);
                            case "description" -> description = append(description, text);
                            default -> {
                            }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        if ("item".equals(r.getLocalName())) {
                            inItem = false;
                            if (title != null && !title.isBlank()
                                    && link != null && !link.isBlank()) {
                                String url = link.strip();
                                String teaser = description == null ? null
                                        : stripTags(description).strip();
                                out.add(new RawNewsItem(url, stripTags(title).strip(),
                                        host(url), url, null, List.of(), null,
                                        teaser == null || teaser.isBlank() ? null : teaser,
                                        false));
                            }
                        }
                        current = inItem ? null : current;
                    }
                }
            } finally {
                r.close();
            }
        } catch (Exception e) {
            LOG.debug("Bing RSS parse failed: {}", e.getMessage());
            return List.copyOf(out);
        }
        return out;
    }

    private static String append(String existing, String text) {
        return existing == null ? text : existing + text;
    }

    private static String stripTags(String s) {
        return s.replaceAll("<[^>]+>", "");
    }

    /** The real outlet as the publisher — {@code www.} shed for readability. */
    static String host(String url) {
        try {
            String h = URI.create(url).getHost();
            if (h == null) return "Web";
            return h.startsWith("www.") ? h.substring(4) : h;
        } catch (Exception e) {
            return "Web";
        }
    }
}
