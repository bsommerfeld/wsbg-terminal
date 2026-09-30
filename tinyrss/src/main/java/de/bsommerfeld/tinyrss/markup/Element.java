package de.bsommerfeld.tinyrss.markup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One element of a parsed document. Children are elements and text, in
 * document order; text is already decoded. Names compare without regard to
 * case, because broken feeds write {@code <pubdate>} as readily as
 * {@code <pubDate>}.
 */
public final class Element {

    /** HTML elements without content, written back as {@code <br/>}. */
    private static final Set<String> EMPTY_IN_HTML = Set.of(
            "area", "base", "br", "col", "embed", "hr", "img", "input", "meta", "param", "source", "track", "wbr");

    private final String name;
    private final String prefix;
    private final String localName;
    private final Namespace namespace;
    private final Map<String, String> attributes;
    private final String base;
    private final List<Object> children = new ArrayList<>();

    Element(String name, Namespace namespace, Map<String, String> attributes, String base) {
        this.name = name;
        int colon = name.indexOf(':');
        this.prefix = colon > 0 ? name.substring(0, colon) : "";
        this.localName = colon > 0 ? name.substring(colon + 1) : name;
        this.namespace = namespace;
        this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        this.base = base;
    }

    /** The name as written, prefix included. */
    public String name() {
        return name;
    }

    public String prefix() {
        return prefix;
    }

    public String localName() {
        return localName;
    }

    public Namespace namespace() {
        return namespace;
    }

    /** The address relative links in this element resolve against: {@code xml:base}, else the document's. */
    public String base() {
        return base;
    }

    public boolean is(Namespace namespace, String localName) {
        return this.namespace == namespace && this.localName.equalsIgnoreCase(localName);
    }

    public boolean isIn(Set<Namespace> namespaces, String localName) {
        return namespaces.contains(namespace) && this.localName.equalsIgnoreCase(localName);
    }

    /** Attributes by name as written, {@code xmlns} declarations included. */
    public Map<String, String> attributes() {
        return attributes;
    }

    /**
     * An attribute by name, any case; a prefixed attribute is also found by its
     * local name ({@code rdf:about} as {@code about}).
     *
     * @return {@code null} when absent
     */
    public String attribute(String name) {
        String prefixed = null;
        for (Map.Entry<String, String> attribute : attributes.entrySet()) {
            String key = attribute.getKey();
            if (key.equalsIgnoreCase(name)) {
                return attribute.getValue();
            }
            int colon = key.indexOf(':');
            if (prefixed == null && colon > 0 && !key.startsWith("xmlns")
                    && key.substring(colon + 1).equalsIgnoreCase(name)) {
                prefixed = attribute.getValue();
            }
        }
        return prefixed;
    }

    /** Elements and text strings, in document order. */
    public List<Object> children() {
        return Collections.unmodifiableList(children);
    }

    public List<Element> elements() {
        List<Element> elements = new ArrayList<>();
        for (Object child : children) {
            if (child instanceof Element element) {
                elements.add(element);
            }
        }
        return elements;
    }

    public boolean hasElements() {
        for (Object child : children) {
            if (child instanceof Element) {
                return true;
            }
        }
        return false;
    }

    /** All text inside, tags dropped - the DOM's {@code textContent}. */
    public String text() {
        StringBuilder text = new StringBuilder();
        appendText(text);
        return text.toString();
    }

    /**
     * The content as markup - child elements written back as tags, text
     * escaped. What an Atom {@code xhtml} content or an RSS description with
     * unescaped HTML in it becomes.
     */
    public String innerMarkup() {
        StringBuilder markup = new StringBuilder();
        for (Object child : children) {
            if (child instanceof Element element) {
                element.appendMarkup(markup);
            } else {
                escape((String) child, markup, false);
            }
        }
        return markup.toString();
    }

    void add(Object child) {
        if (child instanceof String text && !children.isEmpty() && children.getLast() instanceof String previous) {
            children.set(children.size() - 1, previous + text);
        } else {
            children.add(child);
        }
    }

    private void appendText(StringBuilder text) {
        for (Object child : children) {
            if (child instanceof Element element) {
                element.appendText(text);
            } else {
                text.append((String) child);
            }
        }
    }

    private void appendMarkup(StringBuilder markup) {
        markup.append('<').append(localName);
        attributes.forEach((key, value) -> {
            if (!key.startsWith("xmlns")) {
                markup.append(' ').append(key).append("=\"");
                escape(value, markup, true);
                markup.append('"');
            }
        });
        if (children.isEmpty() && EMPTY_IN_HTML.contains(localName.toLowerCase(Locale.ROOT))) {
            markup.append("/>");
            return;
        }
        markup.append('>').append(innerMarkup()).append("</").append(localName).append('>');
    }

    private static void escape(String text, StringBuilder markup, boolean attribute) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> markup.append("&amp;");
                case '<' -> markup.append("&lt;");
                case '>' -> markup.append("&gt;");
                case '"' -> markup.append(attribute ? "&quot;" : "\"");
                default -> markup.append(c);
            }
        }
    }

    @Override
    public String toString() {
        return "<" + name + ">";
    }
}
