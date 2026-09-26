package de.bsommerfeld.tinyreddit.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A strict RFC 8259 parser into {@link JsonNode} trees.
 *
 * <h3>Why its own</h3>
 * The framework carries no dependencies (as TinyUpdate does not), and Reddit's
 * payloads only need reading - a tree and {@code path()} navigation, nothing a
 * data-binding library adds. A few hundred lines instead of a jar.
 *
 * <h3>Limits</h3>
 * Nesting deeper than {@value #MAX_DEPTH} is refused rather than risking the
 * stack; Reddit's deepest comment trees stay far below it (every reply level
 * costs four: {@code replies.data.children[i].data}).
 */
public final class Json {

    static final int MAX_DEPTH = 512;

    private final String text;
    private int position;
    private int depth;

    private Json(String text) {
        this.text = text;
    }

    /**
     * @throws JsonException on anything that is not exactly one JSON value
     */
    public static JsonNode parse(String text) {
        Json parser = new Json(text);
        parser.skipWhitespace();
        JsonNode value = parser.value();
        parser.skipWhitespace();
        if (parser.position != text.length()) {
            throw parser.error("trailing content");
        }
        return value;
    }

    private JsonNode value() {
        if (position >= text.length()) {
            throw error("unexpected end");
        }
        char c = text.charAt(position);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> JsonNode.string(string());
            case 't' -> literal("true", JsonNode.TRUE);
            case 'f' -> literal("false", JsonNode.FALSE);
            case 'n' -> literal("null", JsonNode.NULL);
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) {
                    yield number();
                }
                throw error("unexpected '" + c + "'");
            }
        };
    }

    private JsonNode object() {
        enter();
        position++; // {
        Map<String, JsonNode> members = new LinkedHashMap<>();
        skipWhitespace();
        if (peek() == '}') {
            position++;
            leave();
            return JsonNode.object(members);
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("expected a member name");
            }
            String name = string();
            skipWhitespace();
            expect(':');
            skipWhitespace();
            members.put(name, value());
            skipWhitespace();
            char next = next();
            if (next == '}') {
                leave();
                return JsonNode.object(members);
            }
            if (next != ',') {
                throw error("expected ',' or '}'");
            }
        }
    }

    private JsonNode array() {
        enter();
        position++; // [
        List<JsonNode> elements = new ArrayList<>();
        skipWhitespace();
        if (peek() == ']') {
            position++;
            leave();
            return JsonNode.array(elements);
        }
        while (true) {
            skipWhitespace();
            elements.add(value());
            skipWhitespace();
            char next = next();
            if (next == ']') {
                leave();
                return JsonNode.array(elements);
            }
            if (next != ',') {
                throw error("expected ',' or ']'");
            }
        }
    }

    private String string() {
        position++; // opening quote
        StringBuilder out = null;
        int start = position;
        while (true) {
            if (position >= text.length()) {
                throw error("unterminated string");
            }
            char c = text.charAt(position);
            if (c == '"') {
                String result = out == null
                        ? text.substring(start, position)
                        : out.append(text, start, position).toString();
                position++;
                return result;
            }
            if (c < 0x20) {
                throw error("control character in string");
            }
            if (c == '\\') {
                if (out == null) {
                    out = new StringBuilder();
                }
                out.append(text, start, position);
                position++;
                out.append(escape());
                start = position;
                continue;
            }
            position++;
        }
    }

    /** One escape after the backslash; {@code \\u} pairs surrogates by simply appending both halves. */
    private char escape() {
        char c = next();
        return switch (c) {
            case '"' -> '"';
            case '\\' -> '\\';
            case '/' -> '/';
            case 'b' -> '\b';
            case 'f' -> '\f';
            case 'n' -> '\n';
            case 'r' -> '\r';
            case 't' -> '\t';
            case 'u' -> {
                if (position + 4 > text.length()) {
                    throw error("short \\u escape");
                }
                try {
                    char decoded = (char) Integer.parseInt(text, position, position + 4, 16);
                    position += 4;
                    yield decoded;
                } catch (NumberFormatException e) {
                    throw error("bad \\u escape");
                }
            }
            default -> throw error("bad escape '\\" + c + "'");
        };
    }

    private JsonNode number() {
        int start = position;
        if (peek() == '-') {
            position++;
        }
        if (peek() == '0') {
            position++;
        } else if (isDigit(peek())) {
            while (isDigit(peek())) {
                position++;
            }
        } else {
            throw error("bad number");
        }
        if (peek() == '.') {
            position++;
            if (!isDigit(peek())) {
                throw error("bad fraction");
            }
            while (isDigit(peek())) {
                position++;
            }
        }
        if (peek() == 'e' || peek() == 'E') {
            position++;
            if (peek() == '+' || peek() == '-') {
                position++;
            }
            if (!isDigit(peek())) {
                throw error("bad exponent");
            }
            while (isDigit(peek())) {
                position++;
            }
        }
        return JsonNode.number(text.substring(start, position));
    }

    private JsonNode literal(String word, JsonNode node) {
        if (!text.startsWith(word, position)) {
            throw error("expected " + word);
        }
        position += word.length();
        return node;
    }

    private void enter() {
        if (++depth > MAX_DEPTH) {
            throw error("nested deeper than " + MAX_DEPTH);
        }
    }

    private void leave() {
        depth--;
    }

    private void skipWhitespace() {
        while (position < text.length()) {
            char c = text.charAt(position);
            if (c != ' ' && c != '\n' && c != '\r' && c != '\t') {
                return;
            }
            position++;
        }
    }

    private void expect(char expected) {
        if (next() != expected) {
            throw error("expected '" + expected + "'");
        }
    }

    private char peek() {
        return position < text.length() ? text.charAt(position) : '\0';
    }

    private char next() {
        if (position >= text.length()) {
            throw error("unexpected end");
        }
        return text.charAt(position++);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private JsonException error(String message) {
        return new JsonException(message + " at " + position);
    }
}
