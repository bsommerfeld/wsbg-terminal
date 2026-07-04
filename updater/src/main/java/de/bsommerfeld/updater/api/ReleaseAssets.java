package de.bsommerfeld.updater.api;

import de.bsommerfeld.updater.json.JsonParser;

import java.io.IOException;

/**
 * Thin adapter over {@link JsonParser} that looks up named release assets in a
 * GitHub release JSON payload.
 *
 * <p>
 * Scopes the scan to the {@code assets} array (the release {@code body} — free
 * markdown text — can never produce a false match) and localizes the
 * "asset not found" failure.
 */
final class ReleaseAssets {

    private ReleaseAssets() {
    }

    /**
     * Returns the {@code browser_download_url} of a named release asset.
     *
     * @throws IOException if the asset is not found in the release
     */
    static String requireUrl(String releaseJson, String assetName) throws IOException {
        String url = JsonParser.extractAssetUrl(releaseJson, assetName);
        if (url == null) {
            throw new IOException("Asset not found in release: " + assetName);
        }
        return url;
    }

    /** Returns {@code true} if the release contains an asset with the given name. */
    static boolean has(String releaseJson, String assetName) {
        return JsonParser.extractAssetUrl(releaseJson, assetName) != null;
    }
}
