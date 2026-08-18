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

        /*
         * A literal "assets" (with unescaped quotes) cannot occur inside a
         * JSON string value, so the first hit is the real assets key.
        */
        int keyIdx = releaseJson.indexOf("\"assets\"");
        if (keyIdx == -1)
            return null;
        int arrayStart = releaseJson.indexOf('[', keyIdx);
        if (arrayStart == -1)
            return null;

        /*
         * Depth-counted and string-aware: the release body sits in the same
         * payload and markdown brackets there would otherwise close the array
         * early.
        */
        int arrayEnd = JsonScan.findMatchingBracket(releaseJson, arrayStart, '[', ']');

        /*
         * Walk the asset objects in order. Scanning the full payload with an
         * explicit arrayEnd bound (rather than a substring) keeps the reported
         * indices aligned with the original input.
        */
        int cursor = arrayStart + 1;
        while (cursor < arrayEnd) {
            int objStart = releaseJson.indexOf('{', cursor);

            /*
             * indexOf searches to the end of the payload, so an object *after*
             * the assets array must not be mistaken for another asset.
            */
            if (objStart == -1 || objStart > arrayEnd)
                break;
            int objEnd = JsonScan.findMatchingBracket(releaseJson, objStart, '{', '}');
            String obj = releaseJson.substring(objStart, objEnd + 1);

            /*
             * Depth-1 lookup, not a first-hit scan: every asset object embeds an
             * "uploader" object that carries a "name" of its own, which a naive
             * search would return instead of the file name.
            */
            if (assetName.equals(topLevelString(obj, "name"))) {
                return topLevelString(obj, "browser_download_url");
            }

            /*
             * Resume past the asset just examined, otherwise the indexOf above
             * returns the same '{' forever.
            */
            cursor = objEnd + 1;
        }
        return null;
    }

    /**
     * Returns the newest release from a {@code /releases} listing that is
     * actually published — the first entry whose top-level {@code draft} flag
     * is not set. Pre-releases deliberately count as published: this listing is
     * the experimental channel's source, and skipping them would leave it
     * identical to {@code /releases/latest}.
     *
     * <p>
     * GitHub returns the array newest-first, so the first hit is the newest.
     * Returns {@code null} when the payload holds no publishable release.
     */
    static String firstPublished(String releasesJson) {

        /*
         * The listing is a bare top-level array, so the first '[' is the array
         * itself - no key to locate first, unlike the assets scan above.
        */
        int arrayStart = releasesJson.indexOf('[');
        if (arrayStart == -1)
            return null;
        int arrayEnd = JsonScan.findMatchingBracket(releasesJson, arrayStart, '[', ']');

        int cursor = arrayStart + 1;
        while (cursor < arrayEnd) {
            int objStart = releasesJson.indexOf('{', cursor);
            if (objStart == -1 || objStart > arrayEnd)
                break;
            int objEnd = JsonScan.findMatchingBracket(releasesJson, objStart, '{', '}');
            String obj = releasesJson.substring(objStart, objEnd + 1);

            /*
             * "draft" is an unquoted JSON boolean, so it needs the literal
             * reader - topLevelString would return null for it. Anything that is
             * not exactly true (absent, unreadable, false) counts as published:
             * erring towards offering an update beats silently having none.
            */
            if (!"true".equals(topLevelLiteral(obj, "draft"))) {
                return obj;
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
        int from = topLevelValueStart(obj, key);
        return from < 0 ? null : readStringValueAfter(obj, from);
    }

    /**
     * Reads the <em>unquoted</em> value of {@code key} at depth 1 — booleans and
     * numbers, which {@link #topLevelString} rejects. Returns {@code null} if
     * the key is absent or its value is a string/object/array.
     */
    private static String topLevelLiteral(String obj, String key) {
        int i = topLevelValueStart(obj, key);
        if (i < 0)
            return null;

        /*
         * The returned index sits just past the key's closing quote, so the
         * colon and any padding still have to be stepped over.
        */
        while (i < obj.length() && (Character.isWhitespace(obj.charAt(i)) || obj.charAt(i) == ':')) {
            i++;
        }

        /*
         * A bare literal runs until the pair or container ends.
        */
        int start = i;
        while (i < obj.length() && ",}]".indexOf(obj.charAt(i)) < 0 && !Character.isWhitespace(obj.charAt(i))) {
            i++;
        }
        String literal = obj.substring(start, i);

        /*
         * A leading quote means the value is a string, and the caller wanted a
         * literal - refusing it keeps the string "true" from passing as boolean
         * true.
        */
        return literal.isEmpty() || literal.charAt(0) == '"' ? null : literal;
    }

    /**
     * Locates {@code key} at depth 1 of the given JSON object and returns the
     * index just past its closing quote (i.e. where the {@code :} and the value
     * follow), or {@code -1} if the key is absent. Occurrences inside string
     * values and inside nested objects/arrays are skipped.
     */
    private static int topLevelValueStart(String obj, String key) {
        String pattern = "\"" + key + "\"";
        boolean inString = false;
        int depth = 0;
        for (int i = 0; i < obj.length(); i++) {
            char c = obj.charAt(i);

            /*
             * Inside a value nothing is structural: braces, brackets and the key
             * pattern itself are just characters there. This is what makes a
             * release body full of markdown harmless.
            */
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

                    /*
                     * The object's own '{' pushed depth to 1, so depth 1 is where
                     * its keys live; anything deeper belongs to a nested object.
                     * Tested before entering string mode, because the key is
                     * itself a string and would otherwise be skipped as a value.
                    */
                    if (depth == 1 && obj.startsWith(pattern, i)) {
                        return i + pattern.length();
                    }
                    inString = true;
                }
                default -> { }
            }
        }
        return -1;
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

        /*
         * Not a quote means the value is a number, object or array; the caller
         * asked for a string, so report absence rather than guess.
        */
        if (i >= obj.length() || obj.charAt(i) != '"')
            return null;

        /*
         * Built up character by character rather than cut out with substring,
         * because the value has to arrive unescaped - a download URL carrying
         * \/ must come back as /. Only the backslash itself is consumed, which
         * is enough for the fields read here (asset names, URLs); escape
         * sequences that stand for another character - newline, tab, a unicode
         * codepoint - would need real decoding and never occur in them.
        */
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

        /*
         * Ran off the end without a closing quote: the payload is truncated.
        */
        return null;
    }
}
