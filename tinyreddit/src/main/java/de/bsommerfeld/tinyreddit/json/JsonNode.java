package de.bsommerfeld.tinyreddit.json;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * One parsed JSON value. Navigation never throws: {@link #path} and
 * {@link #at} on anything that has no such member return {@link #MISSING},
 * and the {@code as...} readers fall back to the default given - the same
 * contract Jackson's {@code path()} offers, which is what makes deep reads
 * like {@code root.path("data").path("children")} safe on partial payloads.
 */
public final class JsonNode {

    /** What a value is. */
    public enum Type { OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL, MISSING }

    /** Stands for any member or element that is not there. */
    public static final JsonNode MISSING = new JsonNode(Type.MISSING, null);

    static final JsonNode NULL = new JsonNode(Type.NULL, null);
    static final JsonNode TRUE = new JsonNode(Type.BOOLEAN, Boolean.TRUE);
    static final JsonNode FALSE = new JsonNode(Type.BOOLEAN, Boolean.FALSE);

    private final Type type;
    private final Object value;

    private JsonNode(Type type, Object value) {
        this.type = type;
        this.value = value;
    }

    static JsonNode object(Map<String, JsonNode> members) {
        return new JsonNode(Type.OBJECT, Collections.unmodifiableMap(members));
    }

    static JsonNode array(List<JsonNode> elements) {
        return new JsonNode(Type.ARRAY, Collections.unmodifiableList(elements));
    }

    static JsonNode string(String text) {
        return new JsonNode(Type.STRING, text);
    }

    /** Numbers keep their source text; readers convert on demand. */
    static JsonNode number(String literal) {
        return new JsonNode(Type.NUMBER, literal);
    }

    public Type type() {
        return type;
    }

    public boolean isObject() {
        return type == Type.OBJECT;
    }

    public boolean isArray() {
        return type == Type.ARRAY;
    }

    public boolean isNull() {
        return type == Type.NULL;
    }

    public boolean isMissing() {
        return type == Type.MISSING;
    }

    /** Neither missing nor {@code null}. */
    public boolean isPresent() {
        return type != Type.MISSING && type != Type.NULL;
    }

    /** The member {@code name} of an object; {@link #MISSING} otherwise. */
    @SuppressWarnings("unchecked")
    public JsonNode path(String name) {
        if (type != Type.OBJECT) {
            return MISSING;
        }
        JsonNode member = ((Map<String, JsonNode>) value).get(name);
        return member == null ? MISSING : member;
    }

    /** Whether an object has the member, {@code null} values included. */
    public boolean has(String name) {
        return !path(name).isMissing();
    }

    /** Element {@code index} of an array; {@link #MISSING} otherwise. */
    public JsonNode at(int index) {
        List<JsonNode> elements = elements();
        return index >= 0 && index < elements.size() ? elements.get(index) : MISSING;
    }

    /** An array's elements; empty for anything else. */
    @SuppressWarnings("unchecked")
    public List<JsonNode> elements() {
        return type == Type.ARRAY ? (List<JsonNode>) value : List.of();
    }

    /** An object's members in source order; empty for anything else. */
    @SuppressWarnings("unchecked")
    public Map<String, JsonNode> members() {
        return type == Type.OBJECT ? (Map<String, JsonNode>) value : Map.of();
    }

    public int size() {
        return switch (type) {
            case ARRAY -> elements().size();
            case OBJECT -> members().size();
            default -> 0;
        };
    }

    /** Strings as they are, numbers and booleans as their text; {@code fallback} otherwise. */
    public String asText(String fallback) {
        return switch (type) {
            case STRING, NUMBER -> (String) value;
            case BOOLEAN -> value.toString();
            default -> fallback;
        };
    }

    public long asLong(long fallback) {
        Double number = numeric();
        return number == null ? fallback : number.longValue();
    }

    public int asInt(int fallback) {
        Double number = numeric();
        return number == null ? fallback : number.intValue();
    }

    public double asDouble(double fallback) {
        Double number = numeric();
        return number == null ? fallback : number;
    }

    public boolean asBoolean(boolean fallback) {
        return type == Type.BOOLEAN ? (Boolean) value : fallback;
    }

    /** Numbers, and strings that hold one (Reddit sends a few numbers quoted). */
    private Double numeric() {
        if (type != Type.NUMBER && type != Type.STRING) {
            return null;
        }
        try {
            return Double.parseDouble((String) value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return type + (value == null ? "" : ":" + value);
    }
}
