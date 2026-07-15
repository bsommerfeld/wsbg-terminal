package de.bsommerfeld.updater.api;

/**
 * Identifies a GitHub repository for release queries.
 *
 * <p>
 * Encapsulates the owner/repo pair and provides the API endpoint URL
 * for the latest release. Used by {@link TinyUpdateClient} to resolve
 * where to check for updates.
 *
 * @param owner   repository owner — GitHub user or organization name
 * @param repo    repository name
 * @param apiBase REST API base URL, {@code https://api.github.com} in
 *                production; overridable so update flows can be verified
 *                against a locally served release (no test mode in the
 *                client itself — same code path, different host)
 */
public record GitHubRepository(String owner, String repo, String apiBase) {

    private static final String GITHUB_API = "https://api.github.com";

    /** Production shorthand: the real GitHub API. */
    public GitHubRepository(String owner, String repo) {
        this(owner, repo, GITHUB_API);
    }

    /**
     * Parses {@code "owner/repo"} slug notation into a typed record.
     *
     * @throws IllegalArgumentException if the input doesn't contain exactly one
     *                                  slash
     *                                  or either segment is blank
     */
    public static GitHubRepository of(String slug) {
        String[] parts = slug.split("/");
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("Expected 'owner/repo', got: " + slug);
        }
        return new GitHubRepository(parts[0], parts[1]);
    }

    /**
     * Returns the REST API endpoint for the latest release.
     * The response includes the tag name, asset list with download URLs,
     * and release metadata.
     */
    public String latestReleaseUrl() {
        return apiBase + "/repos/" + owner + "/" + repo + "/releases/latest";
    }
}
