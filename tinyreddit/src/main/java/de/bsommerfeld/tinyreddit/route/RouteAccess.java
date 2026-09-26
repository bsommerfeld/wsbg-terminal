package de.bsommerfeld.tinyreddit.route;

import de.bsommerfeld.tinyfetch.api.FetchException;
import de.bsommerfeld.tinyreddit.api.Route;
import de.bsommerfeld.tinyreddit.model.Comment;
import de.bsommerfeld.tinyreddit.model.Discussion;
import de.bsommerfeld.tinyreddit.model.Post;

import java.util.List;

/**
 * One way into Reddit. Every method is one request.
 *
 * <p>Failures: {@link FetchException} (nothing came back, or the host is
 * paused), {@link RouteRefused} (an answer that is not the data - a wall, an
 * error status), {@link MalformedAnswerException} (the data did not parse).
 */
public interface RouteAccess {

    Route route();

    /** {@code sort}: {@code new}, {@code hot}, {@code rising}, {@code top}. */
    List<Post> listing(String subreddit, String sort, int limit)
            throws FetchException, RouteRefused, MalformedAnswerException, InterruptedException;

    /** The newest comments of the whole subreddit, across all posts. */
    List<Comment> latestComments(String subreddit, int limit)
            throws FetchException, RouteRefused, MalformedAnswerException, InterruptedException;

    Discussion discussion(String permalink)
            throws FetchException, RouteRefused, MalformedAnswerException, InterruptedException;

    /** Whether {@link #posts} exists on this route. */
    boolean canLookUpPosts();

    /** Current state of up to 100 posts by fullname. */
    List<Post> posts(List<String> fullnames)
            throws FetchException, RouteRefused, MalformedAnswerException, InterruptedException;
}
