package de.bsommerfeld.wsbg.terminal.finanznachrichten;

import com.google.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Fetches and parses a single finanznachrichten.de RSS feed into
 * {@link FnNewsItem} objects. Stateless and thread-safe — de-duplication and
 * scheduling live in {@link FnMonitorService}.
 *
 * <h3>Feed format</h3>
 * RSS 2.0 with a custom {@code fn:} namespace
 * ({@code xmlns:fn="http://www.finanznachrichten.de/service/rss"}). Each
 * {@code <item>} has {@code <title>}, {@code <description>}, {@code <link>},
 * {@code <pubDate>} (ISO-8601 instant, e.g. {@code 2026-06-05T03:46:00Z}) and an
 * optional {@code <fn:isin>}. There is no {@code <guid>} / {@code <author>} /
 * {@code <category>}; the {@code <link>} is the identity key. Sponsored items
 * carry a leading "Anzeige / Werbung" marker in the description.
 *
 * <h3>Parsing</h3>
 * Uses JAXP (built into the JDK) with DOCTYPE declarations disabled for XXE
 * safety — RSS is untrusted external input. The parser is left
 * namespace-unaware, so {@code fn:isin} is matched by its literal qualified
 * name. Any fetch or parse failure yields an empty list (never an exception)
 * so one broken feed never stalls a whole sweep.
 */
@Singleton
public class FnRssClient implements FeedFetcher {

    private static final Logger LOG = LoggerFactory.getLogger(FnRssClient.class);

    /** Browser-shaped UA — bare HTTP clients are sometimes bot-blocked. */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36";

    /** Leading paid-placement marker finanznachrichten.de prepends to ads. */
    private static final Pattern SPONSORED_MARKER =
            Pattern.compile("^\\s*Anzeige\\s*/\\s*Werbung\\s*", Pattern.CASE_INSENSITIVE);

    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");

    private final HttpClient http;
    private final Duration requestTimeout;

    public FnRssClient() {
        this(15);
    }

    public FnRssClient(int requestTimeoutSeconds) {
        int t = Math.max(2, requestTimeoutSeconds);
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(t))
                .build();
        this.requestTimeout = Duration.ofSeconds(t);
    }

    /**
     * Fetches {@code feed} over HTTP and parses it. Returns an empty list on any
     * failure (network, non-200, parse error) — never throws.
     */
    @Override
    public List<FnNewsItem> fetch(FnFeed feed) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(feed.url()))
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/rss+xml, application/xml, text/xml")
                    .timeout(requestTimeout)
                    .GET()
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                LOG.warn("finanznachrichten feed {} returned HTTP {}", feed.slug(), response.statusCode());
                return Collections.emptyList();
            }
            return parse(response.body(), feed);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Collections.emptyList();
        } catch (Exception e) {
            LOG.warn("Failed to fetch finanznachrichten feed {}: {}", feed.slug(), e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Parses raw RSS 2.0 XML into {@link FnNewsItem}s. Package-visible and pure
     * (no network) so it can be unit-tested against captured feed bodies.
     */
    List<FnNewsItem> parse(String xml, FnFeed feed) {
        List<FnNewsItem> items = new ArrayList<>();
        long now = System.currentTimeMillis() / 1000;
        String feedSlug = feed == null ? "" : feed.slug();

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // XXE protection — RSS feeds are untrusted external input.
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xml)));

            NodeList itemNodes = doc.getElementsByTagName("item");
            for (int i = 0; i < itemNodes.getLength(); i++) {
                Element element = (Element) itemNodes.item(i);

                String title = textContent(element, "title");
                String link = textContent(element, "link");
                String rawDescription = textContent(element, "description");
                String pubDate = textContent(element, "pubDate");
                String isin = textContent(element, "fn:isin");

                boolean sponsored = SPONSORED_MARKER.matcher(rawDescription).find();
                String description = stripHtml(SPONSORED_MARKER.matcher(rawDescription).replaceFirst(""));
                long publishedUtc = parsePubDate(pubDate);

                items.add(new FnNewsItem(
                        title,
                        link,
                        description,
                        isin.isEmpty() ? null : isin,
                        publishedUtc,
                        now,
                        feedSlug,
                        sponsored));
            }
        } catch (Exception e) {
            LOG.warn("Failed to parse finanznachrichten feed {}: {}", feedSlug, e.getMessage());
        }

        return items;
    }

    private static String textContent(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return "";
        }
        String content = nodes.item(0).getTextContent();
        return content != null ? content.trim() : "";
    }

    /** Strips any residual HTML tags and collapses whitespace in a teaser. */
    private static String stripHtml(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        return HTML_TAG_PATTERN.matcher(html).replaceAll(" ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Parses the {@code <pubDate>} into epoch seconds. The feeds use an ISO-8601
     * instant ({@code 2026-06-05T03:46:00Z}); an explicit numeric offset
     * ({@code +02:00}) is tolerated as a fallback. Returns 0 on any failure —
     * the item is still usable, just without a timestamp.
     */
    private static long parsePubDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return 0;
        }
        try {
            return Instant.parse(dateStr).getEpochSecond();
        } catch (Exception ignored) {
            // fall through to offset form
        }
        try {
            return OffsetDateTime.parse(dateStr).toEpochSecond();
        } catch (Exception e) {
            LOG.debug("Unparseable pubDate '{}'", dateStr);
            return 0;
        }
    }
}
