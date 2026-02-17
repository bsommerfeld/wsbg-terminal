package de.bsommerfeld.wsbg.terminal.reddit;

import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.core.util.TestDataGenerator;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Offline stub that replaces the real {@link RedditScraper} when the
 * application runs in TEST mode ({@code --test} flag).
 *
 * <h3>No network access</h3>
 * No HTTP requests are made — every method returns synthetic data generated
 * by {@link TestDataGenerator}. This allows the full application stack
 * (graph view, sidebar, passive monitor, headline generation) to function
 * identically to production without Reddit API access or an internet
 * connection.
 *
 * <h3>Simulated data cadence</h3>
 * Real-time activity is simulated by generating 2 new threads every
 * {@value #GENERATION_INTERVAL}th scan call. This mimics the intermittent
 * nature of real subreddit activity — not every poll cycle produces new
 * content. The generated threads are saved to the repository so downstream
 * consumers (e.g. the graph view) see them on the next refresh.
 *
 * <h3>What is stubbed</h3>
 * <ul>
 * <li>{@link #scanSubreddit} — generates 2 threads every 3rd call</li>
 * <li>{@link #scanSubredditHot} — delegates to {@link #scanSubreddit}
 * (hot/new distinction is irrelevant for test data)</li>
 * <li>{@link #updateThreadsBatch} — returns empty stats (nothing to
 * update when data is synthetic)</li>
 * <li>{@link #fetchThreadContext} — generates a context with a synthetic
 * title, body, and 10 recursively nested comments</li>
 * </ul>
 *
 * @see TestDataGenerator
 */
@Singleton
public class TestRedditScraper extends RedditScraper {

    private static final Logger LOG = LoggerFactory.getLogger(TestRedditScraper.class);

    /**
     * Generates new data every Nth call to simulate intermittent subreddit
     * activity. A value of 3 means ~1 in 3 poll cycles produces new threads,
     * which roughly matches the cadence of a mid-activity subreddit.
     */
    private static final int GENERATION_INTERVAL = 3;

    private int callCount = 0;

    @Inject
    public TestRedditScraper(RedditRepository repository) {
        super(repository);
        LOG.warn("#######################################################");
        LOG.warn("#  TEST MODE ENABLED: Reddit Scraper is DISABLED      #");
        LOG.warn("#  Using Dummy Data Generator for Scans               #");
        LOG.warn("#######################################################");
    }

    /**
     * Simulates a subreddit scan. Every {@value #GENERATION_INTERVAL}th call,
     * 2 new threads are generated via {@link TestDataGenerator} and saved to
     * the repository. All other calls return empty stats.
     */
    @Override
    public ScrapeStats scanSubreddit(String subreddit) {
        LOG.debug("[TEST] Simulating Scan for r/{}", subreddit);
        ScrapeStats stats = new ScrapeStats();
        callCount++;

        if (callCount % GENERATION_INTERVAL == 0) {
            List<RedditThread> newThreads = TestDataGenerator.generateThreads(2);
            stats.newThreads = newThreads.size();
            stats.threadUpdates.addAll(newThreads);
            repository.saveThreadsBatch(newThreads);
        }

        return stats;
    }

    /**
     * Delegates to {@link #scanSubreddit} — the hot/new sort distinction
     * is irrelevant for synthetic test data.
     */
    @Override
    public ScrapeStats scanSubredditHot(String subreddit) {
        return scanSubreddit(subreddit);
    }

    /**
     * No-op — synthetic threads don't need batch metadata updates since
     * their scores and comment counts never change after generation.
     */
    @Override
    public ScrapeStats updateThreadsBatch(List<String> threadIds) {
        return new ScrapeStats();
    }

    /**
     * Generates a synthetic thread context with a timestamped title,
     * placeholder body text, and 10 recursively nested comments. The
     * generated comments are not saved to the repository here — that
     * responsibility lies with the {@link TestDataGenerator} consumers.
     */
    @Override
    public ThreadAnalysisContext fetchThreadContext(String permalink) {
        LOG.debug("[TEST] Stub fetchThreadContext: {}", permalink);
        ThreadAnalysisContext ctx = new ThreadAnalysisContext();
        ctx.title = "TEST THREAD CONTEXT " + System.currentTimeMillis();
        ctx.selftext = "Generated content for testing deep analysis.";

        List<RedditComment> comments = TestDataGenerator.generateCommentsRecursive("dummy", 10);
        for (RedditComment c : comments) {
            ctx.comments.add(c.author() + ": " + (c.body() != null ? c.body() : "[deleted]"));
        }
        return ctx;
    }
}
