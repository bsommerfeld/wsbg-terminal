package de.bsommerfeld.wsbg.terminal.reddit.support;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The pure address arithmetic of the SUBREDDIT-WIDE comment stream
 * ({@code /r/<sub>/comments/}): one request returns the newest comments of the
 * whole subreddit, across every thread, instead of one request per thread.
 *
 * <h3>Why this exists</h3>
 * The per-thread comment fetch costs one rate-limited request for every thread
 * that showed activity — on a busy subreddit that is dozens of requests per
 * cycle for material the sub-wide stream delivers in ONE. Measured 2026-08-10:
 * 100 comments spanned 21 min on r/wallstreetbets and 41 min on
 * r/wallstreetbetsGER, so a single poll well inside that span loses nothing.
 *
 * <h3>The one thing the stream has to solve</h3>
 * A per-thread fetch knows its thread from the URL it asked for; a sub-wide
 * entry has to say which thread it belongs to. Both transports carry that in
 * the comment's own path — {@code /r/<sub>/comments/<thread>/<slug>/<comment>/}
 * — so the parent thread id, the parent thread's permalink and (on the Atom
 * path) its title are all recoverable from data the entry already carries. No
 * extra request, no lookup.
 *
 * <p>Comments whose parent thread is unknown to the repository get a
 * {@link #stubThread stub} so the comment has something to hang on; the regular
 * listing scan fills the stub in with the real body, score and images the next
 * time it sees the thread.
 */
public final class CommentStream {

    private CommentStream() {}

    /**
     * A Reddit comment path: {@code /r/<sub>/comments/<thread36>/<slug>/<comment36>/}.
     * The thread part is the only mandatory capture — the slug can be empty and
     * the trailing comment id is absent on a thread permalink.
     */
    private static final Pattern COMMENT_PATH =
            Pattern.compile("^/r/([^/]+)/comments/([0-9a-z]+)(?:/([^/]*))?(?:/([0-9a-z]+))?/?");

    /** The Atom entry title's separator: {@code /u/<author> on <thread title>}. */
    private static final String TITLE_SEPARATOR = " on ";

    /** One comment's parent coordinates, recovered from its own path. */
    public record Parent(String threadId, String subreddit, String permalink) {}

    /**
     * Recovers the parent thread's fullname, subreddit and permalink from a
     * comment's link. Accepts an absolute reddit URL or a bare path.
     *
     * @return the parent coordinates, or {@code null} when the link carries no
     *         recognisable comment path (nothing is guessed)
     */
    public static Parent parentOf(String link) {
        if (link == null || link.isBlank()) return null;
        String path = link;
        int idx = path.indexOf("/r/");
        if (idx >= 0) path = path.substring(idx);
        if (!path.startsWith("/r/")) return null;

        Matcher m = COMMENT_PATH.matcher(path);
        if (!m.find()) return null;

        String sub = m.group(1);
        String thread36 = m.group(2);
        String slug = m.group(3) == null ? "" : m.group(3);
        String permalink = "/r/" + sub + "/comments/" + thread36 + "/" + slug + "/";
        return new Parent("t3_" + thread36, sub, permalink);
    }

    /**
     * The parent thread's title as carried by an Atom entry title
     * ({@code /u/<author> on <thread title>}). Reddit usernames cannot contain
     * spaces, so the FIRST {@value #TITLE_SEPARATOR} is the separator.
     *
     * @return the thread title, or {@code null} when the entry title doesn't
     *         carry one
     */
    public static String threadTitleOf(String entryTitle) {
        if (entryTitle == null) return null;
        int at = entryTitle.indexOf(TITLE_SEPARATOR);
        if (at < 0) return null;
        String title = entryTitle.substring(at + TITLE_SEPARATOR.length()).trim();
        return title.isEmpty() ? null : title;
    }

    /**
     * A placeholder thread for a comment whose parent the repository has never
     * seen. It carries only what the stream itself knows — id, subreddit, title,
     * permalink — and deliberately zero score/comment count: those are the
     * listing scan's numbers, and a fabricated value would read as a real
     * measurement everywhere downstream.
     *
     * @param createdUtc best available timestamp (the comment's own — the thread
     *                   is by definition no younger than its comment)
     */
    public static RedditThread stubThread(Parent parent, String title, long createdUtc) {
        return new RedditThread(
                parent.threadId(),
                parent.subreddit(),
                title == null || title.isBlank() ? parent.threadId() : title,
                "unknown",
                "",
                createdUtc,
                parent.permalink(),
                0,
                0.0,
                0,
                createdUtc,
                List.of(),
                null);
    }
}
