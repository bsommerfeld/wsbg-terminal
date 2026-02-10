package de.bsommerfeld.wsbg.terminal.reddit;

import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

/**
 * Test Implementation of RedditScraper.
 * Returns empty stats/data to simulate "No Data Fetching" mode.
 */
@Singleton
public class TestRedditScraper extends RedditScraper {

    private static final Logger LOG = LoggerFactory.getLogger(TestRedditScraper.class);

    // Counter to simulate "new" threads over time
    private int callCount = 0;

    @Inject
    public TestRedditScraper(RedditRepository repository) {
        super(repository);
        LOG.warn("#######################################################");
        LOG.warn("#  TEST MODE ENABLED: Reddit Scraper is DISABLED      #");
        LOG.warn("#  Using Dummy Data Generator for Scans               #");
        LOG.warn("#######################################################");
    }

    @Override
    public ScrapeStats scanSubreddit(String subreddit) {
        LOG.debug("[TEST] Simulating Scan for r/{}", subreddit);
        ScrapeStats stats = new ScrapeStats();
        callCount++;

        // Return new data every 3rd call to simulate real-time flow
        if (callCount % 3 == 0) {
            java.util.List<de.bsommerfeld.wsbg.terminal.core.domain.RedditThread> newThreads = de.bsommerfeld.wsbg.terminal.core.util.TestDataGenerator
                    .generateThreads(2);

            stats.newThreads = newThreads.size();
            stats.threadUpdates.addAll(newThreads);

            // Side-effect: Save to DB (which delegates to TestDatabaseService)
            repository.saveThreadsBatch(newThreads);
        }

        return stats;
    }

    @Override
    public ScrapeStats scanSubredditHot(String subreddit) {
        // Similar to scan
        return scanSubreddit(subreddit);
    }

    @Override
    public ScrapeStats updateThreadsBatch(List<String> threadIds) {
        return new ScrapeStats();
    }

    @Override
    public ThreadAnalysisContext fetchThreadContext(String permalink) {
        LOG.debug("[TEST] Stub fetchThreadContext: {}", permalink);
        // Generate on fly
        ThreadAnalysisContext ctx = new ThreadAnalysisContext();
        ctx.title = "TEST THREAD CONTEXT " + System.currentTimeMillis();
        ctx.selftext = "Generated content for testing deep analysis.";

        List<de.bsommerfeld.wsbg.terminal.core.domain.RedditComment> comments = de.bsommerfeld.wsbg.terminal.core.util.TestDataGenerator
                .generateCommentsRecursive("dummy", 10);

        for (de.bsommerfeld.wsbg.terminal.core.domain.RedditComment c : comments) {
            ctx.comments.add(c.getAuthor() + ": " + c.getBody());
        }
        return ctx;
    }

    @Override
    public List<String> fetchThreadComments(String permalink) {
        return Collections.emptyList();
    }
}
