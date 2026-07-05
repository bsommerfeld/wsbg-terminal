package de.bsommerfeld.wsbg.terminal.reddit.support;

/**
 * Stateless text/URL utilities shared by both Reddit sources. Previously
 * duplicated (near-verbatim) across {@code RedditScraper} and
 * {@code RssRedditScraper}.
 */
public final class RedditText {

    private RedditText() {}

    /**
     * Checks whether a URL points to an image by looking for common image
     * file extensions anywhere in the URL. Uses {@code .contains()} rather
     * than {@code .endsWith()} because image CDNs often append query
     * parameters after the extension (e.g. {@code image.jpg?width=640}).
     */
    public static boolean isImageUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.contains(".jpg") || lower.contains(".jpeg")
                || lower.contains(".png") || lower.contains(".webp")
                || lower.contains(".gif");
    }

    /**
     * Reverses the HTML entity encoding that Reddit's JSON and Atom feeds
     * occasionally contain in URL and text fields. Without this, URLs with
     * query parameters (e.g. {@code &amp;} instead of {@code &}) would fail to
     * resolve. Superset of the entities both sources need.
     */
    public static String unescapeHtml(String s) {
        if (s == null) return null;
        return s.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&#32;", " ");
    }

    /**
     * Strips trailing punctuation characters that the greedy URL regex
     * ({@code \\S+}) often captures. Markdown-formatted links like
     * {@code [text](url)} leave a trailing {@code )} on the URL, and
     * sentence-ending punctuation like {@code .}, {@code ,}, {@code ;}
     * is also commonly appended.
     */
    public static String stripTrailingPunctuation(String url) {
        while (url.endsWith(")") || url.endsWith("]") || url.endsWith(".")
                || url.endsWith(",") || url.endsWith(";")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /**
     * Normalizes a Reddit permalink by stripping a trailing slash and
     * ensuring a leading slash. Reddit URLs may or may not include these
     * depending on the source (API vs. web).
     */
    public static String normalizePermalink(String permalink) {
        String p = permalink;
        if (p.endsWith("/")) p = p.substring(0, p.length() - 1);
        if (!p.startsWith("/")) p = "/" + p;
        return p;
    }

    /**
     * Returns {@code true} if the author name represents a real Reddit user.
     * Placeholder values like "anon", "unknown", and "[deleted]" are excluded —
     * they don't carry useful attribution information.
     */
    public static boolean isRealAuthor(String author) {
        return !author.equals("anon") && !author.equals("unknown") && !author.equals("[deleted]");
    }
}
