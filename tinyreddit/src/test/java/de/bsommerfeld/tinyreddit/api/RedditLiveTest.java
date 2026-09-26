package de.bsommerfeld.tinyreddit.api;

import de.bsommerfeld.tinyfetch.api.Browser;
import de.bsommerfeld.tinyfetch.api.CaptchaSolver;
import de.bsommerfeld.tinyfetch.api.ProcessUnlocker;
import de.bsommerfeld.tinyfetch.api.TinyFetch;
import de.bsommerfeld.tinyreddit.model.Comment;
import de.bsommerfeld.tinyreddit.model.Post;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Real requests to Reddit - opt-in, and run sparingly:
 * {@code mvn test -pl tinyreddit -Dtest=RedditLiveTest -Dtest.excludedGroups=visual}.
 *
 * <p>With the unlock engine built ({@code mvn package -pl tinyunlock}) and
 * {@code -Dtinyunlock.bundle=<chromium build> -Dtinyunlock.profile=<dir>}
 * (optionally {@code -Dtinyunlock.seed=<old profile>}), the visitor session is
 * opened through it first; {@code -Dtinyunlock.captchaWindow=true} lets the
 * person at the machine solve a CAPTCHA in a window. Without them the client
 * asks Reddit as it is.
 *
 * <p>Either outcome passes: Reddit answers and everything parses, or it
 * refuses and the client says so without trying a second route on the same
 * paused host.
 */
@Tag("live")
class RedditLiveTest {

    @BeforeAll
    static void requireLibrary() {
        assumeTrue(TinyFetch.libraryAvailable(), "libcurl-impersonate not installed - run .script/natives.sh");
    }

    @Test
    void newPostsAndTheCommentStream() throws Exception {
        TinyFetch.Builder http = TinyFetch.builder().browser(Browser.CHROMIUM_EMBEDDED);
        unlocker().ifPresent(http::unlocker);
        RedditClient.hostPolicies().forEach(http::policy);

        try (TinyFetch fetch = http.build()) {
            RedditClient reddit = RedditClient.builder(fetch).build();
            try {
                Fetched<List<Post>> posts = reddit.newPosts("wallstreetbetsGER", 5);
                System.out.println("Reddit answered via " + posts.route() + ": " + posts.value().size() + " posts");
                posts.value().forEach(post -> System.out.println("  " + post.id() + " | " + post.title()));
                assertFalse(posts.value().isEmpty());

                Fetched<List<Comment>> comments = reddit.latestComments("wallstreetbets", 100);
                System.out.println("Comment stream via " + comments.route() + ": " + comments.value().size());
                comments.value().stream().limit(3).forEach(comment ->
                        System.out.println("  " + comment.postId() + " | " + comment.author() + " (" + comment.score() + ")"));
                assertFalse(comments.value().isEmpty());
            } catch (RedditException refused) {
                System.out.println("Reddit refused: " + refused.attempts());
                String rss = refused.attempts().get(Route.RSS);
                assertTrue(rss.startsWith("host paused") || rss.startsWith("CAPTCHA unsolved"),
                        "RSS must not send a request after JSON was refused: " + refused.attempts());
            }
        }
    }

    private static Optional<ProcessUnlocker> unlocker() {
        String bundle = System.getProperty("tinyunlock.bundle");
        String profile = System.getProperty("tinyunlock.profile");
        if (bundle == null || profile == null) {
            return Optional.empty();
        }
        Path engine = Path.of(System.getProperty("tinyunlock.dir", "../tinyunlock/target")).toAbsolutePath();
        String seed = System.getProperty("tinyunlock.seed");
        List<String> command = ProcessUnlocker.tinyUnlockCommand(
                Path.of(ProcessHandle.current().info().command().orElse("java")),
                List.of(engine.resolve("classes"), engine.resolve("engine").resolve("*")),
                Path.of(bundle), Path.of(profile), seed == null ? null : Path.of(seed));
        CaptchaSolver solver = Boolean.getBoolean("tinyunlock.captchaWindow")
                ? challenge -> Optional.of(challenge.openWindow("Reddit - please confirm you are human"))
                : CaptchaSolver.NOBODY;
        return Optional.of(ProcessUnlocker.builder(command).captchaSolver(solver).build());
    }
}
