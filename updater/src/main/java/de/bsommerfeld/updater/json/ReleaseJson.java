package de.bsommerfeld.updater.json;

/**
 * Scanner for the <em>untrusted</em> GitHub release JSON payload.
 *
 * <p>
 * Unlike {@link ManifestJson} (a fixed, trusted shape), a release payload
 * carries a free-text {@code body} (arbitrary author markdown). This scanner
 * is therefore hardened: the search is scoped to the release's {@code "assets"}
 * array (so the {@code body} can never produce a false match), and within each
 * asset object only <em>top-level</em> keys are considered (so nested objects
 * like {@code uploader} cannot shadow the asset's own {@code name}).
 */
final class ReleaseJson {

    private ReleaseJson() {
    }

    /**
     * Extracts the {@code browser_download_url} of a named asset from a
     * GitHub release JSON payload, or {@code null} if no such asset exists.
     */
    static String extractAssetUrl(String releaseJson, String assetName) {
        // A literal "assets" (with unescaped quotes) cannot occur inside a
        // JSON string value, so the first hit is the real assets key.
        int keyIdx = releaseJson.indexOf("\"assets\"");
        if (keyIdx == -1)
            return null;
        int arrayStart = releaseJson.indexOf('[', keyIdx);
        if (arrayStart == -1)
            return null;
        int arrayEnd = JsonScan.findMatchingBracket(releaseJson, arrayStart, '[', ']');

        int cursor = arrayStart + 1;
        while (cursor < arrayEnd) {
            int objStart = releaseJson.indexOf('{', cursor);
            if (objStart == -1 || objStart > arrayEnd)
                break;
            int objEnd = JsonScan.findMatchingBracket(releaseJson, objStart, '{', '}');
            String obj = releaseJson.substring(objStart, objEnd + 1);

            if (assetName.equals(topLevelString(obj, "name"))) {
                return topLevelString(obj, "browser_download_url");
            }
            cursor = objEnd + 1;
        }
        return null;
    }

    /**
     * Reads the string value of {@code key} at depth 1 of the given JSON
     * object, ignoring occurrences inside string values and inside nested
     * objects/arrays. Returns {@code null} if the key is absent.
     */
    private static String topLevelString(String obj, String key) {
        String pattern = "\"" + key + "\"";
        boolean inString = false;
        int depth = 0;
        for (int i = 0; i < obj.length(); i++) {
            char c = obj.charAt(i);
            if (inString) {
                if (c == '\\')
                    i++; // skip escaped char
                else if (c == '"')
                    inString = false;
                continue;
            }
            switch (c) {
                case '{', '[' -> depth++;
                case '}', ']' -> depth--;
                case '"' -> {
                    if (depth == 1 && obj.startsWith(pattern, i)) {
                        return readStringValueAfter(obj, i + pattern.length());
                    }
                    inString = true;
                }
                default -> { }
            }
        }
        return null;
    }

    /**
     * Reads the quoted string value following {@code "key":} starting at
     * {@code from} (just past the key's closing quote). Returns {@code null}
     * if the value is not a string (e.g. a number or object).
     */
    private static String readStringValueAfter(String obj, int from) {
        int i = from;
        while (i < obj.length() && (Character.isWhitespace(obj.charAt(i)) || obj.charAt(i) == ':')) {
            i++;
        }
        if (i >= obj.length() || obj.charAt(i) != '"')
            return null;

        StringBuilder value = new StringBuilder();
        for (i++; i < obj.length(); i++) {
            char c = obj.charAt(i);
            if (c == '\\' && i + 1 < obj.length()) {
                value.append(obj.charAt(++i));
            } else if (c == '"') {
                return value.toString();
            } else {
                value.append(c);
            }
        }
        return null;
    }
}
