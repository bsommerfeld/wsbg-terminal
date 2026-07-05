package de.bsommerfeld.wsbg.terminal.reddit;

import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.config.RedditConfig;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import de.bsommerfeld.wsbg.terminal.reddit.support.DeferredCommentBackfill;
import de.bsommerfeld.wsbg.terminal.reddit.support.RateLimitGuard;
import de.bsommerfeld.wsbg.terminal.reddit.support.RedditConstants;
import de.bsommerfeld.wsbg.terminal.reddit.support.RedditText;
import de.bsommerfeld.wsbg.terminal.reddit.support.SourceHealthReporter;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.TokenBucketRateLimiter;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Anonymous Reddit data source backed by Reddit's Atom feeds on
 * {@code www.reddit.com}. The sibling of {@link RedditScraper}: same
 * {@link RedditSource} contract, same {@link RedditRepository}, same domain
 * objects — but no app registration, no OAuth token, no user login.
 *
 * <h3>Why feeds instead of {@code .json}</h3>
 * Anonymous {@code .json} access is often refused with a 403 for headless
 * clients. The public Atom feeds ({@code …/new.rss}, {@code …/comments/<id>/.rss})
 * are served to a plain client with a 200. They share the same ~100 req / 10 min
 * per-IP budget as the JSON endpoint.
 *
 * <h3>What the feed cannot carry</h3>
 * Atom has no scores, no comment counts, no poll data, and no comment-tree
 * structure (replies arrive as flat entries with no parent linkage). The mapping
 * of these limitations lives in {@link RssEntryMapper}. Comment feeds are capped
 * at ~100 entries per post by Reddit. The OAuth source provides full fidelity
 * when reachable; this RSS source is the always-anonymous fallback in the
 * {@link FallbackRedditSource} chain.
 *
 * <h3>Change detection</h3>
 * Without {@code num_comments} we cannot diff comment counts the way the JSON
 * path does. Instead {@link #updateThreadsBatch} compares the comment IDs in the
 * feed against what the repository already holds — a fresh comment ID is the "new
 * activity" signal that re-surfaces a thread for the editorial agent.
 */
@Singleton
public final class RssRedditScraper implements RedditSource {

    private static final Logger LOG = LoggerFactory.getLogger(RssRedditScraper.class);

    private static final String REDDIT_HOST = "www.reddit.com";
    private static final int LISTING_LIMIT = 100;
    private static final int COMMENT_LIMIT = 100;

    private final RedditRepository repository;
    private final WebFetcher fetcher;
    private final RateLimitGuard rateGuard;
    private final SourceHealthReporter health;
    private final AtomFeedParser feedParser;
    private final DeferredCommentBackfill backfill;

    @Inject
    public RssRedditScraper(RedditRepository repository, GlobalConfig config,
            ApplicationEventBus eventBus) {
        this.repository = repository;
        RedditConfig rc = config.getReddit();
        this.rateGuard = new RateLimitGuard(new TokenBucketRateLimiter(
                rc.getRateLimitBurst(), rc.getRateLimitRequestsPerSecond()));
        this.health = new SourceHealthReporter(eventBus);
        // Plain transport: hits www.reddit.com directly (no OAuth host rewrite,
        // no bearer token) — exactly what the public Atom feeds need.
        this.fetcher = new DirectWebFetcher();
        this.feedParser = new AtomFeedParser();

        // Comment ingestion is the cold-start bottleneck: fetching one comment
        // feed per thread at the anonymous budget turns a 100-thread listing into
        // an ~11-minute serial wait — and clustering doesn't even need comments.
        // So discovery enqueues each new thread here and a single daemon worker
        // drains the queue in the background; the listing scan returns at once.
        this.backfill = new DeferredCommentBackfill("rss-comment-ingest", repository,
                (threadId, permalink) -> {
                    int fresh = ingestComments(threadId, permalink);
                    if (fresh <= 0) return false;
                    RedditThread t = repository.getThread(threadId);
                    if (t == null) return false;
                    int total = repository.getCommentsForThread(threadId, 0).size();
                    repository.saveThread(RssEntryMapper.withCommentCount(t, total));
                    return true;
                });
    }

    // =====================================================================
    // RedditSource contract
    // =====================================================================

    @Override
    public ScrapeStats scanSubreddit(String subreddit) {
        return scanListing(subreddit, "new");
    }

    @Override
    public ScrapeStats scanSubredditHot(String subreddit) {
        return scanListing(subreddit, "hot");
    }

    private ScrapeStats scanListing(String subreddit, String listing) {
        LOG.info("Scanning r/{}/{} via RSS", subreddit, listing);
        ScrapeStats stats = new ScrapeStats();
        String url = redditUrl("/r/" + subreddit + "/" + listing + ".rss",
                "limit=" + LISTING_LIMIT);

        List<AtomFeedParser.Entry> entries = fetchFeed(url);
        if (entries == null) {
            health.record(false);
            stats.failed = true;
            return stats;
        }
        health.record(true);

        List<RedditThread> batch = new ArrayList<>();
        for (AtomFeedParser.Entry e : entries) {
            if (e.id == null || !e.id.startsWith("t3_")) continue;
            if (e.id.equals(RedditConstants.BLACKLISTED_THREAD_ID) || e.id.contains("nwvkto")) continue;

            stats.scannedIds.add(e.id);
            RedditThread existing = repository.getThread(e.id);
            RedditThread thread = RssEntryMapper.toThread(e, subreddit, existing);
            batch.add(thread);

            if (existing == null) {
                stats.newThreads++;
                stats.threadUpdates.add(thread);
                logNewThread(thread);
                // Defer the comment fetch to the background worker — fetching it
                // inline here serialises one rate-limited request per thread and
                // is what made cold start take ~11 min.
                backfill.enqueue(thread.id(), thread.permalink());
            }
        }
        if (!batch.isEmpty()) {
            repository.saveThreadsBatch(batch);
        }
        backfill.drainInto(stats);
        return stats;
    }

    @Override
    public ScrapeStats updateThreadsBatch(List<String> threadIds) {
        ScrapeStats stats = new ScrapeStats();
        if (threadIds == null || threadIds.isEmpty()) return stats;

        for (String rawId : new HashSet<>(threadIds)) {
            String id = rawId.startsWith("t3_") ? rawId : "t3_" + rawId;
            RedditThread existing = repository.getThread(id);
            if (existing == null) continue; // no permalink to fetch the feed from

            int newComments = ingestComments(id, existing.permalink());
            if (newComments > 0) {
                stats.newComments += newComments;
                // Bump activity to "now" so the gap-fill window keeps this thread
                // hot, and re-surface it for clustering.
                RedditThread bumped = RssEntryMapper.withActivity(existing, System.currentTimeMillis() / 1000);
                stats.threadUpdates.add(bumped);
                repository.saveThread(bumped);
                LOG.info("RSS update: {} (+{} new comments) — {}", id, newComments, existing.title());
            }
        }
        return stats;
    }

    @Override
    public ThreadAnalysisContext fetchThreadContext(String permalink) {
        ThreadAnalysisContext context = new ThreadAnalysisContext();
        if (permalink == null || permalink.isBlank()) {
            LOG.error("Cannot fetch context: permalink is empty");
            return context;
        }
        String path = RedditText.normalizePermalink(RssEntryMapper.permalinkOf(permalink));
        String url = redditUrl(path + "/.rss", "limit=" + COMMENT_LIMIT);

        List<AtomFeedParser.Entry> entries = fetchFeed(url);
        if (entries == null || entries.isEmpty()) return context;

        // First t3_ entry is the post itself; the rest are comments.
        for (AtomFeedParser.Entry e : entries) {
            if (e.id != null && e.id.startsWith("t3_")) {
                context.threadId = e.id;
                context.title = e.title;
                context.selftext = RssEntryMapper.extractBodyText(e.content);
                List<String> imgs = RssEntryMapper.extractImages(e.content, e.thumbnail);
                if (!imgs.isEmpty()) context.imageUrl = imgs.get(0);
                break;
            }
        }
        if (context.threadId == null) return context;

        for (AtomFeedParser.Entry e : entries) {
            if (e.id == null || !e.id.startsWith("t1_")) continue;
            RedditComment comment = RssEntryMapper.toComment(e, context.threadId);
            context.comments.add(RssEntryMapper.displayAuthor(comment.author()) + ": " + comment.body());
            repository.saveComment(comment);
        }
        return context;
    }

    @Override
    public boolean probe(String subreddit) {
        if (subreddit == null || subreddit.isBlank()) return false;
        String url = redditUrl("/r/" + subreddit + "/new.rss", "limit=1");
        return fetchFeed(url) != null;
    }

    @Override
    public String sourceName() {
        return "RSS";
    }

    // =====================================================================
    // Comment ingestion
    // =====================================================================

    /**
     * Fetches the comment feed for a thread and persists every comment,
     * returning how many were genuinely new (not already in the repository). The
     * new-comment count is the change signal that drives re-clustering.
     */
    private int ingestComments(String threadId, String permalink) {
        if (permalink == null || permalink.isBlank()) return 0;
        String url = redditUrl(RedditText.normalizePermalink(RssEntryMapper.permalinkOf(permalink)) + "/.rss",
                "limit=" + COMMENT_LIMIT);
        List<AtomFeedParser.Entry> entries = fetchFeed(url);
        if (entries == null) return 0;

        Set<String> known = new HashSet<>();
        for (RedditComment c : repository.getCommentsForThread(threadId, 0)) {
            known.add(c.id());
        }

        int fresh = 0;
        for (AtomFeedParser.Entry e : entries) {
            if (e.id == null || !e.id.startsWith("t1_")) continue;
            if (!known.contains(e.id)) fresh++;
            repository.saveComment(RssEntryMapper.toComment(e, threadId));
        }
        return fresh;
    }

    // =====================================================================
    // HTTP + feed parsing
    // =====================================================================

    /** Returns parsed entries, or {@code null} when the fetch/parse failed. */
    private List<AtomFeedParser.Entry> fetchFeed(String url) {
        try {
            WebResponse response = rateGuard.execute(fetcher, url,
                    RedditConstants.REQUEST_HEADERS, RedditConstants.REQUEST_TIMEOUT);
            if (response.status() != 200) {
                LOG.warn("RSS fetch failed (HTTP {}): {}", response.status(), url);
                return null;
            }
            return feedParser.parse(response.body());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception ex) {
            LOG.error("RSS fetch/parse error for {}", url, ex);
            return null;
        }
    }

    /**
     * Builds an absolute reddit.com URL, percent-encoding the path and query so
     * non-ASCII permalink slugs (e.g. German umlauts in titles) don't blow up
     * {@code URI} parsing in the transport.
     */
    private static String redditUrl(String path, String query) {
        try {
            return new URI("https", REDDIT_HOST, path, query, null).toASCIIString();
        } catch (Exception e) {
            return "https://" + REDDIT_HOST + path + (query != null ? "?" + query : "");
        }
    }

    private void logNewThread(RedditThread thread) {
        String imgTag = thread.imageUrls().isEmpty() ? ""
                : (thread.imageUrls().size() == 1 ? "  [img]" : "  [img×" + thread.imageUrls().size() + "]");
        LOG.info("[REDDIT-RSS] new thread {}{} | {}", thread.id(), imgTag, thread.title());
    }
}
