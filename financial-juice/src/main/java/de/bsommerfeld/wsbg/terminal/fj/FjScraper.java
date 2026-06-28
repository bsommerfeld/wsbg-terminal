package de.bsommerfeld.wsbg.terminal.fj;

import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import de.bsommerfeld.wsbg.terminal.core.util.BrowserUserAgent;
import java.time.Instant;
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
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Fetches and parses the FinancialJuice RSS feed into {@link RawNewsItem}
 * objects.
 *
 * <h3>Feed format</h3>
 * FinancialJuice exposes a standard RSS 2.0 feed at
 * {@code https://www.financialjuice.com/feed.ashx?xy=rss}. Each
 * {@code <item>} contains a headline, permalink, optional HTML description,
 * author, publication date, and a numeric GUID used for deduplication.
 *
 * <h3>Rate limiting</h3>
 * RSS feeds are not API endpoints — there is no documented rate limit.
 * A 60-second polling interval is conservative and safe; most RSS
 * providers tolerate intervals down to 15 seconds without issues.
 * If FinancialJuice ever starts returning 429s, the scraper logs the
 * status code and returns an empty list without retrying.
 *
 * <h3>Deduplication</h3>
 * The scraper tracks seen GUIDs in memory across calls. Items already
 * seen in a previous fetch are not included in the returned list.
 * This allows callers to treat the return value as a "delta" of new
 * items since the last poll.
 *
 * <h3>Title normalization</h3>
 * Every item title is prefixed with {@code "FinancialJuice: "} by the
 * feed. This prefix is stripped during parsing to produce cleaner headlines
 * for downstream AI analysis.
 */
@Singleton
public class FjScraper {

    private static final Logger LOG = LoggerFactory.getLogger(FjScraper.class);

    private static final String FEED_URL = "https://www.financialjuice.com/feed.ashx?xy=rss";

    /**
     * 30s is aggressive for a typical RSS feed but well within safe territory.
     * RSS providers expect polling in the minute range; FinancialJuice
     * has no documented rate limit. Can be lowered to 15s if needed,
     * but 30s already catches most breaking news within one cycle.
     */
    private static final long POLL_INTERVAL_SECONDS = 30;

    /**
     * A random, realistic browser User-Agent chosen once per process so the feed
     * accepts us as a genuine browser without every install sharing one
     * fingerprint. See {@link BrowserUserAgent}.
     */
    private final String userAgent = BrowserUserAgent.random();

    /**
     * Redundant prefix that FinancialJuice prepends to every single item
     * title. Stripped during parsing because it adds no information and
     * wastes tokens during AI analysis.
     */
    private static final String FJ_TITLE_PREFIX = "FinancialJuice: ";

    /** Matches HTML tags for stripping from description content. */
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");

    /**
     * Block / break tags that FinancialJuice uses to separate the lines of
     * a multi-line body (e.g. EPS / revenue / comps each on their own line).
     * These become newlines so the structure survives stripping — the
     * expandable card in the UI renders one line per entry.
     */
    private static final Pattern LINE_BREAK_TAG_PATTERN =
            Pattern.compile("(?i)<br\\s*/?>|</(?:div|p|li|tr)>");

    /** Extractor for <img> tags in description html. */
    private static final Pattern IMG_SRC_PATTERN = Pattern.compile("<img[^>]+src\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>");

    /** RFC 1123 date format used by RSS 2.0 {@code <pubDate>} elements. */
    private static final DateTimeFormatter RSS_DATE_FORMAT =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH);

    private final HttpClient httpClient;

    /**
     * GUIDs that have been returned in previous {@link #fetch()} calls.
     * Prevents re-processing of items already seen. Not persisted across
     * restarts — acceptable because downstream consumers (the agent)
     * perform their own deduplication via embedding similarity.
     */
    private final Set<String> seenGuids = new HashSet<>();

    public FjScraper() {
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .build();
    }

    /**
     * Fetches the RSS feed and returns only items not seen in previous calls.
     *
     * @return new items since the last fetch, ordered newest-first
     *         (matching the feed's natural order). Returns an empty list on
     *         any fetch or parse failure.
     */
    public List<RawNewsItem> fetch() {
        LOG.debug("Fetching FinancialJuice RSS feed");

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(FEED_URL))
                    .header("User-Agent", userAgent)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                LOG.warn("FinancialJuice RSS returned HTTP {}", response.statusCode());
                return Collections.emptyList();
            }

            List<RawNewsItem> allItems = parseRss(response.body());
            List<RawNewsItem> newItems = new ArrayList<>();

            for (RawNewsItem item : allItems) {
                if (seenGuids.add(item.uuid())) {
                    newItems.add(item);
                }
            }

            if (!newItems.isEmpty()) {
                LOG.info("FinancialJuice: {} new items fetched", newItems.size());
            }

            return Collections.unmodifiableList(newItems);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warn("FinancialJuice fetch interrupted");
            return Collections.emptyList();
        } catch (Exception e) {
            LOG.error("Failed to fetch FinancialJuice RSS", e);
            return Collections.emptyList();
        }
    }

    /**
     * Parses raw RSS 2.0 XML into domain objects. Uses JAXP (built into
     * the JDK) instead of Jackson or a third-party XML library to avoid
     * adding dependencies for a trivially simple document structure.
     */
    List<RawNewsItem> parseRss(String xml) {
        List<RawNewsItem> items = new ArrayList<>();

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            // XXE protection — RSS feeds are untrusted external input
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(xml)));

            NodeList itemNodes = doc.getElementsByTagName("item");

            for (int i = 0; i < itemNodes.getLength(); i++) {
                Element element = (Element) itemNodes.item(i);

                String guid = textContent(element, "guid");
                String rawTitle = textContent(element, "title");
                String link = textContent(element, "link");
                String rawDescription = textContent(element, "description");
                String author = textContent(element, "author");
                String pubDateStr = textContent(element, "pubDate");

                String title = stripFjPrefix(rawTitle);
                String imageUrl = extractImageUrl(rawDescription);
                String description = stripHtml(rawDescription);
                long publishedUtc = parsePubDate(pubDateStr);
                
                items.add(new RawNewsItem(
                        guid,
                        title,
                        author,
                        link,
                        publishedUtc == 0 ? null : Instant.ofEpochSecond(publishedUtc),
                        List.of(),
                        null,
                        description,
                        false,
                        imageUrl));
            }
        } catch (Exception e) {
            LOG.error("Failed to parse FinancialJuice RSS XML", e);
        }

        return items;
    }

    /** Returns the total number of unique items seen since startup. */
    public int seenCount() {
        return seenGuids.size();
    }

    /** Returns the configured polling interval in seconds. */
    public long pollIntervalSeconds() {
        return POLL_INTERVAL_SECONDS;
    }

    /** Clears the deduplication cache, causing the next fetch to return all feed items. */
    public void resetSeenGuids() {
        seenGuids.clear();
    }

    // =====================================================================
    // Parsing Helpers
    // =====================================================================

    private String textContent(Element parent, String tagName) {
        NodeList nodes = parent.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return "";
        }
        String content = nodes.item(0).getTextContent();
        return content != null ? content.trim() : "";
    }

    /**
     * Strips the redundant {@code "FinancialJuice: "} prefix that the
     * feed adds to every title. Left intact if the prefix is absent
     * (defensive, in case they change the format).
     */
    private String stripFjPrefix(String title) {
        if (title.startsWith(FJ_TITLE_PREFIX)) {
            return title.substring(FJ_TITLE_PREFIX.length());
        }
        return title;
    }

    /**
     * Removes HTML tags from description content while preserving the
     * line structure. FinancialJuice wraps descriptions in {@code <div>}
     * and {@code <br>} tags — block/break boundaries are converted to
     * newlines, remaining markup is dropped, horizontal whitespace inside
     * each line is collapsed, and blank lines are removed.
     */
    private String stripHtml(String html) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        String withBreaks = LINE_BREAK_TAG_PATTERN.matcher(html).replaceAll("\n");
        String decoded = HTML_TAG_PATTERN.matcher(withBreaks).replaceAll(" ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");
        return decoded.lines()
                .map(line -> line.replaceAll("[ \\t\\x0B\\f\\r]+", " ").trim())
                .filter(line -> !line.isEmpty())
                .collect(Collectors.joining("\n"));
    }

    /**
     * Parses the RFC 1123 date from {@code <pubDate>} into epoch seconds.
     * Returns 0 on parse failure — the item is still usable, just without
     * a valid timestamp.
     */
    private long parsePubDate(String dateStr) {
        if (dateStr == null || dateStr.isEmpty()) {
            return 0;
        }
        try {
            return ZonedDateTime.parse(dateStr, RSS_DATE_FORMAT).toEpochSecond();
        } catch (Exception e) {
            LOG.warn("Failed to parse RSS date '{}': {}", dateStr, e.getMessage());
            return 0;
        }
    }

    private String extractImageUrl(String html) {
        if (html == null || html.isEmpty()) return null;
        Matcher m = IMG_SRC_PATTERN.matcher(html);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

}
