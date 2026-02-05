package de.bsommerfeld.wsbg.terminal.core.domain;

/**
 * Immutable snapshot of a Reddit thread at the time of scraping.
 * Carries all metadata required for clustering, significance evaluation,
 * and UI display. Timestamps are Unix epoch seconds (UTC).
 *
 * @param id              Reddit's internal fullname (e.g. {@code t3_abc123})
 * @param subreddit       subreddit name without {@code r/} prefix
 * @param title           thread title as posted by the author
 * @param author          Reddit username of the thread author
 * @param textContent     self-text body, {@code null} for link-only posts
 * @param createdUtc      creation timestamp in epoch seconds
 * @param permalink       relative permalink path (e.g.
 *                        {@code /r/wsb/comments/...})
 * @param score           net upvote count at time of scraping
 * @param upvoteRatio     upvote ratio as a 0.0–1.0 fraction
 * @param numComments     total comment count at time of scraping
 * @param lastActivityUtc most recent activity timestamp in epoch seconds
 * @param imageUrl        URL of the thread's primary image, {@code null} if
 *                        absent
 */
public record RedditThread(
        String id,
        String subreddit,
        String title,
        String author,
        String textContent,
        long createdUtc,
        String permalink,
        int score,
        double upvoteRatio,
        int numComments,
        long lastActivityUtc,
        String imageUrl) {

    /**
     * Convenience constructor for threads without image or explicit
     * last-activity timestamp. Sets {@code lastActivityUtc} to {@code createdUtc}.
     */
    public RedditThread(String id, String subreddit, String title, String author,
            String textContent, long createdUtc, String permalink, int score,
            double upvoteRatio, int numComments) {
        this(id, subreddit, title, author, textContent, createdUtc, permalink,
                score, upvoteRatio, numComments, createdUtc, null);
    }

    /**
     * Convenience constructor for threads without an image.
     */
    public RedditThread(String id, String subreddit, String title, String author,
            String textContent, long createdUtc, String permalink, int score,
            double upvoteRatio, int numComments, long lastActivityUtc) {
        this(id, subreddit, title, author, textContent, createdUtc, permalink,
                score, upvoteRatio, numComments, lastActivityUtc, null);
    }
}
