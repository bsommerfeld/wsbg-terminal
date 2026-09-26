package de.bsommerfeld.tinyreddit.model;

import java.util.List;
import java.util.Optional;

/**
 * A submission. Values Reddit did not deliver on the route that fetched it
 * (see {@code Route}) read as zero - RSS carries no score, no ratio, no count.
 *
 * @param id           fullname, {@code t3_...}
 * @param author       username without {@code u/}; {@code [deleted]} when gone
 * @param body         the self text - for a crosspost the original's; empty for a link post
 * @param linkUrl      where a link post points; {@code null} for a self post
 * @param createdUtc   epoch seconds
 * @param permalink    path, {@code /r/<sub>/comments/<id>/<slug>/}
 * @param upvoteRatio  {@code 0..1}
 * @param imageUrls    full-size images in display order: gallery, inline, single image
 * @param poll         {@code null} unless the post is a poll
 */
public record Post(
        String id,
        String subreddit,
        String title,
        String author,
        String body,
        String linkUrl,
        long createdUtc,
        String permalink,
        int score,
        double upvoteRatio,
        int commentCount,
        List<String> imageUrls,
        Poll poll) {

    public Post {
        imageUrls = List.copyOf(imageUrls);
    }

    public Optional<Poll> pollIfAny() {
        return Optional.ofNullable(poll);
    }
}
