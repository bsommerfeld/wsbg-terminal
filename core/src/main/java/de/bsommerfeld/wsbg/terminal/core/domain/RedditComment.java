package de.bsommerfeld.wsbg.terminal.core.domain;

import java.util.Collections;
import java.util.List;

/**
 * Immutable snapshot of a Reddit comment at the time of scraping.
 * Carries comment metadata, content, and any embedded image URLs.
 * Timestamps are Unix epoch seconds (UTC).
 *
 * @param id             Reddit's internal fullname (e.g. {@code t1_xyz789})
 * @param threadId       parent thread fullname this comment belongs to
 * @param parentId       direct parent fullname (thread or another comment)
 * @param author         Reddit username of the comment author
 * @param body           raw comment text
 * @param score          net upvote count at time of scraping
 * @param createdUtc     creation timestamp in epoch seconds
 * @param fetchedAt      timestamp when this comment was fetched (epoch seconds)
 * @param lastUpdatedUtc last edit/update timestamp in epoch seconds
 * @param imageUrls      URLs of images embedded in or linked from the comment
 */
public record RedditComment(
        String id,
        String threadId,
        String parentId,
        String author,
        String body,
        int score,
        long createdUtc,
        long fetchedAt,
        long lastUpdatedUtc,
        List<String> imageUrls) {

    /**
     * Canonical constructor — ensures imageUrls is never null.
     */
    public RedditComment {
        imageUrls = imageUrls != null ? imageUrls : Collections.emptyList();
    }

    /**
     * Convenience constructor for comments without images.
     */
    public RedditComment(String id, String threadId, String parentId, String author,
            String body, int score, long createdUtc, long fetchedAt, long lastUpdatedUtc) {
        this(id, threadId, parentId, author, body, score, createdUtc, fetchedAt,
                lastUpdatedUtc, Collections.emptyList());
    }
}
