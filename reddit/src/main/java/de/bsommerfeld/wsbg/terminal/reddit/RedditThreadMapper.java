package de.bsommerfeld.wsbg.terminal.reddit;

import com.fasterxml.jackson.databind.JsonNode;
import de.bsommerfeld.wsbg.terminal.core.domain.PollData;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import de.bsommerfeld.wsbg.terminal.reddit.support.RedditText;

import java.util.List;

/**
 * Maps Reddit JSON {@code data} nodes to {@link RedditThread} domain objects and
 * fills a {@link ThreadAnalysisContext}'s thread half. Owns the selftext
 * fallback ladder (crosspost parent → linked URL) and the "last activity"
 * resolution against the repository.
 */
public final class RedditThreadMapper {

    private final RedditRepository repository;
    private final RedditMediaExtractor media;

    public RedditThreadMapper(RedditRepository repository, RedditMediaExtractor media) {
        this.repository = repository;
        this.media = media;
    }

    /**
     * Maps a raw JSON thread data node to a {@link RedditThread} domain object.
     *
     * <p>For link posts (no selftext), the linked URL is used as the body
     * prefixed with {@code [Link: ...]} so the AI agent still has something to
     * analyze. The author is prefixed with {@code u/} unless it's a placeholder.
     */
    public RedditThread parseThread(JsonNode data, String id, String subredditDefault) {
        String subreddit = data.path("subreddit").asText(subredditDefault);
        String title = data.path("title").asText("No Title");
        String rawAuthor = data.path("author").asText("unknown");
        String author = RedditText.isRealAuthor(rawAuthor) ? "u/" + rawAuthor : rawAuthor;

        String selftext = data.path("selftext").asText("");
        if (selftext.isEmpty()) {
            // Crossposts render the original post's body; pull it from the
            // parent so the agent analyses the actual content, not a bare link.
            JsonNode crosspostParent = media.firstCrosspostParent(data);
            if (crosspostParent != null) {
                selftext = crosspostParent.path("selftext").asText("");
            }
        }
        if (selftext.isEmpty()) {
            if (data.has("url_overridden_by_dest")) {
                selftext = "[Link: " + data.get("url_overridden_by_dest").asText() + "]";
            } else if (data.has("url")) {
                selftext = "[Link: " + data.get("url").asText() + "]";
            }
        }

        long created = data.path("created_utc").asLong(0);
        String permalink = data.path("permalink").asText("");
        int score = data.path("score").asInt(0);
        double upvoteRatio = data.path("upvote_ratio").asDouble(0.0);
        int numComments = data.path("num_comments").asInt(0);

        List<String> imageUrls = media.resolveImageUrls(data);

        long resolvedActivity = resolveLastActivity(id, created, numComments);
        PollData pollData = PollParser.parse(data);

        return new RedditThread(id, subreddit, title, author, selftext, created,
                permalink, score, upvoteRatio, numComments, resolvedActivity,
                imageUrls, pollData);
    }

    /**
     * Extracts thread metadata from the first element of the Reddit JSON
     * response array into the given context. Reddit wraps the thread in a
     * listing with a single child, so the path is
     * {@code [0].data.children[0].data}.
     *
     * <p>For link posts that have no selftext, the linked URL is used as a
     * fallback body to ensure the context is never completely empty.
     */
    public void parseThreadData(JsonNode listing, ThreadAnalysisContext context) {
        JsonNode data = listing.get("data").get("children").get(0).get("data");

        // "name" is the fullname (t3_abc123), "id" is the bare ID (abc123)
        context.threadId = data.has("name")
                ? data.get("name").asText()
                : "t3_" + data.path("id").asText();

        context.title = data.path("title").asText(null);
        context.selftext = data.path("selftext").asText(null);

        if (context.selftext == null || context.selftext.isEmpty()) {
            JsonNode crosspostParent = media.firstCrosspostParent(data);
            if (crosspostParent != null) {
                String parentText = crosspostParent.path("selftext").asText("");
                if (!parentText.isEmpty()) context.selftext = parentText;
            }
        }
        if ((context.selftext == null || context.selftext.isEmpty())
                && data.has("url_overridden_by_dest")) {
            context.selftext = "[Link: " + data.get("url_overridden_by_dest").asText() + "]";
        }

        List<String> images = media.resolveImageUrls(data);
        if (!images.isEmpty()) {
            context.imageUrl = images.get(0);
        }
    }

    /**
     * Resolves the "last activity" timestamp for a thread. New threads use their
     * creation time; existing threads whose comment count grew are bumped to
     * "now" (drives the recent-activity sort), otherwise the cached value is
     * preserved.
     */
    private long resolveLastActivity(String threadId, long createdUtc, int numComments) {
        RedditThread existing = repository.getThread(threadId);
        if (existing == null)
            return createdUtc;

        if (numComments > existing.numComments()) {
            return System.currentTimeMillis() / 1000;
        }
        return existing.lastActivityUtc();
    }
}
