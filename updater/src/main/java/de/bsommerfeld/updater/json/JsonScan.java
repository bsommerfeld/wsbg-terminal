package de.bsommerfeld.updater.json;

/**
 * Shared low-level scan primitives for the hand-rolled JSON readers in this
 * package ({@link ManifestJson}, {@link ReleaseJson}).
 *
 * <p>
 * These are deliberately minimal, dependency-free helpers. {@link #extractString}
 * is a naive first-hit lookup safe only on the fixed, trusted manifest shape;
 * {@link #findMatchingBracket} is string/escape aware and safe on untrusted
 * release JSON (see {@link ReleaseJson}).
 */
final class JsonScan {

    private JsonScan() {
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
    static String extractString(String json, String key) {
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
     * Finds the index of the closing bracket that matches the opening
     * bracket at {@code openIdx}. Tracks nesting depth so that inner
     * brackets (e.g. nested arrays or objects) don't cause a false match,
     * and skips string literals so bracket characters inside values (e.g.
     * markdown in a release body) don't corrupt the depth count.
     *
     * @param open  the opening bracket character ({@code '['} or {@code '{'})
     * @param close the closing bracket character ({@code ']'} or {@code '}'})
     * @throws JsonParseException if the end of the string is reached without
     *                            finding a match
     */
    static int findMatchingBracket(String json, int openIdx, char open, char close) {
        int depth = 0;
        boolean inString = false;
        for (int i = openIdx; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (c == '\\')
                    i++; // skip escaped char
                else if (c == '"')
                    inString = false;
            } else if (c == '"') {
                inString = true;
            } else if (c == open) {
                depth++;
            } else if (c == close) {
                depth--;
                if (depth == 0)
                    return i;
            }
        }
        throw new JsonParseException("Unmatched bracket at index " + openIdx);
    }
}
