package de.bsommerfeld.updater.api;

/**
 * Identifies a GitHub repository for release queries.
 *
 * @param owner repository owner (user or org)
 * @param repo  repository name
 */
public record GitHubRepository(String owner, String repo) {

    /**
     * Parses "owner/repo" notation.
     *
     * @throws IllegalArgumentException if the format is invalid
     */
    public static GitHubRepository of(String slug) {
        String[] parts = slug.split("/");
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw new IllegalArgumentException("Expected 'owner/repo', got: " + slug);
        }
        return new GitHubRepository(parts[0], parts[1]);
    }

    /**
     * Returns the API endpoint for the latest release.
     */
    public String latestReleaseUrl() {
        return "https://api.github.com/repos/" + owner + "/" + repo + "/releases/latest";
    }
}
