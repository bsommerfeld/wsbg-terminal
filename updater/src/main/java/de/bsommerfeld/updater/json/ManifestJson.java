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

        /*
         * Find the key, not the array. A first-hit search is safe here only
         * because the manifest is machine-emitted and carries no free text in
         * which the literal "files" could appear as a value.
        */
        int arrayStart = json.indexOf("\"files\"");
        if (arrayStart == -1)
            throw new JsonParseException("Missing 'files' array");

        /*
         * Step from the key to the value. Separate from the lookup above so a
         * manifest with a "files" key but no array reports that, not a missing key.
        */
        arrayStart = json.indexOf('[', arrayStart);
        if (arrayStart == -1)
            throw new JsonParseException("Malformed 'files' array — no opening bracket");

        /*
         * Depth-counted rather than indexOf(']'), because the array's own file
         * objects are nested and a path may legally contain a bracket.
        */
        int arrayEnd = JsonScan.findMatchingBracket(json, arrayStart, '[', ']');

        /*
         * Narrow the working window to the array interior, so the loop below can
         * never wander into keys that follow the array and happen to be named
         * "path" or "size".
        */
        String arrayContent = json.substring(arrayStart + 1, arrayEnd);

        int cursor = 0;
        while (cursor < arrayContent.length()) {
            int objStart = arrayContent.indexOf('{', cursor);

            /*
             * No further object: the rest is whitespace, or "files" was empty.
             * An empty array is legal and yields an empty entry list.
            */
            if (objStart == -1)
                break;

            /*
             * Cut the entry out whole, so the field lookups below cannot bleed
             * into the next entry when a key is missing from this one.
            */
            int objEnd = JsonScan.findMatchingBracket(arrayContent, objStart, '{', '}');
            String obj = arrayContent.substring(objStart, objEnd + 1);

            String path = JsonScan.extractString(obj, "path");
            String sha256 = JsonScan.extractString(obj, "sha256");
            long size = extractLong(obj, "size");

            entries.add(new FileEntry(path, sha256, size));

            /*
             * Resume past the entry just consumed - without this the indexOf
             * above would keep finding the same '{' and never terminate.
            */
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

        /*
         * Quoted on both sides so that looking for "size" cannot match a
         * longer key such as "size_hint" or "filesize".
        */
        String pattern = "\"" + key + "\"";
        int keyIdx = json.indexOf(pattern);
        if (keyIdx == -1)
            throw new JsonParseException("Missing key: " + key);

        /*
         * Search past the key itself, so the colon found is this key's own
         * separator and not one from an earlier pair.
        */
        int colonIdx = json.indexOf(':', keyIdx + pattern.length());

        /*
         * JSON permits arbitrary whitespace between colon and value, and the
         * action pretty-prints the manifest, so the value rarely starts at
         * colonIdx + 1.
        */
        int start = colonIdx + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) {
            start++;
        }

        /*
         * Run to the first character that cannot belong to a number; the ',' or
         * '}' that ends the pair is what stops this. The scan stays permissive
         * (it would accept "1-2") because parseLong below rejects what it must -
         * the same route a missing colon takes, which lands here on an empty span.
        */
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
