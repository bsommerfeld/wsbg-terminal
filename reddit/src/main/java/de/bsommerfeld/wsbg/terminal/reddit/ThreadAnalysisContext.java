package de.bsommerfeld.wsbg.terminal.reddit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Aggregated context for a single thread, populated during a deep fetch.
 * Used by the AI agent for topic relevance analysis.
 *
 * <p>
 * Fields are intentionally mutable and public — this is a builder-style
 * accumulator that is populated incrementally during recursive comment tree
 * traversal, not a value object.
 *
 * <p>
 * Hoisted to a top-level type (previously nested in {@code RedditScraper}) so it
 * can serve as the shared return type of the {@link RedditSource} contract.
 */
public class ThreadAnalysisContext {
    public String threadId;
    public String title;
    public String imageUrl;
    public String selftext;
    public Map<String, String> imageIdToUrl = new LinkedHashMap<>();
    public List<String> comments = new ArrayList<>();
    public int imageCounter = 1;

    /** Returns {@code true} if no meaningful content was extracted. */
    public boolean isEmpty() {
        return (title == null || title.isEmpty())
                && (selftext == null || selftext.isEmpty())
                && comments.isEmpty();
    }
}
