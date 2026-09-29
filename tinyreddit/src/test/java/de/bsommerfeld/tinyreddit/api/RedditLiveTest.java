package de.bsommerfeld.tinyreddit.api;

import de.bsommerfeld.tinyfetch.api.BrowserEngine;
import de.bsommerfeld.tinyfetch.api.TinyFetch;
import de.bsommerfeld.tinyreddit.model.Comment;
import de.bsommerfeld.tinyreddit.model.Post;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real requests to Reddit through the real browser engine - opt-in, and run
 * sparingly: {@code mvn package -pl tinybrowser -am -DskipTests}, then
 * {@code mvn test -pl tinyreddit -Dtest=RedditLiveTest -Dtest.excludedGroups=visual}.
 * Chromium comes from {@code tinybrowser/target/chromium} (installed on first
 * use), the profile stays in {@code tinybrowser/target/reddit-profile}, so the
 * runs are one returning visitor.
 *
 * <p>The JSON route has to answer - it is the one the terminal lives on, and
 * the browser engine exists to keep it open. A refusal fails the test.
 */
@Tag("live")
class RedditLiveTest {

    @Test
    void newPostsAndTheCommentStream() throws Exception {
        Path target = Path.of(System.getProperty("tinybrowser.target", "../tinybrowser/target")).toAbsolutePath();
        assumeTrue(Files.isDirectory(target.resolve("engine")), "TinyBrowser not packaged - mvn package -pl tinybrowser -am");
        BrowserEngine engine = BrowserEngine.of(List.of(target.resolve("classes"), target.resolve("engine").resolve("*")),
                target.resolve("chromium"), target.resolve("reddit-profile"));

        TinyFetch.Builder http = TinyFetch.builder().engine(engine);
        RedditClient.hostPolicies().forEach(http::policy);

        try (TinyFetch fetch = http.build()) {
            RedditClient reddit = RedditClient.builder(fetch).routes(Route.JSON).build();

            Fetched<List<Post>> posts = reddit.newPosts("wallstreetbetsGER", 5);
            System.out.println("Reddit answered via " + posts.route() + ": " + posts.value().size() + " posts");
            posts.value().forEach(post -> System.out.println("  " + post.id() + " | " + post.title()));
            assertEquals(Route.JSON, posts.route());
            assertFalse(posts.value().isEmpty());

            Fetched<List<Comment>> comments = reddit.latestComments("wallstreetbets", 100);
            System.out.println("Comment stream via " + comments.route() + ": " + comments.value().size());
            comments.value().stream().limit(3).forEach(comment ->
                    System.out.println("  " + comment.postId() + " | " + comment.author() + " (" + comment.score() + ")"));
            assertEquals(Route.JSON, comments.route());
            assertFalse(comments.value().isEmpty());
        }
    }
}
