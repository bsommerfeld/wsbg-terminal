package de.bsommerfeld.tinyreddit.model;

import java.util.List;

/**
 * A comment. On RSS every comment sits directly under the post: Atom carries
 * no parent linkage and no score, so {@code parentId} is the post and
 * {@code score} and {@code depth} are zero.
 *
 * @param id         fullname, {@code t1_...}
 * @param postId     fullname of the post it belongs to, {@code t3_...}
 * @param parentId   what it answers: another comment ({@code t1_}) or the post ({@code t3_})
 * @param author     username without {@code u/}
 * @param body       markdown source as written
 * @param createdUtc epoch seconds
 * @param depth      0 for a top-level comment, +1 per reply level
 * @param imageUrls  images linked or embedded in the body
 */
public record Comment(
        String id,
        String postId,
        String parentId,
        String author,
        String body,
        int score,
        long createdUtc,
        int depth,
        List<String> imageUrls) {

    public Comment {
        imageUrls = List.copyOf(imageUrls);
    }
}
