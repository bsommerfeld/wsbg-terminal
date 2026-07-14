package de.bsommerfeld.wsbg.terminal.briefing;

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
            XMLStreamReader r = XML_FACTORY.createXMLStreamReader(new StringReader(xml));
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

    /** RFC-1123 (with a lenient no-weekday fallback) or ISO instant/offset; unparseable → null. */
    static Instant parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
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

    private static String textOf(XMLStreamReader r) throws Exception {
        String t = r.getElementText();
        return t != null ? t.trim() : "";
    }

    private static XMLInputFactory newHardenedFactory() {
        XMLInputFactory f = XMLInputFactory.newFactory();
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        return f;
    }
}
