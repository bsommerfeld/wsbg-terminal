package de.bsommerfeld.tinyreddit.mapping;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small text and URL rules both routes share. */
public final class RedditText {

    /** {@code /r/<sub>/comments/<post36>/<slug>/<comment36>/} - slug and comment id optional. */
    private static final Pattern COMMENT_PATH =
            Pattern.compile("^/r/([^/]+)/comments/([0-9a-z]+)(?:/([^/]*))?(?:/([0-9a-z]+))?/?");

    private RedditText() {
    }

    /**
     * Whether a URL points at an image. {@code contains}, not {@code endsWith}:
     * image CDNs append query strings ({@code a.jpg?width=640}).
     */
    public static boolean isImageUrl(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        return lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png")
                || lower.contains(".webp") || lower.contains(".gif");
    }

    /**
     * Undoes the entity escaping Reddit leaves in URLs and Atom text, so
     * {@code &amp;} in a query string does not break the link.
     */
    public static String unescapeHtml(String text) {
        if (text == null) {
            return null;
        }
        return text.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&#32;", " ");
    }

    /** Drops what a greedy URL match picks up from the sentence around it: {@code ) ] . , ;}. */
    public static String stripTrailingPunctuation(String url) {
        String trimmed = url;
        while (!trimmed.isEmpty() && ")].,;".indexOf(trimmed.charAt(trimmed.length() - 1)) >= 0) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /** The Reddit path of a link or path: scheme and host gone, one leading slash, no trailing one. */
    public static String normalizePermalink(String link) {
        String path = link;
        int index = path.indexOf("/r/");
        if (index > 0) {
            path = path.substring(index);
        }
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path.startsWith("/") ? path : "/" + path;
    }

    /** Where a comment belongs, read off its own permalink. */
    public record CommentPath(String postId, String subreddit, String postPermalink) {
    }

    /**
     * Recovers post fullname, subreddit and post permalink from a comment's
     * link - a sub-wide comment stream carries nothing else to hang it on.
     *
     * @return {@code null} when the link has no comment path
     */
    public static CommentPath commentPathOf(String link) {
        if (link == null || link.isBlank()) {
            return null;
        }
        int index = link.indexOf("/r/");
        if (index < 0) {
            return null;
        }
        Matcher matcher = COMMENT_PATH.matcher(link.substring(index));
        if (!matcher.find()) {
            return null;
        }
        String subreddit = matcher.group(1);
        String post = matcher.group(2);
        String slug = matcher.group(3) == null ? "" : matcher.group(3);
        return new CommentPath("t3_" + post, subreddit, "/r/" + subreddit + "/comments/" + post + "/" + slug + "/");
    }
}
