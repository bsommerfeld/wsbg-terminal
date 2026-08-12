package de.bsommerfeld.wsbg.terminal.web.impl.sources.reuters;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.web.article.Article;
import de.bsommerfeld.wsbg.terminal.web.fetch.FetchUtil;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebFetcher;
import de.bsommerfeld.wsbg.terminal.web.fetch.WebResponse;
import de.bsommerfeld.wsbg.terminal.web.schedule.FetchInterval;
import de.bsommerfeld.wsbg.terminal.web.source.AbstractWebSource;
import de.bsommerfeld.wsbg.terminal.web.source.CollectorSource;
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

/**
 * Reuters wire headlines via the Arc news-sitemap
 * ({@code www.reuters.com/arc/outboundfeeds/news-sitemap/?outputType=xml}) —
 * the one keyless door left into the flagship global newswire: the classic
 * RSS endpoints are dead (401/404) and the article site is bot-walled, but
 * the Google-News sitemap the site publishes for crawlers answers a plain
 * client 200 (live-probed 2026-07-16) with the freshest ~50 stories: article
 * URL, exact publication instant and the full headline as
 * {@code <news:title>}. No teaser — the headline is the value. The sitemap
 * format is not RSS/Atom, so this stays a hand-written collector beside the
 * curated feed rows.
 *
 * <p>Deliberately UNCURATED (house principle: ingestion wide, the model
 * judges): the sitemap mixes business/markets with world, sports and the
 * non-English desks — a name that only trends on the Brazilian commodities
 * desk still pours into the pool.
 *
 * <p>Entry fields (pinned live 2026-07-16): {@code <loc>} is the article URL
 * (doubles as the item's uuid; the desk sits in its path — /business/,
 * /world/, /pt/ …), {@code <news:title>} is CDATA, {@code
 * <news:publication_date>} is ISO-8601 with fractional seconds; an
 * {@code <image:image>} block carries its own {@code <image:loc>}, so the
 * article URL is strictly the FIRST loc of an entry.
 */
@Singleton
public final class ReutersNewsSource extends AbstractWebSource implements CollectorSource {

    private static final Logger LOG = LoggerFactory.getLogger(ReutersNewsSource.class);

    private static final String SITEMAP_URL =
            "https://www.reuters.com/arc/outboundfeeds/news-sitemap/?outputType=xml";
    private static final String PUBLISHER = "Reuters";

    /** Hardened StAX factory (XXE off — this is a remote feed), reused for every parse. */
    private static final XMLInputFactory XML_FACTORY = newHardenedFactory();

    private final Duration requestTimeout = Duration.ofSeconds(12);

    @Inject
    public ReutersNewsSource(WebFetcher fetcher) {
        super(fetcher);
    }

    @Override
    public String sourceName() {
        return "reuters";
    }

    /**
     * Direct-first: the crawler sitemap answers a bare client with no wall
     * (live-probed 2026-07-16; only the ARTICLE pages sit behind the bot
     * wall).
     */
    @Override
    public FetchUtil[] mode() {
        return new FetchUtil[] {FetchUtil.DIRECT, FetchUtil.BROWSER};
    }

    /** A global wire refreshing constantly — fast cadence. */
    @Override
    public FetchInterval interval() {
        return FetchInterval.of(5, 10);
    }

    @Override
    public List<Article> collect() throws Exception {
        if (hostCoolingDown(SITEMAP_URL)) return List.of();
        WebResponse resp = get(SITEMAP_URL,
                Map.of("Accept", "application/xml, text/xml"), requestTimeout);
        if (resp.status() != 200) {
            LOG.debug("Reuters news-sitemap answered status {}", resp.status());
            return List.of();
        }
        String body = resp.body();
        if (!looksLikeSitemap(body)) {
            // The bot wall answers 200-shaped HTML challenges — a status
            // proves nothing, only content does.
            LOG.debug("Reuters news-sitemap answered a 200 that is not a sitemap — miss");
            reportWall(SITEMAP_URL);
            return List.of();
        }
        return parse(body);
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
     * Google-News sitemap {@code <url>} entries → {@link Article}s, unfiltered
     * (de-duplication and relevance are the pool's job). Garbage yields empty,
     * never throws. Package-private for tests.
     */
    static List<Article> parse(String xml) {
        if (xml == null || xml.isBlank()) return List.of();
        List<Article> out = new ArrayList<>();
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
                            Article item = toItem(loc, title, pubDate);
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

    /** One parsed {@code <url>} entry → an {@link Article}, or null when incomplete. */
    private static Article toItem(String loc, String title, String pubDate) {
        if (title == null || title.isBlank() || loc == null || loc.isBlank()) return null;
        String cleanLoc = loc.strip();
        return new Article(
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

    private static XMLInputFactory newHardenedFactory() {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        return f;
    }
}
