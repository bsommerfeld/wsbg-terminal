package de.bsommerfeld.tinyreddit.mapping;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads Reddit's Atom feeds into flat {@link Entry entries} via StAX. Only
 * the fields the model needs are kept. External entities and DTDs are off:
 * the feed is remote input.
 */
public final class AtomFeed {

    /** One {@code <entry>}. */
    public record Entry(String id, String title, String content, String author,
            String published, String updated, String link, String thumbnail) {
    }

    private static final XMLInputFactory FACTORY = hardenedFactory();

    private AtomFeed() {
    }

    public static List<Entry> parse(String xml) throws XMLStreamException {
        List<Entry> entries = new ArrayList<>();
        XMLStreamReader reader = FACTORY.createXMLStreamReader(new StringReader(xml));
        try {
            EntryBuilder current = null;
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = reader.getLocalName();
                    if (name.equals("entry")) {
                        current = new EntryBuilder();
                    } else if (current != null) {
                        current.read(name, reader);
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT
                        && reader.getLocalName().equals("entry") && current != null) {
                    entries.add(current.build());
                    current = null;
                }
            }
        } finally {
            reader.close();
        }
        return entries;
    }

    private static XMLInputFactory hardenedFactory() {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        return factory;
    }

    private static final class EntryBuilder {
        String id;
        String title;
        String content;
        String author;
        String published;
        String updated;
        String link;
        String thumbnail;

        void read(String name, XMLStreamReader reader) throws XMLStreamException {
            switch (name) {
                case "id" -> id = reader.getElementText().trim();
                case "title" -> title = reader.getElementText().trim();
                case "content" -> content = reader.getElementText();
                case "published" -> published = reader.getElementText().trim();
                case "updated" -> updated = reader.getElementText().trim();
                case "name" -> {
                    String text = reader.getElementText().trim();
                    if (author == null) {
                        author = text;
                    }
                }
                case "link" -> {
                    if (link == null) {
                        link = reader.getAttributeValue(null, "href");
                    }
                }
                case "thumbnail" -> {
                    if (thumbnail == null) {
                        thumbnail = reader.getAttributeValue(null, "url");
                    }
                }
                default -> {
                    // not needed
                }
            }
        }

        Entry build() {
            return new Entry(id, title, content, author, published, updated, link, thumbnail);
        }
    }
}
