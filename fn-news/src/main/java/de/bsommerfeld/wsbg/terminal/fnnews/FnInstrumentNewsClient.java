package de.bsommerfeld.wsbg.terminal.fnnews;

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
import java.util.concurrent.ConcurrentHashMap;

/**
 * finanznachrichten.de's per-instrument feed as the ISIN-addressed German news
 * leg ({@link NewsSource} #6): the densest per-stock German news aggregate
 * anywhere (dpa-AFX, dpa-AFX-Analyser, EQS, IT-Times, boersennews …), keyless,
 * no bot wall (probed 2026-07-13). The URL keys on the ISIN ALONE — the name
 * slug is a dummy token: {@code rss-x-aktien-<isin-lowercase>} answers 200 +
 * RSS for a covered ISIN and a 301 to the homepage for an unknown one (NEVER
 * a 404 — a followed redirect looks like a 200 HTML page, so the client gates
 * on the body actually being RSS).
 *
 * <p>ISIN-addressed only ({@link #newsForIsin}); symbol and name queries stay
 * no-ops. {@code pubDate} is ISO-8601, not RFC-1123 (the FN house quirk the
 * ad-hoc feeds share); {@code <fn:isin>} rides on every item and lands in the
 * {@link RawNewsItem#isin()} field. Per-ISIN politeness cache (the feed
 * declares max-age=90; we stay far above), misses cached too.
 */
public class FnInstrumentNewsClient implements NewsSource {

    private static final Logger LOG = LoggerFactory.getLogger(FnInstrumentNewsClient.class);

    private static final String FEED_URL = "https://www.finanznachrichten.de/rss-x-aktien-";
    private static final Duration CACHE_TTL = Duration.ofMinutes(10);
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

    /** Test/CLI convenience — plain direct HTTP (the feed has no wall). */
    public FnInstrumentNewsClient() {
        this(new DirectWebFetcher());
    }

    @com.google.inject.Inject
    public FnInstrumentNewsClient(
            @de.bsommerfeld.wsbg.terminal.source.net.DirectFirst WebFetcher fetcher) {
        this.fetcher = fetcher;
    }

    @Override
    public String sourceName() {
        return "fn-news";
    }

    @Override
    public List<RawNewsItem> newsFor(String symbol, int limit) {
        return List.of();
    }

    @Override
    public List<RawNewsItem> newsForIsin(String isin, int limit) {
        String key = isinKey(isin);
        if (key == null || limit <= 0) return List.of();

        CachedResult cached = cache.get(key);
        if (cached != null && cached.fetchedAt().plus(CACHE_TTL).isAfter(Instant.now())) {
            return cap(cached.items(), limit);
        }
        try {
            WebResponse resp = fetcher.fetch(FEED_URL + key,
                    Map.of("User-Agent", USER_AGENT,
                            "Accept", "application/rss+xml, application/xml, text/xml"),
                    REQUEST_TIMEOUT);
            // An unknown ISIN 301s to the homepage — with redirects followed
            // that is a 200-shaped HTML page, so the RSS gate is the body.
            if (resp != null && resp.status() == 200 && looksLikeRss(resp.body())) {
                List<RawNewsItem> items = parse(resp.body());
                cache.put(key, new CachedResult(Instant.now(), items));
                return cap(items, limit);
            }
            LOG.debug("fn-news feed for '{}' answered status {} (rss={})", key,
                    resp == null ? "null" : resp.status(),
                    resp != null && looksLikeRss(resp.body()));
            cache.put(key, new CachedResult(Instant.now(), List.of()));
        } catch (Exception e) {
            LOG.debug("fn-news feed failed for '{}': {}", key, e.getMessage());
        }
        return List.of();
    }

    /** ISIN shape (2 letters + 10 alphanumerics), lowercased for the URL. */
    static String isinKey(String isin) {
        if (isin == null) return null;
        String s = isin.strip();
        if (s.length() != 12) return null;
        if (!Character.isLetter(s.charAt(0)) || !Character.isLetter(s.charAt(1))) return null;
        for (int i = 2; i < 12; i++) {
            if (!Character.isLetterOrDigit(s.charAt(i))) return null;
        }
        return s.toLowerCase(Locale.ROOT);
    }

    static boolean looksLikeRss(String body) {
        if (body == null) return false;
        int head = Math.min(body.length(), 300);
        String start = body.substring(0, head);
        return start.contains("<rss") || start.contains("<?xml");
    }

    private static List<RawNewsItem> cap(List<RawNewsItem> items, int limit) {
        return items.size() <= limit ? items : List.copyOf(items.subList(0, limit));
    }

    /**
     * RSS 2.0 items → {@link RawNewsItem}s: title, teaser description, link,
     * ISO-8601 pubDate, {@code fn:isin}. Garbage yields empty, never throws.
     */
    static List<RawNewsItem> parse(String xml) {
        if (xml == null || xml.isBlank()) return List.of();
        List<RawNewsItem> out = new ArrayList<>();
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(xml));
            boolean inItem = false;
            String title = null;
            String description = null;
            String link = null;
            String pubDate = null;
            String isin = null;
            String current = null;
            try {
                while (r.hasNext()) {
                    int event = r.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String ln = r.getLocalName();
                        if ("item".equals(ln)) {
                            inItem = true;
                            title = description = link = pubDate = isin = null;
                        }
                        current = inItem ? ln : null;
                    } else if (event == XMLStreamConstants.CHARACTERS
                            || event == XMLStreamConstants.CDATA) {
                        if (!inItem || current == null) continue;
                        String text = r.getText();
                        switch (current) {
                            case "title" -> title = append(title, text);
                            case "description" -> description = append(description, text);
                            case "link" -> link = append(link, text);
                            case "pubDate" -> pubDate = append(pubDate, text);
                            case "isin" -> isin = append(isin, text);
                            default -> {
                            }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        if ("item".equals(r.getLocalName())) {
                            inItem = false;
                            if (title != null && !title.isBlank()) {
                                String id = link != null && !link.isBlank()
                                        ? link.strip() : title.strip();
                                String teaser = description == null ? null : description.strip();
                                out.add(new RawNewsItem(id, title.strip(),
                                        "finanznachrichten.de",
                                        link == null ? null : link.strip(),
                                        parseDate(pubDate), List.of(),
                                        isin == null ? null : isin.strip(),
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
            LOG.debug("fn-news RSS parse failed: {}", e.getMessage());
            return List.copyOf(out);
        }
        return out;
    }

    private static String append(String existing, String text) {
        return existing == null ? text : existing + text;
    }

    /** FN emits ISO-8601 pubDates (the house quirk its ad-hoc feeds share). */
    private static Instant parseDate(String pubDate) {
        if (pubDate == null || pubDate.isBlank()) return null;
        try {
            return Instant.parse(pubDate.strip());
        } catch (Exception e) {
            return null;
        }
    }
}
