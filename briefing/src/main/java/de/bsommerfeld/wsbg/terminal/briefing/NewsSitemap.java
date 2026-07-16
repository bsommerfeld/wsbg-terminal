package de.bsommerfeld.wsbg.terminal.briefing;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal shared Google-News-sitemap reader ({@code <urlset><url>…}) — the
 * crawler-facing sibling of {@link Rss} for wires that publish no RSS anymore
 * (Reuters' Arc outboundfeeds). Per entry: the article {@code <loc>} —
 * strictly the FIRST loc, because an {@code <image:image>} block carries its
 * own same-local-name loc — the CDATA {@code <news:title>} and the ISO-8601
 * {@code <news:publication_date>}. Hardened StAX (XXE off), garbage in →
 * empty list out.
 */
final class NewsSitemap {

    /** One parsed {@code <url>} entry; absent fields are empty / null instant. */
    record Item(String loc, String title, Instant publishedAt) {
    }

    private static final XMLInputFactory XML_FACTORY = newHardenedFactory();

    private NewsSitemap() {
    }

    static List<Item> parse(String xml) {
        if (xml == null || xml.isBlank()) return List.of();
        List<Item> out = new ArrayList<>();
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
                            case "loc" -> loc = loc == null ? text : loc; // first wins
                            case "title" -> title = title == null ? text : title + text;
                            case "publication_date" ->
                                    pubDate = pubDate == null ? text : pubDate + text;
                            default -> { /* keywords, language, image — ignored */ }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        if ("url".equals(r.getLocalName())) {
                            inUrl = false;
                            if (title != null && !title.isBlank()) {
                                out.add(new Item(
                                        loc == null ? "" : loc.strip(),
                                        title.replaceAll("\\s+", " ").strip(),
                                        parseDate(pubDate)));
                            }
                        }
                        current = inUrl ? null : current;
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

    /** ISO-8601 with fractional seconds ("2026-07-16T16:54:42.616Z"); unparseable → null. */
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
