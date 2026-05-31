package de.bsommerfeld.wsbg.terminal.reddit;

import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Accumulates delta statistics across one or more scrape cycles.
 *
 * <p>
 * Tracks the difference between what was fetched and what was already cached:
 * new threads, new upvotes (score increases), new comments, and the full set
 * of thread IDs that were examined. The caller uses {@link #hasUpdates()} to
 * decide whether a notification or analysis cycle is warranted.
 *
 * <p>
 * Hoisted to a top-level type (previously nested in {@code RedditScraper}) so it
 * can serve as the shared return type of the {@link RedditSource} contract,
 * which both the JSON-backed {@code RedditScraper} and the Atom-backed
 * {@code RssRedditScraper} implement.
 */
public class ScrapeStats {
    public int newThreads = 0;
    public int newUpvotes = 0;
    public int newComments = 0;
    public List<RedditThread> threadUpdates = new ArrayList<>();
    public Set<String> scannedIds = new HashSet<>();

    /**
     * Merges another stats instance into this one (used for multi-subreddit scans).
     */
    public void add(ScrapeStats other) {
        this.newThreads += other.newThreads;
        this.newUpvotes += other.newUpvotes;
        this.newComments += other.newComments;
        this.threadUpdates.addAll(other.threadUpdates);
        this.scannedIds.addAll(other.scannedIds);
    }

    /** Returns {@code true} if any metric recorded a positive delta. */
    public boolean hasUpdates() {
        return newThreads > 0 || newUpvotes > 0 || newComments > 0;
    }

    @Override
    public String toString() {
        return String.format("%d new threads, %d new upvotes, %d new comments",
                newThreads, newUpvotes, newComments);
    }
}
