package de.bsommerfeld.tinyreddit.mapping;

import de.bsommerfeld.tinyreddit.json.JsonNode;
import de.bsommerfeld.tinyreddit.model.Comment;
import de.bsommerfeld.tinyreddit.model.Discussion;
import de.bsommerfeld.tinyreddit.model.Poll;
import de.bsommerfeld.tinyreddit.model.Post;

import java.util.ArrayList;
import java.util.List;

/**
 * Reddit's JSON (the {@code .json} pages) to the model. Requests ask for {@code raw_json=1}, so text arrives unescaped.
 */
public final class JsonMapper {

    private JsonMapper() {
    }

    /** The posts of a listing ({@code data.children[].data}); anything that is not a post is skipped. */
    public static List<Post> posts(JsonNode listing) {
        List<Post> posts = new ArrayList<>();
        for (JsonNode child : listing.path("data").path("children").elements()) {
            if ("t3".equals(child.path("kind").asText(""))) {
                posts.add(post(child.path("data")));
            }
        }
        return posts;
    }

    /**
     * The comments of a sub-wide comment listing ({@code /r/<sub>/comments}),
     * each carrying its real post and parent.
     */
    public static List<Comment> streamComments(JsonNode listing) {
        List<Comment> comments = new ArrayList<>();
        for (JsonNode child : listing.path("data").path("children").elements()) {
            JsonNode data = child.path("data");
            if (!"t1".equals(child.path("kind").asText("")) || !data.path("body").isPresent()) {
                continue;
            }
            String postId = data.path("link_id").asText("");
            if (postId.isEmpty()) {
                RedditText.CommentPath path = RedditText.commentPathOf(data.path("permalink").asText(""));
                if (path == null) {
                    continue;
                }
                postId = path.postId();
            }
            comments.add(comment(data, postId, 0));
        }
        return comments;
    }

    /**
     * A comments page: a two-element array, the post's listing and the
     * comment tree's.
     */
    public static Discussion discussion(JsonNode page) {
        Post post = post(page.at(0).path("data").path("children").at(0).path("data"));
        List<Comment> comments = new ArrayList<>();
        walk(page.at(1), post.id(), 0, comments);
        return new Discussion(post, comments);
    }

    public static Post post(JsonNode data) {
        String id = data.path("name").asText("t3_" + data.path("id").asText(""));

        String body = data.path("selftext").asText("");
        JsonNode original = MediaExtractor.crosspostOriginal(data);
        if (body.isEmpty() && original != null) {
            body = original.path("selftext").asText("");
        }

        String linkUrl = null;
        if (!data.path("is_self").asBoolean(false)) {
            linkUrl = RedditText.unescapeHtml(
                    data.path("url_overridden_by_dest").asText(data.path("url").asText(null)));
        }

        return new Post(
                id,
                data.path("subreddit").asText(""),
                data.path("title").asText(""),
                data.path("author").asText("[deleted]"),
                body,
                linkUrl,
                data.path("created_utc").asLong(0),
                data.path("permalink").asText(""),
                data.path("score").asInt(0),
                data.path("upvote_ratio").asDouble(0),
                data.path("num_comments").asInt(0),
                MediaExtractor.postImages(data),
                poll(data.path("poll_data")));
    }

    /**
     * Depth first through a comment listing. {@code more} stubs and removed
     * comments (no body) are skipped; their replies still count when present.
     */
    private static void walk(JsonNode listing, String postId, int depth, List<Comment> out) {
        for (JsonNode child : listing.path("data").path("children").elements()) {
            if (!"t1".equals(child.path("kind").asText(""))) {
                continue;
            }
            JsonNode data = child.path("data");
            if (data.path("body").isPresent()) {
                out.add(comment(data, postId, depth));
            }
            JsonNode replies = data.path("replies");
            if (replies.isObject()) {
                walk(replies, postId, depth + 1, out);
            }
        }
    }

    private static Comment comment(JsonNode data, String postId, int depth) {
        String body = data.path("body").asText("");
        return new Comment(
                data.path("name").asText("t1_" + data.path("id").asText("")),
                postId,
                data.path("parent_id").asText(postId),
                data.path("author").asText("[deleted]"),
                body,
                data.path("score").asInt(0),
                data.path("created_utc").asLong(0),
                depth,
                MediaExtractor.commentImages(body, data));
    }

    /**
     * {@code poll_data}: options with vote counts, total, and an end time Reddit
     * sends in milliseconds.
     */
    static Poll poll(JsonNode poll) {
        if (!poll.isObject()) {
            return null;
        }
        List<Poll.Option> options = new ArrayList<>();
        for (JsonNode option : poll.path("options").elements()) {
            String text = option.path("text").asText("").trim();
            if (!text.isEmpty()) {
                options.add(new Poll.Option(option.path("id").asText(""), text, option.path("vote_count").asInt(0)));
            }
        }
        if (options.isEmpty()) {
            return null;
        }
        long endsMillis = poll.path("voting_end_timestamp").asLong(0);
        return new Poll(options, poll.path("total_vote_count").asInt(0), endsMillis / 1000);
    }
}
