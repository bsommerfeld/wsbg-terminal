package de.bsommerfeld.wsbg.terminal.web.impl.sources.briefing;

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
 * Minimal shared RSS-2.0 item reader for the briefing feeds (finanznachrichten,
 * Destatis, ifo): title / link / description / category / pubDate per item,
 * hardened StAX (XXE off), garbage in → empty list out. The feeds differ only
 * in which of those fields the individual clients read.
 */
final class Rss {

    /**
     * One parsed {@code <item>}; absent fields are empty strings / null instant.
     * {@code isin} carries a dedicated ISIN element where the feed ships one
     * (finanznachrichten's {@code <fn:isin>} — matched by local name).
     */
    record Item(String title, String link, String description, String category,
            String isin, Instant publishedAt) {
    }

    private static final XMLInputFactory XML_FACTORY = newHardenedFactory();

    /** RFC-1123 without the day-of-week prefix — some feeds emit mismatched weekdays. */
    private static final DateTimeFormatter RFC_1123_NO_DOW =
            DateTimeFormatter.ofPattern("d MMM yyyy HH:mm:ss zzz", Locale.ENGLISH);

    private Rss() {
    }

    static List<Item> parse(String xml) {
        if (xml == null || xml.isBlank()) return List.of();
        List<Item> out = new ArrayList<>();
        try {
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(
                    new StringReader(withoutProlog(xml)));
            boolean inItem = false;
            String title = "", link = "", description = "", category = "", isin = "";
            String pubDate = null;
            try {
                while (r.hasNext()) {
                    int event = r.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String ln = r.getLocalName();
                        if ("item".equals(ln) || "entry".equals(ln)) {
                            inItem = true;
                            title = link = description = category = isin = "";
                            pubDate = null;
                        } else if (inItem) {
                            switch (ln) {
                                case "title" -> title = textOf(r);
                                case "link" -> link = textOf(r);
                                case "description", "summary" -> description = textOf(r);
                                case "category" -> category = category.isEmpty()
                                        ? textOf(r) : category + " " + textOf(r);
                                case "isin" -> isin = textOf(r);
                                case "pubDate", "published", "updated" -> pubDate = textOf(r);
                                // "date" is RSS 1.0's dc:date - an RDF feed
                                // carries no pubDate at all, so reading only
                                // that name dated every one of its items to
                                // null and the freshness filter dropped the
                                // whole feed silently (Nikkei, 2026-08-11).
                                // It only FILLS IN: a feed that ships both a
                                // proper pubDate and a house-formatted date
                                // of its own would otherwise have the good one
                                // overwritten by the unparseable one (rbc.ru
                                // carries "10.08.2026" beside it - measured on
                                // the same sweep, which is how this rule was
                                // learned).
                                case "date" -> {
                                    String own = textOf(r);
                                    if (pubDate == null || pubDate.isBlank()) pubDate = own;
                                }
                                default -> { /* ignored */ }
                            }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT
                            && ("item".equals(r.getLocalName()) || "entry".equals(r.getLocalName()))) {
                        inItem = false;
                        if (!title.isBlank()) {
                            out.add(new Item(stripHtml(title), link, stripHtml(description),
                                    category, isin, parseDate(pubDate)));
                        }
                    }
                }
            } finally {
                r.close();
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    /**
     * Everything before the document's first {@code <} - a byte-order mark or
     * stray whitespace the publisher put in front of the XML declaration.
     * StAX calls that "content in prolog" and refuses the WHOLE document, so
     * three catalogued feeds answered a healthy 200 with a hundred fresh items
     * and delivered nothing at all (kommersant.ru, rss.eastmoney.com,
     * derstandard.at - all three carry a UTF-8 BOM, measured 2026-08-11).
     * A BOM is an encoding artefact, not content; it is not the feed's fault
     * and must not cost the feed.
     */
    static String withoutProlog(String xml) {
        int first = xml.indexOf('<');
        return first <= 0 ? xml : xml.substring(first);
    }

    /** RFC-1123 (with a lenient no-weekday fallback) or ISO instant/offset; unparseable → null. */
    static Instant parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        // "Mon, 10 Aug 2026 10:00:00 Z" - a zone the RFC does not know and
        // Java therefore refuses, which cost an entire feed its dates
        // (derstandard.at, 2026-08-11). The letter means UTC; say so in the
        // spelling the parser accepts.
        if (t.endsWith(" Z")) t = t.substring(0, t.length() - 2) + " GMT";
        else if (t.endsWith(" UTC")) t = t.substring(0, t.length() - 4) + " GMT";
        try {
            return ZonedDateTime.parse(t, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
        } catch (Exception ignored) {
        }
        try {
            int comma = t.indexOf(',');
            return ZonedDateTime.parse(comma >= 0 ? t.substring(comma + 1).trim() : t,
                    RFC_1123_NO_DOW).toInstant();
        } catch (Exception ignored) {
        }
        try {
            return java.time.OffsetDateTime.parse(t).toInstant();
        } catch (Exception ignored) {
        }
        try {
            // investing.com emits a zone-less "2026-07-14 17:54:56" — GMT in
            // practice (matches the feed's lastBuildDate against wall clock).
            return java.time.LocalDateTime.parse(t, ZONELESS)
                    .atZone(java.time.ZoneOffset.UTC).toInstant();
        } catch (Exception ignored) {
        }
        return null;
    }

    private static final DateTimeFormatter ZONELESS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Feeds embed teaser HTML in descriptions; the briefing wants plain text. */
    static String stripHtml(String s) {
        if (s == null) return "";
        return s.replaceAll("(?s)<!\\[CDATA\\[(.*?)]]>", "$1")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    /**
     * The element's text, tolerant of MARKUP INSIDE it. {@code getElementText}
     * throws the moment a child element appears - and a feed that ships the
     * full article body with its links inside the item ({@code rbc.ru}) then
     * lost not that one field but, through the outer catch, THE WHOLE FEED
     * (measured 2026-08-11: the Russian wire answered 200 with a hundred fresh
     * items and delivered none). Text is collected, nested markup is walked
     * past, and the reader always lands on the element's own END_ELEMENT.
     */
    private static String textOf(XMLStreamReader r) throws Exception {
        StringBuilder text = new StringBuilder();
        int depth = 0;
        while (r.hasNext()) {
            int event = r.next();
            if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                if (depth == 0) text.append(r.getText());
            } else if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                if (depth == 0) break;
                depth--;
            }
        }
        return text.toString().trim();
    }

    private static XMLInputFactory newHardenedFactory() {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        return f;
    }
}
