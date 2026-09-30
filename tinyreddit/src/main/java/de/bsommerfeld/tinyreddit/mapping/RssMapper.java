package de.bsommerfeld.tinyreddit.mapping;

import de.bsommerfeld.tinyreddit.model.Comment;
import de.bsommerfeld.tinyreddit.model.Discussion;
import de.bsommerfeld.tinyreddit.model.Post;
import de.bsommerfeld.tinyrss.model.Entry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reddit's Atom entries, as TinyRss reads them, to the model. What Atom does
 * not carry stays zero: score, ratio, comment count, poll; comments hang flat
 * under their post.
 */
public final class RssMapper {

    private static final Pattern ATTRIBUTE_URL = Pattern.compile("(?:src|href)=\"([^\"]+)\"");

    /** Reddit's feed footer, {@code submitted by /u/x [link] [comments]}. */
    private static final String FOOTER = "submitted by";

    private RssMapper() {
    }

    /** The posts of a listing feed. */
    public static List<Post> posts(List<Entry> entries, String subreddit) {
        List<Post> posts = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.id().startsWith("t3_")) {
                posts.add(post(entry, subreddit));
            }
        }
        return posts;
    }

    /** The comments of a sub-wide comment feed; each finds its post from its own link. */
    public static List<Comment> streamComments(List<Entry> entries) {
        List<Comment> comments = new ArrayList<>();
        for (Entry entry : entries) {
            if (!entry.id().startsWith("t1_")) {
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
    public static Discussion discussion(List<Entry> entries, String subreddit) {
        Post post = null;
        for (Entry entry : entries) {
            if (entry.id().startsWith("t3_")) {
                post = post(entry, subreddit);
                break;
            }
        }
        if (post == null) {
            return null;
        }
        List<Comment> comments = new ArrayList<>();
        for (Entry entry : entries) {
            if (entry.id().startsWith("t1_")) {
                comments.add(comment(entry, post.id()));
            }
        }
        return new Discussion(post, comments);
    }

    static Post post(Entry entry, String subreddit) {
        return new Post(
                entry.id(),
                subreddit,
                entry.title(),
                author(entry.authors()),
                text(entry),
                null,
                entry.time().map(Instant::getEpochSecond).orElse(0L),
                entry.link() == null ? "" : RedditText.normalizePermalink(entry.link()) + "/",
                0,
                0,
                0,
                images(entry),
                null);
    }

    static Comment comment(Entry entry, String postId) {
        return new Comment(
                entry.id(),
                postId,
                postId,
                author(entry.authors()),
                text(entry),
                0,
                entry.time().map(Instant::getEpochSecond).orElse(0L),
                0,
                images(entry));
    }

    /**
     * The entry's text without Reddit's footer. The footer ends every entry,
     * so it is cut at its last mention - a post that says "submitted by"
     * itself keeps its words. Empty for image-only posts.
     */
    static String text(Entry entry) {
        String text = entry.contentText();
        int footer = text.lastIndexOf(FOOTER);
        return footer < 0 ? text : text.substring(0, footer).trim();
    }

    /**
     * Image URLs from the entry's HTML - Reddit links the full-size picture
     * rather than embedding it - and the ones TinyRss found. When a full-size
     * {@code i.redd.it} image is present its preview variants are dropped, so
     * one picture is not listed twice.
     */
    static List<String> images(Entry entry) {
        Set<String> all = new LinkedHashSet<>();
        Matcher matcher = ATTRIBUTE_URL.matcher(entry.contentHtml());
        while (matcher.find()) {
            String url = RedditText.unescapeHtml(matcher.group(1));
            if (RedditText.isImageUrl(url)) {
                all.add(url);
            }
        }
        for (String url : entry.images()) {
            if (RedditText.isImageUrl(url)) {
                all.add(url);
            }
        }
        List<String> fullSize = all.stream().filter(url -> url.contains("i.redd.it")).toList();
        List<String> chosen = fullSize.isEmpty() ? List.copyOf(all) : fullSize;
        return chosen.size() > MediaExtractor.MAX_IMAGES ? chosen.subList(0, MediaExtractor.MAX_IMAGES) : chosen;
    }

    /** {@code /u/name} to {@code name}. */
    static String author(List<String> authors) {
        if (authors.isEmpty() || authors.getFirst().isBlank()) {
            return "[deleted]";
        }
        String name = authors.getFirst().trim();
        if (name.startsWith("/u/")) {
            return name.substring(3);
        }
        return name.startsWith("u/") ? name.substring(2) : name;
    }
}
