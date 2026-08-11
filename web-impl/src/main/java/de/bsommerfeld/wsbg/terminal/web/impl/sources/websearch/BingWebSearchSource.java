package de.bsommerfeld.wsbg.terminal.web.impl.sources.websearch;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import de.bsommerfeld.wsbg.terminal.web.source.AbstractWebSource;
import de.bsommerfeld.wsbg.terminal.web.source.SearchEngine;
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
 * The GENERAL web search leg: Bing's search RSS
 * ({@code bing.com/search?q=…&format=rss}) is the one keyless, wall-less web
 * search a bare client can ride — live-probed 2026-07-13: the HTML SERP
 * answers a Cloudflare Turnstile and Google's SERP a JS wall (both 200-shaped,
 * both joker-only), while the RSS variant answered clean organic results with
 * DIRECT target URLs across repeated calls. DDG HTML works too but trips an
 * anomaly wall after three quick requests — noted as a possible fallback, not
 * built.
 *
 * <p>A {@link SearchEngine} now: the caller's free research query rides
 * verbatim (the old world's name-addressed {@code "<name> News"} phrasing was
 * that contract's, not this engine's). The publisher is the target link's HOST
 * (the real outlet — better attribution than "Bing"), the published-at stays
 * {@code null} (Bing's pubDate is the crawl date, not the article date — a
 * lying date is worse than none). Per-query politeness cache; an outage
 * answers empty, never throws.
 */
@Singleton
public final class BingWebSearchSource extends AbstractWebSource implements SearchEngine {

    private static final Logger LOG = LoggerFactory.getLogger(BingWebSearchSource.class);

    private static final String SEARCH_URL = "https://www.bing.com/search";
    private static final Duration CACHE_TTL = Duration.ofMinutes(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(12);

    private static final XMLInputFactory XML_FACTORY = XMLInputFactory.newFactory();

    static {
        XML_FACTORY.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        XML_FACTORY.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    }

    private record CachedResult(Instant fetchedAt, List<Article> items) {
    }

    /** Per-query politeness cache: parsed, uncapped items. */
    private final Map<String, CachedResult> cache = new ConcurrentHashMap<>();

    @Inject
    public BingWebSearchSource(WebFetcher fetcher) {
        super(fetcher);
    }

    @Override
    public String sourceName() {
        return "bing-web";
    }

    /** The RSS path has no wall — direct first, the joker only as rescue. */
    @Override
    public FetchUtil[] mode() {
        return new FetchUtil[] {FetchUtil.DIRECT, FetchUtil.BROWSER};
    }

    /** The free research query, verbatim — the caller phrased it. */
    @Override
    public List<Article> search(String query, int limit) {
        if (query == null || query.isBlank() || limit <= 0) return List.of();
        String trimmed = query.strip();
        String cacheKey = trimmed.toLowerCase(Locale.ROOT);

        CachedResult cached = cache.get(cacheKey);
        if (cached != null && cached.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cap(cached.items(), limit);
        }
        try {
            WebResponse resp = get(
                    SEARCH_URL + "?q=" + URLEncoder.encode(trimmed, StandardCharsets.UTF_8)
                            + "&format=rss&setlang=de&cc=de",
                    Map.of("Accept-Language", "de-DE,de;q=0.9",
                            "Accept", "application/rss+xml, application/xml, text/xml"),
                    REQUEST_TIMEOUT);
            if (resp != null && resp.status() == 200 && looksLikeRss(resp.body())) {
                List<Article> items = parse(resp.body());
                cache.put(cacheKey, new CachedResult(Instant.now(), items));
                return cap(items, limit);
            }
            LOG.debug("Bing web search for '{}' answered status {} (rss={})", trimmed,
                    resp == null ? "null" : resp.status(),
                    resp != null && looksLikeRss(resp.body()));
        } catch (Exception e) {
            LOG.debug("Bing web search failed for '{}': {}", trimmed, e.getMessage());
        }
        return List.of();
    }

    static boolean looksLikeRss(String body) {
        if (body == null) return false;
        String start = body.substring(0, Math.min(body.length(), 300));
        return start.contains("<rss") || start.contains("<?xml");
    }

    private static List<Article> cap(List<Article> items, int limit) {
        return items.size() <= limit ? items : List.copyOf(items.subList(0, limit));
    }

    /**
     * RSS items → {@link Article}s: title, DIRECT target link, snippet as
     * teaser; the publisher is the link's host, the date deliberately null
     * (Bing's pubDate is the crawl date). Garbage yields empty, never throws.
     */
    static List<Article> parse(String xml) {
        if (xml == null || xml.isBlank()) return List.of();
        List<Article> out = new ArrayList<>();
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
                                out.add(new Article(url, stripTags(title).strip(),
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
