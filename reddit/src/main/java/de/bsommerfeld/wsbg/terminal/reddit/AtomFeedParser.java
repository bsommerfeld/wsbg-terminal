package de.bsommerfeld.wsbg.terminal.reddit;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses Reddit's Atom feeds into a flat list of {@link Entry entries} via StAX.
 * Owns the hardened {@link XMLInputFactory} (external entities + DTDs disabled,
 * XXE protection for remote feeds). Only the fields the domain needs are
 * captured; everything else (feed-level metadata, categories, icons) is ignored.
 */
public final class AtomFeedParser {

    /** Thread-safe factory configured once, reused for every parse. */
    private final XMLInputFactory xmlFactory;

    public AtomFeedParser() {
        this.xmlFactory = XMLInputFactory.newFactory();
        // Harden against XXE — these are remote feeds.
        this.xmlFactory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        this.xmlFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    }

    /** Mutable accumulator for one Atom {@code <entry>}. */
    public static final class Entry {
        public String id;
        public String title;
        public String content;
        public String authorName;
        public String published;
        public String updated;
        public String link;
        public String thumbnail;
    }

    /**
     * Parses an Atom feed into a flat list of entries.
     */
    public List<Entry> parse(String xml) throws Exception {
        List<Entry> entries = new ArrayList<>();
        XMLStreamReader r = xmlFactory.createXMLStreamReader(new StringReader(xml));
        boolean inEntry = false;
        boolean authorNameSet = false;
        Entry cur = null;
        try {
            while (r.hasNext()) {
                int event = r.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String ln = r.getLocalName();
                    if ("entry".equals(ln)) {
                        inEntry = true;
                        authorNameSet = false;
                        cur = new Entry();
                    } else if (inEntry && cur != null) {
                        switch (ln) {
                            case "id" -> cur.id = textOf(r);
                            case "title" -> cur.title = textOf(r);
                            case "content" -> cur.content = rawTextOf(r);
                            case "published" -> cur.published = textOf(r);
                            case "updated" -> cur.updated = textOf(r);
                            case "name" -> {
                                String n = textOf(r);
                                if (!authorNameSet) { cur.authorName = n; authorNameSet = true; }
                            }
                            case "link" -> {
                                String href = r.getAttributeValue(null, "href");
                                if (href != null && cur.link == null) cur.link = href;
                            }
                            case "thumbnail" -> {
                                String u = r.getAttributeValue(null, "url");
                                if (u != null && cur.thumbnail == null) cur.thumbnail = u;
                            }
                            default -> { /* ignore */ }
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if ("entry".equals(r.getLocalName())) {
                        if (cur != null) entries.add(cur);
                        inEntry = false;
                        cur = null;
                    }
                }
            }
        } finally {
            r.close();
        }
        return entries;
    }

    /** Reads the element's text content, trimmed. Safe for text-only elements. */
    private static String textOf(XMLStreamReader r) throws Exception {
        String t = r.getElementText();
        return t != null ? t.trim() : null;
    }

    /** Reads the element's text content without trimming (HTML content body). */
    private static String rawTextOf(XMLStreamReader r) throws Exception {
        return r.getElementText();
    }
}
