package de.bsommerfeld.tinyrss.markup;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Reads XML the way a browser reads HTML: whatever the document gets wrong,
 * a tree comes out. An XML parser drops a whole feed for one bare {@code &},
 * one {@code <br>} in a description, one undeclared {@code &nbsp;}, one
 * {@code media:} prefix nobody declared or one script a CDN appends after the
 * closing root tag; here each of those costs nothing but itself.
 *
 * <ul>
 *   <li><b>Text</b> - references decoded (XML's, numeric and HTML's named
 *       ones); an unknown name or a bare {@code &} stays literal; characters
 *       XML forbids are dropped. CDATA is taken as it stands.</li>
 *   <li><b>Tags</b> - an end tag closes the innermost open element of its
 *       name and everything opened inside it; an end tag nothing matches is
 *       ignored; elements still open at the end are closed. HTML's empty
 *       elements ({@code <br>}, {@code <img>}) and the feed elements that
 *       never have content ({@code <enclosure>}, a {@code <link href>}) open
 *       nothing. {@code <script>} and {@code <style>} hold raw text.</li>
 *   <li><b>Namespaces</b> - resolved from the declarations in scope; an
 *       undeclared prefix is taken for what it usually means
 *       ({@link Namespace#ofUndeclaredPrefix}).</li>
 *   <li><b>Never fetched</b> - DOCTYPEs are skipped, entities are only the
 *       fixed tables: no external entity, no entity expansion.</li>
 * </ul>
 */
public final class MarkupParser {

    /** Deeper than this, elements are kept but open nothing - tag soup must not overflow the stack. */
    private static final int MAX_DEPTH = 256;

    private static final Set<String> EMPTY = Set.of(
            "area", "base", "br", "col", "embed", "hr", "img", "input", "keygen", "meta", "param", "track", "wbr",
            "enclosure", "thumbnail");

    private static final Set<String> RAW_TEXT = Set.of("script", "style");

    private final String text;
    private final Deque<Frame> open = new ArrayDeque<>();
    private int position;

    private MarkupParser(String text) {
        this.text = text;
    }

    /**
     * Parses {@code text}; never fails.
     *
     * @param base the document's address, for relative links; may be {@code null}
     * @return a synthetic {@code #document} element holding everything at the top level
     */
    public static Element parse(String text, String base) {
        return new MarkupParser(text).run(base);
    }

    private Element run(String base) {
        Element document = new Element("#document", Namespace.NONE, Map.of(), base);
        open.push(new Frame(document, Map.of()));
        while (position < text.length()) {
            int tag = text.indexOf('<', position);
            if (tag < 0) {
                addText(text.substring(position), true);
                break;
            }
            if (tag > position) {
                addText(text.substring(position, tag), true);
            }
            position = tag;
            if (text.startsWith("<!--", position)) {
                position = after("-->", position + 4);
            } else if (text.startsWith("<![CDATA[", position)) {
                int end = text.indexOf("]]>", position + 9);
                addText(text.substring(position + 9, end < 0 ? text.length() : end), false);
                position = end < 0 ? text.length() : end + 3;
            } else if (text.startsWith("<!", position)) {
                skipDeclaration();
            } else if (text.startsWith("<?", position)) {
                position = after("?>", position + 2);
            } else if (text.startsWith("</", position)) {
                endTag();
            } else if (position + 1 < text.length() && isNameStart(text.charAt(position + 1))) {
                startTag();
            } else {
                addText("<", false);
                position++;
            }
        }
        return document;
    }

    // ---- tags ---------------------------------------------------------------

    private void startTag() {
        position++;
        String name = readName();
        Map<String, String> attributes = new LinkedHashMap<>();
        boolean closed = false;
        while (position < text.length()) {
            skipWhitespace();
            if (position >= text.length()) {
                break;
            }
            char c = text.charAt(position);
            if (c == '>') {
                position++;
                break;
            }
            if (c == '/') {
                position++;
                if (position < text.length() && text.charAt(position) == '>') {
                    position++;
                    closed = true;
                    break;
                }
                continue;
            }
            if (c == '<') {
                break;
            }
            readAttribute(attributes);
        }
        open(name, attributes, closed);
    }

    private void readAttribute(Map<String, String> attributes) {
        int start = position;
        while (position < text.length()) {
            char c = text.charAt(position);
            if (Character.isWhitespace(c) || c == '=' || c == '>' || c == '/' || c == '<') {
                break;
            }
            position++;
        }
        if (position == start) {
            position++;
            return;
        }
        String name = text.substring(start, position);
        skipWhitespace();
        String value = "";
        if (position < text.length() && text.charAt(position) == '=') {
            position++;
            skipWhitespace();
            value = readValue();
        }
        attributes.putIfAbsent(name, Entities.decode(value).replace('\t', ' ').replace('\n', ' ').replace('\r', ' '));
    }

    private String readValue() {
        if (position >= text.length()) {
            return "";
        }
        char quote = text.charAt(position);
        if (quote == '"' || quote == '\'') {
            int end = text.indexOf(quote, position + 1);
            if (end < 0) {
                end = text.indexOf('>', position + 1);
                end = end < 0 ? text.length() : end;
                String value = text.substring(position + 1, end);
                position = end;
                return value;
            }
            String value = text.substring(position + 1, end);
            position = end + 1;
            return value;
        }
        int start = position;
        while (position < text.length() && !Character.isWhitespace(text.charAt(position))
                && text.charAt(position) != '>') {
            position++;
        }
        return text.substring(start, position);
    }

    private void open(String name, Map<String, String> attributes, boolean closed) {
        Frame parent = open.peek();
        Map<String, String> scope = scope(parent.scope, attributes);
        String xmlBase = attributes.get("xml:base");
        String base = xmlBase == null ? parent.element.base() : Urls.resolve(parent.element.base(), xmlBase);
        Element element = new Element(name, namespace(name, scope), attributes, base);
        parent.element.add(element);

        String local = element.localName().toLowerCase(Locale.ROOT);
        boolean empty = EMPTY.contains(local) || local.equals("link") && element.attribute("href") != null;
        if (closed || empty || open.size() > MAX_DEPTH) {
            return;
        }
        if (RAW_TEXT.contains(local)) {
            int end = indexOfIgnoreCase("</" + local, position);
            element.add(clean(text.substring(position, end < 0 ? text.length() : end)));
            position = end < 0 ? text.length() : after(">", end);
            return;
        }
        open.push(new Frame(element, scope));
    }

    private void endTag() {
        position += 2;
        int start = position;
        while (position < text.length()) {
            char c = text.charAt(position);
            if (Character.isWhitespace(c) || c == '>' || c == '<') {
                break;
            }
            position++;
        }
        String name = text.substring(start, position);
        int close = text.indexOf('>', position);
        int next = text.indexOf('<', position);
        if (close < 0 || next >= 0 && next < close) {
            position = next < 0 ? text.length() : next;
        } else {
            position = close + 1;
        }
        for (Frame frame : open) {
            if (frame.element.name().equalsIgnoreCase(name)) {
                while (open.peek() != frame) {
                    open.pop();
                }
                open.pop();
                return;
            }
        }
    }

    /** {@code <!DOCTYPE ...>} with its internal subset, or any other {@code <!...>}: skipped, never read. */
    private void skipDeclaration() {
        int depth = 0;
        char quote = 0;
        for (int i = position + 2; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
            } else if (c == '>' && depth <= 0) {
                position = i + 1;
                return;
            }
        }
        position = after(">", position + 2);
    }

    // ---- text ---------------------------------------------------------------

    private void addText(String raw, boolean decode) {
        String cleaned = clean(raw);
        if (!cleaned.isEmpty()) {
            open.peek().element.add(decode ? Entities.decode(cleaned) : cleaned);
        }
    }

    /** Line ends as {@code \n}; characters XML forbids dropped. */
    private static String clean(String raw) {
        StringBuilder cleaned = null;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean forbidden = c < 0x20 && c != '\t' && c != '\n' && c != '\r' || c == '￾' || c == '￿';
            if (forbidden || c == '\r') {
                if (cleaned == null) {
                    cleaned = new StringBuilder(raw.length()).append(raw, 0, i);
                }
                if (c == '\r' && (i + 1 >= raw.length() || raw.charAt(i + 1) != '\n')) {
                    cleaned.append('\n');
                }
            } else if (cleaned != null) {
                cleaned.append(c);
            }
        }
        return cleaned == null ? raw : cleaned.toString();
    }

    // ---- names and namespaces ---------------------------------------------

    private String readName() {
        int start = position;
        while (position < text.length() && isNameChar(text.charAt(position))) {
            position++;
        }
        return text.substring(start, position);
    }

    private static Map<String, String> scope(Map<String, String> inherited, Map<String, String> attributes) {
        Map<String, String> scope = null;
        for (Map.Entry<String, String> attribute : attributes.entrySet()) {
            String key = attribute.getKey();
            if (key.equals("xmlns") || key.startsWith("xmlns:")) {
                if (scope == null) {
                    scope = new HashMap<>(inherited);
                }
                scope.put(key.equals("xmlns") ? "" : key.substring(6), attribute.getValue().trim());
            }
        }
        return scope == null ? inherited : scope;
    }

    private static Namespace namespace(String name, Map<String, String> scope) {
        int colon = name.indexOf(':');
        if (colon <= 0) {
            return Namespace.ofUri(scope.get(""));
        }
        String prefix = name.substring(0, colon);
        String uri = scope.get(prefix);
        return uri != null ? Namespace.ofUri(uri) : Namespace.ofUndeclaredPrefix(prefix);
    }

    private static boolean isNameStart(char c) {
        return Character.isLetter(c) || c == '_';
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == ':';
    }

    // ---- scanning -----------------------------------------------------------

    private void skipWhitespace() {
        while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
            position++;
        }
    }

    /** The index after the next {@code marker} from {@code from}, or the end of the text. */
    private int after(String marker, int from) {
        int index = text.indexOf(marker, from);
        return index < 0 ? text.length() : index + marker.length();
    }

    private int indexOfIgnoreCase(String marker, int from) {
        for (int i = from; i <= text.length() - marker.length(); i++) {
            if (text.regionMatches(true, i, marker, 0, marker.length())) {
                return i;
            }
        }
        return -1;
    }

    /** An open element and the namespace declarations in its scope. */
    private record Frame(Element element, Map<String, String> scope) {
    }
}
