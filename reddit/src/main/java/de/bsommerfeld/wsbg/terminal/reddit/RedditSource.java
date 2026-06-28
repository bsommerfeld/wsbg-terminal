package de.bsommerfeld.wsbg.terminal.reddit;

import java.util.List;

/**
 * The terminal's contract for pulling data out of Reddit. One object,
 * swappable behind a single Guice binding — change the implementation and the
 * data comes from somewhere else, while every consumer keeps calling the same
 * four methods and the same {@link RedditRepository} fills up.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link RedditScraper} — JSON via application-only OAuth
 *       ({@code oauth.reddit.com}). Full fidelity: scores, comment counts,
 *       comment tree, poll data.</li>
 *   <li>{@link RssRedditScraper} — anonymous Atom feeds ({@code www.reddit.com}).
 *       No app, no token, no login. No scores / poll data; comments are a flat
 *       list capped at ~100 per post.</li>
 * </ul>
 *
 * <p>Auto-selected at runtime by {@link FallbackRedditSource}; bound in
 * {@code AppModule}.
 * All implementations write the same {@code RedditThread}/{@code RedditComment}
 * objects to the shared in-memory {@link RedditRepository} as a side effect,
 * and return {@link ScrapeStats} describing what changed.
 */
public interface RedditSource {

    /**
     * Scans the "new" listing of a subreddit and persists discovered threads.
     * New threads trigger an immediate comment fetch as a side effect.
     *
     * @return delta statistics comparing the fetched state against the cache
     */
    ScrapeStats scanSubreddit(String subreddit);

    /**
     * Scans the "hot" listing — same behaviour as {@link #scanSubreddit} but
     * ordered by Reddit's hotness algorithm rather than chronologically.
     */
    ScrapeStats scanSubredditHot(String subreddit);

    /**
     * Refreshes a set of known threads by ID: updates thread metadata and
     * pulls in any comments that have appeared since the last fetch.
     *
     * @param threadIds Reddit fullnames ({@code t3_…}) or bare IDs
     */
    ScrapeStats updateThreadsBatch(List<String> threadIds);

    /**
     * Fetches the full context of a single thread (title, body, image, and
     * comments). Each discovered comment is saved to the repository as a side
     * effect.
     *
     * @param permalink Reddit permalink, e.g. {@code /r/x/comments/abc/title/}
     * @return populated context, or an empty context if the fetch fails
     */
    ThreadAnalysisContext fetchThreadContext(String permalink);

    /**
     * Cheap, side-effect-free reachability check: can this source currently
     * reach Reddit for the given subreddit? Used by {@link FallbackRedditSource}
     * to pick a working path (a {@code limit=1} request, no parsing, no comment
     * fan-out). Returns {@code false} on any error rather than throwing.
     */
    boolean probe(String subreddit);

    /** Short human-readable label for logging which path is active. */
    String sourceName();
}
