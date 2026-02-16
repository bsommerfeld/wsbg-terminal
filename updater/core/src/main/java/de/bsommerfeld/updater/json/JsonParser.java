package de.bsommerfeld.updater.json;

import de.bsommerfeld.updater.model.FileEntry;
import de.bsommerfeld.updater.model.UpdateManifest;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal, dependency-free JSON parser for {@link UpdateManifest}.
 * Avoids pulling Jackson/Gson into the Launcher's native-image classpath.
 *
 * <p>Only supports the exact schema we emit — not a general-purpose parser.
 * The expected format is:
 * <pre>{@code
 * {
 *   "version": "1.0.0",
 *   "files": [
 *     { "path": "lib/core.jar", "sha256": "abc123...", "size": 12345 },
 *     ...
 *   ]
 * }
 * }</pre>
 */
public final class JsonParser {

    private JsonParser() {}

    /**
     * Parses a raw JSON string into an {@link UpdateManifest}.
     *
     * @throws JsonParseException if the input does not match the expected schema
     */
    public static UpdateManifest parseManifest(String json) {
        String version = extractString(json, "version");
        List<FileEntry> files = parseFileEntries(json);
        return new UpdateManifest(version, files);
    }

    private static List<FileEntry> parseFileEntries(String json) {
        List<FileEntry> entries = new ArrayList<>();

        int arrayStart = json.indexOf("\"files\"");
        if (arrayStart == -1) throw new JsonParseException("Missing 'files' array");

        arrayStart = json.indexOf('[', arrayStart);
        if (arrayStart == -1) throw new JsonParseException("Malformed 'files' array — no opening bracket");

        int arrayEnd = findMatchingBracket(json, arrayStart, '[', ']');

        String arrayContent = json.substring(arrayStart + 1, arrayEnd);

        int cursor = 0;
        while (cursor < arrayContent.length()) {
            int objStart = arrayContent.indexOf('{', cursor);
            if (objStart == -1) break;

            int objEnd = findMatchingBracket(arrayContent, objStart, '{', '}');
            String obj = arrayContent.substring(objStart, objEnd + 1);

            String path = extractString(obj, "path");
            String sha256 = extractString(obj, "sha256");
            long size = extractLong(obj, "size");

            entries.add(new FileEntry(path, sha256, size));
            cursor = objEnd + 1;
        }

        return entries;
    }

    public static String extractString(String json, String key) {
        String pattern = "\"" + key + "\"";
        int keyIdx = json.indexOf(pattern);
        if (keyIdx == -1) throw new JsonParseException("Missing key: " + key);

        int colonIdx = json.indexOf(':', keyIdx + pattern.length());
        int quoteStart = json.indexOf('"', colonIdx + 1);
        int quoteEnd = json.indexOf('"', quoteStart + 1);

        if (quoteStart == -1 || quoteEnd == -1) {
            throw new JsonParseException("Malformed string value for key: " + key);
        }

        return json.substring(quoteStart + 1, quoteEnd);
    }

    private static long extractLong(String json, String key) {
        String pattern = "\"" + key + "\"";
        int keyIdx = json.indexOf(pattern);
        if (keyIdx == -1) throw new JsonParseException("Missing key: " + key);

        int colonIdx = json.indexOf(':', keyIdx + pattern.length());

        int start = colonIdx + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }

        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '-')) {
            end++;
        }

        try {
            return Long.parseLong(json.substring(start, end));
        } catch (NumberFormatException e) {
            throw new JsonParseException("Invalid number for key: " + key, e);
        }
    }

    private static int findMatchingBracket(String json, int openIdx, char open, char close) {
        int depth = 0;
        for (int i = openIdx; i < json.length(); i++) {
            if (json.charAt(i) == open) depth++;
            else if (json.charAt(i) == close) {
                depth--;
                if (depth == 0) return i;
            }
        }
        throw new JsonParseException("Unmatched bracket at index " + openIdx);
    }
}
