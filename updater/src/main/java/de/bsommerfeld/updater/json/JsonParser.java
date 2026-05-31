package de.bsommerfeld.updater.json;

import de.bsommerfeld.updater.model.FileEntry;
import de.bsommerfeld.updater.model.UpdateManifest;

import java.util.ArrayList;
import java.util.List;

/**
 * Minimal, dependency-free JSON parser for the TinyUpdate manifest format.
 *
 * <h3>Why not Jackson/Gson?</h3>
 * The updater module is bundled into the JPackage native launcher, which
 * produces a minimal runtime image. Adding a full JSON library would
 * increase the launcher size significantly and introduce transitive
 * dependencies (annotations, reflection modules). Since the manifest
 * schema is fixed and trivial, hand-parsing is both smaller and faster.
 *
 * <h3>Supported schema</h3>
 * Only the exact JSON structure emitted by the TinyUpdate GitHub Action
 * is recognized — this is not a general-purpose parser. The expected
 * format is:
 * <pre>{@code
 * {
 * "version": "1.0.0",
 * "files": [
 * { "path": "lib/core.jar", "sha256": "abc123...", "size": 12345 },
 * ...
 * ]
 * }
 * }</pre>
 *
 * <h3>Parsing strategy</h3>
 * All methods use index-based scanning ({@code String.indexOf}) rather
 * than tokenization or character-by-character state machines. This is
 * sufficient because the manifest contains no escaped quotes, no nested
 * objects beyond the file array, and no ambiguous key names.
 *
 * @see UpdateManifest
 */
public final class JsonParser {

    private JsonParser() {
    }

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

    /**
     * Locates the {@code "files"} array in the JSON, iterates over each
     * object inside it, and extracts the three required fields
     * ({@code path}, {@code sha256}, {@code size}) from each object.
     *
     * <p>
     * Object boundaries are detected via {@link #findMatchingBracket}
     * rather than simple indexOf, so nested structures (if any) don't
     * cause premature truncation.
     */
    private static List<FileEntry> parseFileEntries(String json) {
        List<FileEntry> entries = new ArrayList<>();

        int arrayStart = json.indexOf("\"files\"");
        if (arrayStart == -1)
            throw new JsonParseException("Missing 'files' array");

        arrayStart = json.indexOf('[', arrayStart);
        if (arrayStart == -1)
            throw new JsonParseException("Malformed 'files' array — no opening bracket");

        int arrayEnd = findMatchingBracket(json, arrayStart, '[', ']');

        String arrayContent = json.substring(arrayStart + 1, arrayEnd);

        int cursor = 0;
        while (cursor < arrayContent.length()) {
            int objStart = arrayContent.indexOf('{', cursor);
            if (objStart == -1)
                break;

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

    /**
     * Extracts a quoted string value for a given key from a JSON fragment.
     *
     * <p>
     * Scans for the pattern {@code "key": "value"} using index-based
     * string search. This is safe because the manifest schema guarantees
     * no escaped quotes inside values (paths, hex hashes, and version
     * strings never contain quote characters).
     *
     * @throws JsonParseException if the key is missing or the value is malformed
     */
    public static String extractString(String json, String key) {
        String pattern = "\"" + key + "\"";
        int keyIdx = json.indexOf(pattern);
        if (keyIdx == -1)
            throw new JsonParseException("Missing key: " + key);

        int colonIdx = json.indexOf(':', keyIdx + pattern.length());
        int quoteStart = json.indexOf('"', colonIdx + 1);
        int quoteEnd = json.indexOf('"', quoteStart + 1);

        if (quoteStart == -1 || quoteEnd == -1) {
            throw new JsonParseException("Malformed string value for key: " + key);
        }

        return json.substring(quoteStart + 1, quoteEnd);
    }

    /**
     * Extracts a numeric (long) value for a given key from a JSON fragment.
     *
     * <p>
     * After finding the colon following the key, skips whitespace and
     * reads consecutive digit/minus characters. This handles both positive
     * sizes and the theoretical negative sentinel values.
     *
     * @throws JsonParseException if the key is missing or the value is not a valid
     *                            number
     */
    private static long extractLong(String json, String key) {
        String pattern = "\"" + key + "\"";
        int keyIdx = json.indexOf(pattern);
        if (keyIdx == -1)
            throw new JsonParseException("Missing key: " + key);

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

    /**
     * Finds the index of the closing bracket that matches the opening
     * bracket at {@code openIdx}. Tracks nesting depth so that inner
     * brackets (e.g. nested arrays or objects) don't cause a false match.
     *
     * @param open the opening bracket character ({@code '['} or {@code '{'})
     *             @param close the closing bracket character ({@code ']'} or
     *             {@code '}'})
     * @throws JsonParseException if the end of the string is reached without
     *                            finding a match
     */
    private static int findMatchingBracket(String json, int openIdx, char open, char close) {
        int depth = 0;
        for (int i = openIdx; i < json.length(); i++) {
            if (json.charAt(i) == open)
                depth++;
            else if (json.charAt(i) == close) {
                depth--;
                if (depth == 0)
                    return i;
            }
        }
        throw new JsonParseException("Unmatched bracket at index " + openIdx);
    }
}
