package de.bsommerfeld.wsbg.terminal.core.util;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Utility for generating realistic dummy data for Reddit threads and comments.
 * Used exclusively in TEST mode to populate UI and test features without real
 * API calls.
 */
public class TestDataGenerator {

    private static final Random RND = new Random();

    // --- Data Pools ---

    private static final String[] SUBREDDITS = { "wallstreetbets", "wallstreetbetsGER", "Finanzen", "Aktien",
            "CryptoMoonShots" };

    // Structure: [Stock] [Action/Sentiment] [Qualifier]
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

    public static RedditThread generateThread() {
        String id = "t3_" + UUID.randomUUID().toString().substring(0, 8);
        String sub = randomElement(SUBREDDITS);
        String title = String.format("%s %s %s", randomElement(TITLES_PART_1), randomElement(TITLES_PART_2),
                randomElement(TITLES_PART_3));
        String author = randomElement(AUTHORS);
        String text = "Lorem ipsum crypto stonks hodl based. " + title + ". Not financial advice.";

        long now = System.currentTimeMillis() / 1000;
        long created = now - RND.nextInt(3600 * 24); // Last 24h

        // Realistic metrics
        int score = RND.nextInt(5000);
        int comments = RND.nextInt(500);
        double ratio = 0.60 + (RND.nextDouble() * 0.39); // 60-99%

        // Random Image (Cat placeholder or null)
        String image = (RND.nextBoolean()) ? "https://placekitten.com/500/300" : null;

        return new RedditThread(id, sub, title, author, text, created, "/r/" + sub + "/comments/" + id, score, ratio,
                comments, created, image);
    }

    public static List<RedditThread> generateThreads(int count) {
        List<RedditThread> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(generateThread());
        }
        // Sort by created desc
        list.sort((a, b) -> Long.compare(b.getCreatedUtc(), a.getCreatedUtc()));
        return list;
    }

    public static RedditComment generateComment(String threadId, String parentId) {
        String id = "t1_" + UUID.randomUUID().toString().substring(0, 8);
        String author = randomElement(AUTHORS);
        String body = randomElement(COMMENTS);

        // Sometimes longer text
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
                RND.nextInt(500) - 50, // Score can be negative
                now - RND.nextInt(3600),
                now,
                now,
                Collections.emptyList());
    }

    /**
     * Generates a list of comments, including nested replies.
     * Note: The standard RedditComment flat structure implies parentId linking.
     * This method creates a flat list where some comments are replies to others.
     */
    public static List<RedditComment> generateCommentsRecursive(String threadId, int count) {
        List<RedditComment> allComments = new ArrayList<>();

        // 1. Generate Root Comments (approx 40% of count)
        int rootCount = Math.max(1, (int) (count * 0.4));
        List<RedditComment> roots = new ArrayList<>();

        for (int i = 0; i < rootCount; i++) {
            // Parent ID same as Thread ID (t3_...) means Top Level in some schemas,
            // or we use the specific convention. Here: parentId = threadId
            RedditComment root = generateComment(threadId, threadId);
            roots.add(root);
            allComments.add(root);
        }

        // 2. Distribute remaining count as replies
        int remaining = count - rootCount;

        // Use a simple probability distribution to attach replies to existing comments
        // We iterate and keep adding to the 'possible parents' pool (which includes new
        // replies)
        // to simulate deep nesting.
        List<RedditComment> potentialParents = new ArrayList<>(roots);

        while (remaining > 0 && !potentialParents.isEmpty()) {
            // Pick a random parent
            RedditComment parent = potentialParents.get(RND.nextInt(potentialParents.size()));

            // Create reply
            RedditComment reply = generateComment(threadId, parent.getId());

            // Add to list
            allComments.add(reply);

            // Add reply as a potential parent for deeper nesting (50% chance)
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
