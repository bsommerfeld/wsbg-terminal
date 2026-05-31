package de.bsommerfeld.wsbg.terminal.core.domain;

import java.util.Collections;
import java.util.List;

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
 * @param imageUrls       ordered list of image URLs attached to the thread.
 *                        Single-image posts carry one entry; gallery posts
 *                        carry the gallery slides in display order. Never
 *                        {@code null} (empty list when the post has none).
 * @param pollData        Reddit poll snapshot when the thread is a poll
 *                        post, {@code null} otherwise. Polls are high-signal
 *                        sentiment data; the editorial agent reads them
 *                        directly out of the cluster report.
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
        List<String> imageUrls,
        PollData pollData) {

    public RedditThread {
        imageUrls = imageUrls != null ? List.copyOf(imageUrls) : Collections.emptyList();
    }

    /**
     * Convenience constructor for threads without images, poll, or explicit
     * last-activity timestamp. Sets {@code lastActivityUtc} to {@code createdUtc}.
     */
    public RedditThread(String id, String subreddit, String title, String author,
            String textContent, long createdUtc, String permalink, int score,
            double upvoteRatio, int numComments) {
        this(id, subreddit, title, author, textContent, createdUtc, permalink,
                score, upvoteRatio, numComments, createdUtc, Collections.emptyList(), null);
    }

    /**
     * Convenience constructor for threads without images or poll.
     */
    public RedditThread(String id, String subreddit, String title, String author,
            String textContent, long createdUtc, String permalink, int score,
            double upvoteRatio, int numComments, long lastActivityUtc) {
        this(id, subreddit, title, author, textContent, createdUtc, permalink,
                score, upvoteRatio, numComments, lastActivityUtc, Collections.emptyList(), null);
    }

    /**
     * Convenience constructor for threads with images but without a poll.
     */
    public RedditThread(String id, String subreddit, String title, String author,
            String textContent, long createdUtc, String permalink, int score,
            double upvoteRatio, int numComments, long lastActivityUtc, List<String> imageUrls) {
        this(id, subreddit, title, author, textContent, createdUtc, permalink,
                score, upvoteRatio, numComments, lastActivityUtc, imageUrls, null);
    }
}
