package de.bsommerfeld.updater.json;

import de.bsommerfeld.updater.model.UpdateManifest;

/**
 * Minimal, dependency-free JSON facade for the updater.
 *
 * <h3>Why not Jackson/Gson?</h3>
 * The updater module is bundled into the JPackage native launcher, which
 * produces a minimal runtime image. Adding a full JSON library would
 * increase the launcher size significantly and introduce transitive
 * dependencies (annotations, reflection modules). Since the shapes we read
 * are fixed and trivial, hand-parsing is both smaller and faster.
 *
 * <h3>Two schemas, two trust levels</h3>
 * The updater reads two distinct JSON shapes, split into focused readers:
 * <ul>
 *   <li>{@link ManifestJson} — the fixed, trusted TinyUpdate manifest
 *       ({@code version} + {@code files[]}), scanned with naive first-hit
 *       {@link JsonScan#extractString}.</li>
 *   <li>{@link ReleaseJson} — the untrusted GitHub release payload (carries a
 *       free-text {@code body}), scanned string/escape aware and scoped to the
 *       {@code assets} array.</li>
 * </ul>
 * Both share the low-level primitives in {@link JsonScan}. This class stays as
 * a stable delegating facade over the two.
 *
 * @see ManifestJson
 * @see ReleaseJson
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
        return ManifestJson.parse(json);
    }

    /**
     * Extracts a quoted string value for a given key from a JSON fragment.
     *
     * <p>
     * Naive first-hit scan — safe only for the fixed manifest / release-tag
     * shapes (no escaped quotes in values).
     *
     * @throws JsonParseException if the key is missing or the value is malformed
     */
    public static String extractString(String json, String key) {
        return JsonScan.extractString(json, key);
    }

    /**
     * Extracts the {@code browser_download_url} of a named asset from a
     * GitHub release JSON payload, or {@code null} if no such asset exists.
     *
     * <p>
     * Hardened against free-text {@code body} content and nested objects —
     * see {@link ReleaseJson}.
     */
    public static String extractAssetUrl(String releaseJson, String assetName) {
        return ReleaseJson.extractAssetUrl(releaseJson, assetName);
    }
}
