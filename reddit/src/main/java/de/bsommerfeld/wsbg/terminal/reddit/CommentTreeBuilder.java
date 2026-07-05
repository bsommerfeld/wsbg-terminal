package de.bsommerfeld.wsbg.terminal.reddit;

import com.fasterxml.jackson.databind.JsonNode;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import de.bsommerfeld.wsbg.terminal.reddit.support.RedditText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * Recursively walks a Reddit JSON comment tree, extracting text and images from
 * each comment, persisting every comment to the repository, and collecting
 * formatted lines into a {@link ThreadAnalysisContext} for the AI agent.
 */
public final class CommentTreeBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(CommentTreeBuilder.class);

    /**
     * Maximum recursive depth when traversing the comment tree. Anything beyond
     * 8 is noise for analysis purposes and not worth the parse time.
     */
    private static final int MAX_COMMENT_DEPTH = 8;

    private final RedditRepository repository;
    private final RedditMediaExtractor media;

    public CommentTreeBuilder(RedditRepository repository, RedditMediaExtractor media) {
        this.repository = repository;
        this.media = media;
    }

    /**
     * Recursively traverses the comment tree, extracting text, images, and
     * metadata from each comment node. Each comment is persisted immediately;
     * formatted versions (indented, author-prefixed) are collected in the
     * context for the agent's text analysis. Comments beyond
     * {@value #MAX_COMMENT_DEPTH} are discarded.
     */
    public void processComments(JsonNode node, ThreadAnalysisContext context, int depth) {
        if (depth > MAX_COMMENT_DEPTH)
            return;
        if (!node.has("data") || !node.get("data").has("children"))
            return;

        for (JsonNode child : node.get("data").get("children")) {
            JsonNode data = child.get("data");
            // Reddit returns no "body" field for non-comment children (e.g. "more"
            // objects), and null body for deleted/removed comments — skip both
            if (!data.has("body") || data.get("body").isNull())
                continue;

            String indent = "  ".repeat(depth);
            String rawBody = data.get("body").asText().replace("\n", " ");

            RedditMediaExtractor.ImageExtractionResult extraction =
                    media.extractCommentImages(rawBody, data, context);
            String body = extraction.text();

            String rawAuthor = data.path("author").asText("anon");
            int score = data.path("score").asInt(0);
            String displayAuthor = RedditText.isRealAuthor(rawAuthor) ? "`u/" + rawAuthor + "`" : rawAuthor;

            context.comments.add(indent + displayAuthor + " (Score: " + score + "): " + body);

            saveCommentToRepository(data, context, body, rawAuthor, score, extraction.images());

            if (data.has("replies") && data.get("replies").isObject()) {
                processComments(data.get("replies"), context, depth + 1);
            }
        }
    }

    /**
     * Maps a single JSON comment node to a {@link RedditComment} and persists it.
     * The parent–child relationship is preserved via {@code parent_id}.
     */
    private void saveCommentToRepository(JsonNode data, ThreadAnalysisContext context,
            String body, String author, int score,
            List<String> images) {
        String id = data.has("name")
                ? data.get("name").asText()
                : "unknown_" + UUID.randomUUID();
        String parentId = data.path("parent_id").asText(context.threadId);
        long createdUtc = data.path("created_utc").asLong(System.currentTimeMillis() / 1000);
        long now = System.currentTimeMillis() / 1000;

        RedditComment comment = new RedditComment(
                id,
                context.threadId != null ? context.threadId : "unknown",
                parentId, author, body, score, createdUtc,
                now, now, images);

        // INFO-level dump of every parsed comment so the operator can follow the
        // conversation feeding the cluster reports. Body is truncated to keep the
        // log scannable; full body lands in the brief the editorial agent reads.
        if (body != null && !body.isBlank()) {
            String snippet = body.length() > 140
                    ? body.substring(0, 140).replace('\n', ' ') + "…"
                    : body.replace('\n', ' ');
            LOG.info("[REDDIT]   comment on {} | {} (score={}): {}",
                    context.threadId, author, score, snippet);
        }
        repository.saveComment(comment);
    }
}
