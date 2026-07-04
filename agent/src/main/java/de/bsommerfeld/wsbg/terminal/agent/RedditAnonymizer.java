package de.bsommerfeld.wsbg.terminal.agent;

import java.util.regex.Pattern;

/**
 * Masks Reddit author handles before the model ever sees them. A username like
 * {@code NASX_Trader} gets mis-read by the subject extractor as a ticker
 * ({@code $NASX}); sources are cited by comment ID ({@code t1_…}), never by name,
 * so nothing is lost. Shared by {@link ReportBuilder} (post/comment body scrub)
 * and {@link CommentTreeRenderer}. Extracted verbatim from {@link ReportBuilder}.
 */
final class RedditAnonymizer {

    private RedditAnonymizer() {}

    /** The wholesale replacement for an author field / a {@code u/…} mention. */
    static final String ANON_AUTHOR = "[user]";

    private static final Pattern HANDLE =
            Pattern.compile("(?i)(?<![A-Za-z0-9])/?u/[A-Za-z0-9_-]{3,20}");

    /** Replaces {@code u/handle} / {@code /u/handle} mentions in free text with {@link #ANON_AUTHOR}. */
    static String stripHandles(String text) {
        if (text == null || text.isEmpty()) return text;
        return HANDLE.matcher(text).replaceAll(ANON_AUTHOR);
    }
}
