package de.bsommerfeld.wsbg.terminal.web.impl.feed;

import de.bsommerfeld.wsbg.terminal.web.article.Article;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * THE house feed parser: RSS 2.0 and Atom, hardened (XXE off, DTD off),
 * garbage-tolerant — a broken feed yields what parsed before the break,
 * never throws. Replaces the per-module StAX copies of the old world.
 *
 * <p>Two field notes carried over from the live probes: some CDNs append an
 * HTML/script tail BEHIND the closing root tag (Benzinga's Cloudflare shell,
 * measured 2026-07), so the body is truncated at the root close before
 * parsing; and a 200 whose body is an HTML error shell is not a feed at all —
 * {@link #looksLikeFeed} gates that.
 */
public final class FeedParser {

    private static final Logger LOG = LoggerFactory.getLogger(FeedParser.class);

    /** Hardened StAX factory (XXE off — these are remote feeds), reused for every parse. */
    private static final XMLInputFactory XML_FACTORY = newHardenedFactory();

    /** RFC-1123 without the day-of-week prefix, English month names. */
    private static final DateTimeFormatter RFC_1123_NO_DOW =
            DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss zzz", Locale.ENGLISH);

    private FeedParser() {
    }

    /** A 200 is only a feed when the body is actually XML — error shells are HTML. */
    public static boolean looksLikeFeed(String body) {
        if (body == null) return false;
        String head = stripBom(body).stripLeading();
        if (head.length() > 512) head = head.substring(0, 512);
        String lower = head.toLowerCase(Locale.ROOT);
        return lower.startsWith("<?xml") || lower.startsWith("<rss") || lower.startsWith("<feed");
    }

    /**
     * Drops a leading UTF-8 byte-order mark. {@code String.stripLeading} does
     * NOT remove U+FEFF — it is not whitespace — so a BOM-prefixed feed would
     * silently fail the gate above and choke StAX in the prolog. Perfectly
     * ordinary feeds ship one (Anadolu's economy feed does).
     */
    private static String stripBom(String s) {
        return !s.isEmpty() && s.charAt(0) == '\uFEFF' ? s.substring(1) : s;
    }

    /**
     * Feed items → {@link Article}s with the given publisher stamped.
     * RSS {@code <item>} and Atom {@code <entry>} both parse; garbage yields
     * what parsed so far.
     */
    public static List<Article> parse(String xml, String publisher) {
        if (xml == null || xml.isBlank()) return List.of();
        String clean = truncateAfterRoot(stripBom(xml));
        List<Article> out = new ArrayList<>();
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(clean));
            boolean inItem = false;
            String title = null, link = null, guid = null, date = null, summary = null;
            // Which element filled the guid/date/summary: feeds carry TWINS
            // (CNBC's <guid> beside <metadata:id>, comdirect's <pubDate>
            // beside <dc:date>, description beside content:encoded) —
            // appending across elements would weld two representations into
            // one corrupted/unparseable/doubled string, so the FIRST element
            // of a kind wins and its twins are ignored.
            String guidElement = null, dateElement = null, summaryElement = null;
            String current = null;
            try {
                while (r.hasNext()) {
                    int event = r.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String ln = r.getLocalName();
                        if ("item".equals(ln) || "entry".equals(ln)) {
                            inItem = true;
                            title = link = guid = date = summary = null;
                            guidElement = dateElement = summaryElement = null;
                            current = null;
                            continue;
                        }
                        if (!inItem) continue;
                        current = ln;
                        // Atom's link is an attribute, not text.
                        if ("link".equals(ln)) {
                            String href = r.getAttributeValue(null, "href");
                            if (href != null && !href.isBlank()
                                    && preferAtomLink(r.getAttributeValue(null, "rel"))) {
                                link = href;
                            }
                        }
                    } else if (event == XMLStreamConstants.CHARACTERS
                            || event == XMLStreamConstants.CDATA) {
                        if (!inItem || current == null) continue;
                        String text = r.getText();
                        switch (current) {
                            case "title" -> title = append(title, text);
                            case "link" -> link = append(link, text);
                            case "guid", "id" -> {
                                if (guidElement == null || guidElement.equals(current)) {
                                    guidElement = current;
                                    guid = append(guid, text);
                                }
                            }
                            case "pubDate", "published", "updated", "date" -> {
                                if (dateElement == null || dateElement.equals(current)) {
                                    dateElement = current;
                                    date = append(date, text);
                                }
                            }
                            case "description", "summary", "content" -> {
                                if (summaryElement == null || summaryElement.equals(current)) {
                                    summaryElement = current;
                                    summary = append(summary, text);
                                }
                            }
                            default -> { /* dc:creator etc. — ignored */ }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        String ln = r.getLocalName();
                        if ("item".equals(ln) || "entry".equals(ln)) {
                            inItem = false;
                            Article a = toArticle(title, link, guid, date, summary, publisher);
                            if (a != null) out.add(a);
                        }
                        current = null;
                    }
                }
            } finally {
                r.close();
            }
        } catch (Exception e) {
            LOG.debug("feed parse broke off: {} — keeping the {} item(s) parsed before",
                    e.getMessage(), out.size());
            return List.copyOf(out);
        }
        return out;
    }

    /**
     * An Atom {@code rel} worth taking: the alternate/self-less plain link.
     * RSS links have no rel and never reach here.
     */
    private static boolean preferAtomLink(String rel) {
        return rel == null || rel.isBlank() || "alternate".equals(rel);
    }

    /**
     * Cuts everything behind the closing root tag — some CDNs append a script
     * tail there that breaks strict XML parsing.
     */
    static String truncateAfterRoot(String xml) {
        int rss = xml.lastIndexOf("</rss>");
        if (rss >= 0) return xml.substring(0, rss + "</rss>".length());
        int feed = xml.lastIndexOf("</feed>");
        if (feed >= 0) return xml.substring(0, feed + "</feed>".length());
        return xml;
    }

    private static String append(String existing, String text) {
        return existing == null ? text : existing + text;
    }

    private static Article toArticle(String title, String link, String guid,
            String date, String summary, String publisher) {
        if (title == null || title.isBlank() || link == null || link.isBlank()) return null;
        String cleanTitle = collapse(title);
        String teaser = collapse(summary);
        String cleanLink = link.strip();
        return new Article(
                guid != null && !guid.isBlank() ? guid.strip() : cleanLink,
                cleanTitle,
                publisher,
                cleanLink,
                parseDate(date),
                List.of(),
                null,
                teaser == null || teaser.isBlank() ? null : stripHtml(teaser),
                false);
    }

    private static String collapse(String s) {
        return s == null ? null : s.replaceAll("\\s+", " ").strip();
    }

    /** Teasers may carry markup (Atom content) — the pool searches text. */
    private static String stripHtml(String s) {
        return s.replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").strip();
    }

    /**
     * RFC-1123 ("Thu, 16 Jul 2026 16:40:16 GMT"), RFC-1123 without weekday,
     * or ISO-8601 (Atom) → {@link Instant}; unparseable → null.
     */
    public static Instant parseDate(String date) {
        if (date == null || date.isBlank()) return null;
        String s = date.trim();
        try {
            return ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (Exception notRfc1123) {
            // fall through
        }
        try {
            int comma = s.indexOf(',');
            return ZonedDateTime.parse(comma >= 0 ? s.substring(comma + 1).trim() : s,
                    RFC_1123_NO_DOW).toInstant();
        } catch (Exception notRfc1123NoDow) {
            // fall through
        }
        try {
            return Instant.parse(s);
        } catch (Exception notInstant) {
            // fall through
        }
        try {
            return ZonedDateTime.parse(s).toInstant();
        } catch (Exception notIso) {
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
