package de.bsommerfeld.updater.json;

import de.bsommerfeld.updater.model.FileEntry;
import de.bsommerfeld.updater.model.UpdateManifest;

import java.util.ArrayList;
import java.util.List;

/**
 * Parser for the fixed, trusted TinyUpdate manifest schema:
 * <pre>{@code
 * {
 *   "version": "1.0.0",
 *   "files": [
 *     { "path": "lib/core.jar", "sha256": "abc123...", "size": 12345 },
 *     ...
 *   ]
 * }
 * }</pre>
 *
 * <p>
 * The manifest is emitted by the TinyUpdate GitHub Action and contains no
 * escaped quotes or nested objects beyond the file array, so the naive
 * first-hit {@link JsonScan#extractString} scan is sufficient. This is
 * <em>not</em> a general-purpose parser.
 *
 * @see ReleaseJson for the harder, string-aware release-asset scan
 */
final class ManifestJson {

    private ManifestJson() {
    }

    /**
     * Parses a raw JSON string into an {@link UpdateManifest}.
     *
     * @throws JsonParseException if the input does not match the expected schema
     */
    static UpdateManifest parse(String json) {
        String version = JsonScan.extractString(json, "version");
        List<FileEntry> files = parseFileEntries(json);
        return new UpdateManifest(version, files);
    }

    /**
     * Locates the {@code "files"} array in the JSON, iterates over each
     * object inside it, and extracts the three required fields
     * ({@code path}, {@code sha256}, {@code size}) from each object.
     *
     * <p>
     * Object boundaries are detected via {@link JsonScan#findMatchingBracket}
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

        int arrayEnd = JsonScan.findMatchingBracket(json, arrayStart, '[', ']');

        String arrayContent = json.substring(arrayStart + 1, arrayEnd);

        int cursor = 0;
        while (cursor < arrayContent.length()) {
            int objStart = arrayContent.indexOf('{', cursor);
            if (objStart == -1)
                break;

            int objEnd = JsonScan.findMatchingBracket(arrayContent, objStart, '{', '}');
            String obj = arrayContent.substring(objStart, objEnd + 1);

            String path = JsonScan.extractString(obj, "path");
            String sha256 = JsonScan.extractString(obj, "sha256");
            long size = extractLong(obj, "size");

            entries.add(new FileEntry(path, sha256, size));
            cursor = objEnd + 1;
        }

        return entries;
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
}
