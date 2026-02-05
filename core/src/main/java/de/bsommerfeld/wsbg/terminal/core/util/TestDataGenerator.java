package de.bsommerfeld.wsbg.terminal.core.util;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Generates realistic dummy Reddit data for offline development and TEST mode.
 *
 * <p>
 * The generated data mirrors the structure and metrics of real WallStreetBets
 * content — including nested comment trees, score distributions, image
 * attachments,
 * and temporal spread — so the entire application stack (graph view, sidebar,
 * passive monitor, headline generation) behaves identically to production
 * without requiring Reddit API access.
 *
 * <h3>What the output looks like</h3>
 * <ul>
 * <li><strong>Threads</strong>: 5 subreddits, randomly composed three-part
 * titles
 * (ticker + sentiment + emoji qualifier), scores 0–5000, upvote ratios 60–99%,
 * comment counts 0–500, ~50% chance of a placeholder image</li>
 * <li><strong>Batch threads</strong>: timestamps spread across the last 24h to
 * simulate
 * pre-existing history; sorted descending by creation time</li>
 * <li><strong>Comments</strong>: flat list with parentId linking that encodes a
 * tree structure. ~40% root comments (parentId = threadId), remainder are
 * replies to random existing comments. Scores range from −50 to +450.
 * ~30% chance of 1–4 image attachments per comment</li>
 * </ul>
 *
 * <h3>Intended consumers</h3>
 * <ul>
 * <li>{@link de.bsommerfeld.wsbg.terminal.core.util.TestDataGenerator} itself
 * is
 * a pure data factory with no side effects</li>
 * <li>{@code TestDatabaseService} uses it to seed the in-memory database</li>
 * <li>{@code TestRedditScraper} uses it to simulate live scraping results</li>
 * </ul>
 */
public class TestDataGenerator {

    private static final Random RND = new Random();

    // --- Data Pools ---

    private static final String[] SUBREDDITS = { "wallstreetbets", "wallstreetbetsGER", "Finanzen", "Aktien",
            "CryptoMoonShots" };

    private static final String[] TITLES_PART_1 = { "GME", "AMC", "NVIDIA", "Tesla", "Bitcoin", "Rheinmetall",
            "MicroStrategy", "Palantir" };
    private static final String[] TITLES_PART_2 = { "to the moon!", "is crashing hard", "short squeeze imminent?",
            "DD inside", "YOLO update", "loss porn", "breaking out" };
    private static final String[] TITLES_PART_3 = { "🚀🚀🚀", "(Real Talk)", "[Discussion]", "???", "!!1!", "💎🙌" };

    private static final String[] AUTHORS = { "DeepFuckingValue", "AutoModerator", "Throwaway_123", "HedgeFundTears",
            "ApeStrong", "StonksOnlyGoUp", "DiamondHands88" };

    private static final String[] COMMENTS = {
            "This is the way.",
            "Buy the dip!",
            "I like the stock.",
            "Paper hands causing this drop.",
            "Short ladder attack confirmed.",
            "Can someone explain what calls are?",
            "Sir, this is a Wendy's.",
            "Holding until $1000.",
            "Just bought 100 more shares.",
            "F in the chat for the bears.",
            "To the moon! 🚀"
    };

    // --- Generator Methods ---

    /**
     * Generates a single thread with the current timestamp, random metrics,
     * and a ~50% chance of an attached placeholder image.
     *
     * @return a fully populated {@link RedditThread} with a unique {@code t3_}
     *         prefixed ID
     */
    public static RedditThread generateThread() {
        String id = "t3_" + UUID.randomUUID().toString().substring(0, 8);
        String sub = randomElement(SUBREDDITS);
        String title = String.format("%s %s %s", randomElement(TITLES_PART_1), randomElement(TITLES_PART_2),
                randomElement(TITLES_PART_3));
        String author = randomElement(AUTHORS);
        String text = "Lorem ipsum crypto stonks hodl based. " + title + ". Not financial advice.";

        long now = System.currentTimeMillis() / 1000;

        int score = RND.nextInt(5000);
        int comments = RND.nextInt(500);
        double ratio = 0.60 + (RND.nextDouble() * 0.39);

        String image = (RND.nextBoolean()) ? "https://placekitten.com/500/300" : null;

        return new RedditThread(id, sub, title, author, text, now, "/r/" + sub + "/comments/" + id, score, ratio,
                comments, now, image);
    }

