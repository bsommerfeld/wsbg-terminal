package de.bsommerfeld.wsbg.terminal.reddit;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.reddit.support.RedditText;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Maps parsed Atom {@link AtomFeedParser.Entry entries} to
 * {@link RedditThread}/{@link RedditComment} domain objects, plus the text and
 * image extraction the RSS path needs.
 *
 * <p>Atom carries no scores, comment counts, upvote ratios, poll data, or
 * comment-tree structure. So {@code score}/{@code numComments}/
 * {@code upvoteRatio} are zero, {@code pollData} is {@code null}, and every
 * comment's {@code parentId} points at its thread (a hard Atom limitation, not a
 * bug — replies arrive flat with no parent linkage).
 */
public final class RssEntryMapper {

    private RssEntryMapper() {}

    private static final int MAX_IMAGES = 10;

    /** Matches the URL in any {@code src="…"} or {@code href="…"} attribute. */
    private static final Pattern ATTR_URL = Pattern.compile("(?:src|href)=\"([^\"]+)\"");
    /** Matches HTML tags (incl. comments) for stripping content down to text. */
    private static final Pattern HTML_TAG = Pattern.compile("<!--.*?-->|<[^>]+>", Pattern.DOTALL);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    public static RedditThread toThread(AtomFeedParser.Entry e, String subredditDefault,
            RedditThread existing) {
        long created = parseEpoch(e.published != null ? e.published : e.updated);
        long lastActivity = existing != null ? existing.lastActivityUtc() : created;
        // RSS carries no score / comment-count / upvote-ratio / poll data.
        return new RedditThread(
                e.id,
                subredditDefault,
                e.title != null ? e.title : "No Title",
                normalizeAuthor(e.authorName),
                extractBodyText(e.content),
                created,
                permalinkOf(e.link),
                0,
                0.0,
                0,
                lastActivity,
                extractImages(e.content, e.thumbnail),
                null);
    }

    public static RedditComment toComment(AtomFeedParser.Entry e, String threadId) {
        long created = parseEpoch(e.published != null ? e.published : e.updated);
        long now = System.currentTimeMillis() / 1000;
        return new RedditComment(
                e.id,
                threadId,
                threadId, // no parent linkage in Atom — flat under the thread
                stripUserPrefix(e.authorName),
                extractBodyText(e.content),
                0,
                created,
                now,
                now,
                extractImages(e.content, e.thumbnail));
    }

    public static RedditThread withActivity(RedditThread t, long lastActivityUtc) {
        return new RedditThread(t.id(), t.subreddit(), t.title(), t.author(), t.textContent(),
                t.createdUtc(), t.permalink(), t.score(), t.upvoteRatio(), t.numComments(),
                lastActivityUtc, t.imageUrls(), t.pollData());
    }

    /**
     * Stamps the real comment count (Atom carries none, so threads start at 0)
     * and bumps activity to now. The non-zero count is what lets the upstream
     * {@code computeDeltas} register a positive delta and re-fire the editorial
     * pass once the async worker has backfilled a thread's comments.
     */
    public static RedditThread withCommentCount(RedditThread t, int numComments) {
        return new RedditThread(t.id(), t.subreddit(), t.title(), t.author(), t.textContent(),
                t.createdUtc(), t.permalink(), t.score(), t.upvoteRatio(), numComments,
                System.currentTimeMillis() / 1000, t.imageUrls(), t.pollData());
    }

    /**
     * Reduces a feed entry's HTML content to readable text: drops the RSS
     * "submitted by … [link] [comments]" footer, strips tags, unescapes
     * entities, and collapses whitespace. Returns {@code ""} for image-only
     * posts (their pictures live in the image list instead).
     */
    public static String extractBodyText(String html) {
        if (html == null || html.isBlank()) return "";
        String s = html;
        int cut = s.indexOf("submitted by");
        if (cut >= 0) s = s.substring(0, cut);
        s = HTML_TAG.matcher(s).replaceAll(" ");
        s = RedditText.unescapeHtml(s);
        s = WHITESPACE.matcher(s).replaceAll(" ").trim();
        return s;
    }

    /**
     * Collects image URLs from the entry's HTML attributes plus the
     * {@code media:thumbnail}. When a full-resolution {@code i.redd.it} URL is
     * present, the preview variants are dropped so the same picture isn't
     * analysed twice. Capped at {@value #MAX_IMAGES}.
     */
    public static List<String> extractImages(String html, String thumbnail) {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        if (html != null) {
            Matcher m = ATTR_URL.matcher(html);
            while (m.find()) {
                String u = RedditText.unescapeHtml(m.group(1));
                if (RedditText.isImageUrl(u)) all.add(u);
            }
        }
        if (thumbnail != null) {
            String u = RedditText.unescapeHtml(thumbnail);
            if (RedditText.isImageUrl(u)) all.add(u);
        }
        if (all.isEmpty()) return List.of();

        List<String> full = new ArrayList<>();
        for (String u : all) if (u.contains("i.redd.it")) full.add(u);
        List<String> chosen = full.isEmpty() ? new ArrayList<>(all) : full;
        return chosen.size() > MAX_IMAGES ? chosen.subList(0, MAX_IMAGES) : chosen;
    }

    /** "/u/name" or "u/name" → "u/name"; placeholders pass through. */
    public static String normalizeAuthor(String name) {
        String u = stripUserPrefix(name);
        if (u.equals("[deleted]") || u.equals("unknown") || u.isBlank()) return u;
        return "u/" + u;
    }

    /** "/u/name" → "name". */
    public static String stripUserPrefix(String name) {
        if (name == null || name.isBlank()) return "unknown";
        String n = name.trim();
        if (n.startsWith("/u/")) return n.substring(3);
        if (n.startsWith("u/")) return n.substring(2);
        return n;
    }

    public static String displayAuthor(String author) {
        return "`u/" + author + "`";
    }

    public static long parseEpoch(String iso) {
        if (iso == null || iso.isBlank()) return System.currentTimeMillis() / 1000;
        try {
            return OffsetDateTime.parse(iso).toEpochSecond();
        } catch (Exception e) {
            return System.currentTimeMillis() / 1000;
        }
    }

    /** Extracts the Reddit path from a feed link, dropping scheme + host. */
    public static String permalinkOf(String link) {
        if (link == null) return "";
        int idx = link.indexOf("/r/");
        return idx >= 0 ? link.substring(idx) : link;
    }
}
