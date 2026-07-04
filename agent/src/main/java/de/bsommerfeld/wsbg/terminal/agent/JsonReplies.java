package de.bsommerfeld.wsbg.terminal.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Small, shared helpers for reading a (possibly-broken) JSON reply from the 4B
 * model: the lenient outer-object parse, a quote/escape-aware string-field
 * recovery, and integer-array extraction. Used by the subject extractor, the
 * compose reply parser and the discrete judges. Extracted verbatim from
 * {@link EditorialAgent}.
 */
final class JsonReplies {

    private JsonReplies() {}

    private static final Logger LOG = LoggerFactory.getLogger(JsonReplies.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Lenient JSON extraction — models wrap the object in ```json fences or
     * stray prose. Grabs the outermost {@code { ... }} and parses it.
     */
    static JsonNode parseJson(String text) {
        if (text == null) return null;
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        try {
            return JSON.readTree(text.substring(start, end + 1));
        } catch (Exception e) {
            LOG.debug("JSON parse failed: {}", e.getMessage());
            return null;
        }
    }

    /** Extracts a {@code "key": "value"} string (quote/escape-aware) from possibly-broken JSON. */
    static String regexStringField(String text, String key) {
        if (text == null) return null;
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(text);
        return m.find() ? m.group(1).replace("\\\"", "\"").trim() : null;
    }

    /** Integers out of a JSON array node; non-numbers and anything else → skipped. */
    static List<Integer> readInts(JsonNode node) {
        List<Integer> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode el : node) {
                if (el.canConvertToInt()) out.add(el.asInt());
            }
        }
        return out;
    }
}