    /**
     * Generates a batch of threads with timestamps spread linearly across the
     * last 24 hours. The first thread in the returned list is the newest.
     *
     * <p>
     * This temporal spread ensures the graph view, cleanup logic, and
     * investigation clustering see a realistic age distribution on startup
     * rather than all threads arriving at {@code now}.
     *
     * @param count number of threads to generate
     * @return threads sorted descending by {@code createdUtc}
     */
    public static List<RedditThread> generateThreads(int count) {
        long now = System.currentTimeMillis() / 1000;
        List<RedditThread> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            RedditThread t = generateThread();
            long spread = now - (long) ((i / (double) count) * 3600 * 24);
            list.add(new RedditThread(t.id(), t.subreddit(), t.title(), t.author(),
                    t.textContent(), spread, t.permalink(), t.score(), t.upvoteRatio(),
                    t.numComments(), spread, t.imageUrl()));
        }
        list.sort((a, b) -> Long.compare(b.createdUtc(), a.createdUtc()));
        return list;
    }

    /**
     * Generates a single comment for the given thread. Scores range from
     * −50 to +450 (negative scores are realistic for controversial takes).
     * ~50% of comments have an appended second sentence for varied body lengths.
     * ~30% carry 1–4 placeholder image URLs.
     *
     * @param threadId the owning thread's ID (used for hierarchy resolution)
     * @param parentId the parent entity — equals {@code threadId} for root
     *                 comments, or another comment's ID for replies
     * @return a fully populated {@link RedditComment} with a unique {@code t1_}
     *         prefixed ID
     */
    public static RedditComment generateComment(String threadId, String parentId) {
        String id = "t1_" + UUID.randomUUID().toString().substring(0, 8);
        String author = randomElement(AUTHORS);
        String body = randomElement(COMMENTS);

        if (RND.nextBoolean()) {
            body += " Also, " + randomElement(COMMENTS).toLowerCase();
        }

        long now = System.currentTimeMillis() / 1000;

        return new RedditComment(
                id,
                threadId,
                parentId,
                author,
                body,
                RND.nextInt(500) - 50,
                now - RND.nextInt(3600),
                now,
                now,
                generateRandomImageUrls());
    }

    private static List<String> generateRandomImageUrls() {
        if (RND.nextInt(10) < 3) {
            int count = 1 + RND.nextInt(4);
            List<String> urls = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int w = 200 + RND.nextInt(600);
                int h = 200 + RND.nextInt(400);
                urls.add("https://placekitten.com/" + w + "/" + h);
            }
            return urls;
        }
        return Collections.emptyList();
    }

    /**
     * Generates a flat comment list that encodes a realistic tree structure
     * via {@code parentId} linking.
     *
     * <p>
     * The algorithm works in two phases:
     * <ol>
     * <li><strong>Root comments</strong> (~40% of {@code count}): {@code parentId}
     * is set to {@code threadId}, marking them as top-level replies</li>
     * <li><strong>Nested replies</strong> (remaining ~60%): each picks a random
     * parent from an ever-growing pool. With 50% probability, the new reply
     * itself is added to the pool — this produces naturally varying nesting
     * depths (typically 3–6 levels) without artificial limits</li>
     * </ol>
     *
     * <p>
     * The resulting structure mirrors Reddit's actual comment trees where a few
     * root comments attract deep discussion chains while most remain shallow.
     *
     * @param threadId the owning thread's ID
     * @param count    total number of comments to generate (roots + replies)
     * @return flat list of comments; reconstruct the tree by grouping on
     *         {@code parentId}
     */
    public static List<RedditComment> generateCommentsRecursive(String threadId, int count) {
        List<RedditComment> allComments = new ArrayList<>();

        int rootCount = Math.max(1, (int) (count * 0.4));
        List<RedditComment> roots = new ArrayList<>();

        for (int i = 0; i < rootCount; i++) {
            RedditComment root = generateComment(threadId, threadId);
            roots.add(root);
            allComments.add(root);
        }

        int remaining = count - rootCount;
        List<RedditComment> potentialParents = new ArrayList<>(roots);

        while (remaining > 0 && !potentialParents.isEmpty()) {
            RedditComment parent = potentialParents.get(RND.nextInt(potentialParents.size()));
            RedditComment reply = generateComment(threadId, parent.id());
            allComments.add(reply);

            if (RND.nextBoolean()) {
                potentialParents.add(reply);
            }

            remaining--;
        }

        return allComments;
    }

    private static <T> T randomElement(T[] array) {
        return array[RND.nextInt(array.length)];
    }
}
