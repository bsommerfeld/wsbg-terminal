package de.bsommerfeld.updater.api;

import de.bsommerfeld.updater.download.Downloader;
import de.bsommerfeld.updater.json.JsonParser;

import java.io.IOException;

/**
 * Which releases an install is willing to receive.
 *
 * <p>
 * The channel is the <em>only</em> thing that differs between a cautious and an
 * adventurous install: both run the identical pipeline
 * ({@link TinyUpdateClient}) over whatever release object this enum hands them.
 * Everything downstream — asset lookup, manifest diff, extraction — never
 * learns which channel it is serving.
 *
 * <h3>Switching back is a downgrade, by design</h3>
 * The pipeline compares file hashes, not version numbers. Moving from
 * {@link #EXPERIMENTAL} back to {@link #STABLE} therefore re-syncs the install
 * onto the newest stable release even though that is <em>older</em> than what
 * is currently on disk — which is exactly what leaving the channel should mean.
 */
public enum ReleaseChannel {

    /**
     * Finished releases only. Reads GitHub's own "latest" endpoint, whose
     * definition already excludes drafts and pre-releases.
     */
    STABLE {
        @Override
        String fetchRelease(GitHubRepository repository) throws IOException {
            return Downloader.toString(repository.latestReleaseUrl());
        }
    },

    /**
     * Everything the moment it is published, pre-releases included. Reads the
     * release listing (newest first) and takes the first non-draft entry.
     */
    EXPERIMENTAL {
        @Override
        String fetchRelease(GitHubRepository repository) throws IOException {
            String listing = Downloader.toString(repository.releaseListUrl());
            String release = JsonParser.firstPublishedRelease(listing);
            if (release == null) {
                throw new IOException("No published release found in " + repository.releaseListUrl());
            }
            return release;
        }
    };

    /**
     * Fetches the release this channel considers current, as the same JSON
     * object shape a single-release endpoint returns.
     *
     * @throws IOException on network failure, or when the channel has no
     *                     publishable release to offer
     */
    abstract String fetchRelease(GitHubRepository repository) throws IOException;
}
