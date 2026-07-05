package de.bsommerfeld.wsbg.terminal.fj;

import de.bsommerfeld.wsbg.terminal.core.util.HtmlText;
import de.bsommerfeld.wsbg.terminal.source.RawNewsItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Stateless parser for the FinancialJuice RSS 2.0 feed. Turns raw feed XML into
 * {@link RawNewsItem} objects and owns all of the HTML→text cleaning
 * ({@code stripHtml}, prefix stripping, image extraction) and date parsing.
 *
 * <p>Split out of {@code FjScraper} so the scraper only owns fetch + cross-call
 * GUID dedup (a parse / fetch+dedup+schedule separation).
 * Contains no mutable state and is safe to share.
 */
final class FjRssParser {

    private static final Logger LOG = LoggerFactory.getLogger(FjRssParser.class);

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
                items.add(toItem((Element) itemNodes.item(i)));
            }
        } catch (Exception e) {
            LOG.error("Failed to parse FinancialJuice RSS XML", e);
        }

        return items;
    }

    /** Builds one {@link RawNewsItem} from a single {@code <item>} element. */
    private RawNewsItem toItem(Element element) {
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

        return new RawNewsItem(
                guid,
                title,
                author,
                link,
                publishedUtc == 0 ? null : Instant.ofEpochSecond(publishedUtc),
                List.of(),
                null,
                description,
                false,
                imageUrl);
    }

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
        String decoded = HtmlText.unescapeBasic(HTML_TAG_PATTERN.matcher(withBreaks).replaceAll(" "));
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
