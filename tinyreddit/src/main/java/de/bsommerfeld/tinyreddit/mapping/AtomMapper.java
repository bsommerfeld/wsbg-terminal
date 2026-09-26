package de.bsommerfeld.tinyreddit.mapping;

import de.bsommerfeld.tinyreddit.model.Comment;
import de.bsommerfeld.tinyreddit.model.Discussion;
import de.bsommerfeld.tinyreddit.model.Post;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Atom entries to the model. What Atom does not carry stays zero: score,
 * ratio, comment count, poll; comments hang flat under their post.
 */
public final class AtomMapper {

    private static final Pattern ATTRIBUTE_URL = Pattern.compile("(?:src|href)=\"([^\"]+)\"");
    private static final Pattern HTML_TAG = Pattern.compile("<!--.*?-->|<[^>]+>", Pattern.DOTALL);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /** Reddit's feed footer, {@code submitted by /u/x [link] [comments]}. */
    private static final String FOOTER = "submitted by";

    private AtomMapper() {
    }

    /** The posts of a listing feed. */
    public static List<Post> posts(List<AtomFeed.Entry> entries, String subreddit) {
        List<Post> posts = new ArrayList<>();
        for (AtomFeed.Entry entry : entries) {
            if (entry.id() != null && entry.id().startsWith("t3_")) {
                posts.add(post(entry, subreddit));
            }
        }
        return posts;
    }

    /** The comments of a sub-wide comment feed; each finds its post from its own link. */
    public static List<Comment> streamComments(List<AtomFeed.Entry> entries) {
        List<Comment> comments = new ArrayList<>();
        for (AtomFeed.Entry entry : entries) {
            if (entry.id() == null || !entry.id().startsWith("t1_")) {
                continue;
            }
            RedditText.CommentPath path = RedditText.commentPathOf(entry.link());
            if (path != null) {
                comments.add(comment(entry, path.postId()));
            }
        }
        return comments;
    }

    /**
     * A post's comment feed: the post is its first {@code t3_} entry, the
     * {@code t1_} entries are its comments (Reddit caps them at about 100).
     *
     * @return {@code null} when the feed holds no post
     */
    public static Discussion discussion(List<AtomFeed.Entry> entries, String subreddit) {
        Post post = null;
        for (AtomFeed.Entry entry : entries) {
            if (entry.id() != null && entry.id().startsWith("t3_")) {
                post = post(entry, subreddit);
                break;
            }
        }
        if (post == null) {
            return null;
        }
        List<Comment> comments = new ArrayList<>();
        for (AtomFeed.Entry entry : entries) {
            if (entry.id() != null && entry.id().startsWith("t1_")) {
                comments.add(comment(entry, post.id()));
            }
        }
        return new Discussion(post, comments);
    }

    static Post post(AtomFeed.Entry entry, String subreddit) {
        return new Post(
                entry.id(),
                subreddit,
                entry.title() == null ? "" : entry.title(),
                author(entry.author()),
                text(entry.content()),
                null,
                epochSeconds(entry.published() != null ? entry.published() : entry.updated()),
                entry.link() == null ? "" : RedditText.normalizePermalink(entry.link()) + "/",
                0,
                0,
                0,
                images(entry.content(), entry.thumbnail()),
                null);
    }

    static Comment comment(AtomFeed.Entry entry, String postId) {
        return new Comment(
                entry.id(),
                postId,
                postId,
                author(entry.author()),
                text(entry.content()),
                0,
                epochSeconds(entry.published() != null ? entry.published() : entry.updated()),
                0,
                images(entry.content(), entry.thumbnail()));
    }

    /**
     * An entry's HTML reduced to text: footer dropped, tags stripped,
     * entities undone, whitespace collapsed. Empty for image-only posts.
     */
    static String text(String html) {
        if (html == null || html.isBlank()) {
            return "";
        }
        String text = html;
        int footer = text.indexOf(FOOTER);
        if (footer >= 0) {
            text = text.substring(0, footer);
        }
        text = HTML_TAG.matcher(text).replaceAll(" ");
        text = RedditText.unescapeHtml(text);
        return WHITESPACE.matcher(text).replaceAll(" ").trim();
    }

    /**
     * Image URLs from the entry's HTML and thumbnail. When a full-size
     * {@code i.redd.it} image is present its preview variants are dropped, so
     * one picture is not listed twice.
     */
    static List<String> images(String html, String thumbnail) {
        Set<String> all = new LinkedHashSet<>();
        if (html != null) {
            Matcher matcher = ATTRIBUTE_URL.matcher(html);
            while (matcher.find()) {
                String url = RedditText.unescapeHtml(matcher.group(1));
                if (RedditText.isImageUrl(url)) {
                    all.add(url);
                }
            }
        }
        if (thumbnail != null && RedditText.isImageUrl(RedditText.unescapeHtml(thumbnail))) {
            all.add(RedditText.unescapeHtml(thumbnail));
        }
        List<String> fullSize = all.stream().filter(url -> url.contains("i.redd.it")).toList();
        List<String> chosen = fullSize.isEmpty() ? List.copyOf(all) : fullSize;
        return chosen.size() > MediaExtractor.MAX_IMAGES ? chosen.subList(0, MediaExtractor.MAX_IMAGES) : chosen;
    }

    /** {@code /u/name} to {@code name}. */
    static String author(String name) {
        if (name == null || name.isBlank()) {
            return "[deleted]";
        }
        String trimmed = name.trim();
        if (trimmed.startsWith("/u/")) {
            return trimmed.substring(3);
        }
        return trimmed.startsWith("u/") ? trimmed.substring(2) : trimmed;
    }

    static long epochSeconds(String iso) {
        if (iso == null || iso.isBlank()) {
            return 0;
        }
        try {
            return OffsetDateTime.parse(iso).toEpochSecond();
        } catch (DateTimeParseException e) {
            return 0;
        }
    }
}
