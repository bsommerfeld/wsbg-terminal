package de.bsommerfeld.wsbg.terminal.reddit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.config.RedditConfig;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import de.bsommerfeld.wsbg.terminal.reddit.support.DeferredCommentBackfill;
import de.bsommerfeld.wsbg.terminal.reddit.support.RateLimitGuard;
import de.bsommerfeld.wsbg.terminal.reddit.support.RedditConstants;
import de.bsommerfeld.wsbg.terminal.reddit.support.RedditText;
import de.bsommerfeld.wsbg.terminal.reddit.support.SourceHealthReporter;
import de.bsommerfeld.wsbg.terminal.source.net.DirectWebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebFetcher;
import de.bsommerfeld.wsbg.terminal.source.net.WebResponse;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Scrapes Reddit threads and comments via the public {@code .json} endpoints
 * — no API key required.
 *
 * <h3>How it works</h3>
 * Reddit exposes every listing and thread page as JSON by appending
 * {@code .json} to the URL (e.g. {@code /r/stocks/new.json}). This class fetches
 * those endpoints through the shared {@link WebFetcher} seam, parses the response
 * with Jackson, and persists the data through the {@link RedditRepository}.
 *
 * <h3>Responsibilities</h3>
 * This class is the orchestrator; the concerns it used to hold inline now live in
 * dedicated collaborators, all behaviour-preserving:
 * <ul>
 *   <li>rate-limited transport + backoff — {@link RateLimitGuard}</li>
 *   <li>endpoint health transitions — {@link SourceHealthReporter}</li>
 *   <li>JSON thread mapping — {@link RedditThreadMapper} (with {@link PollParser},
 *       {@link RedditMediaExtractor})</li>
 *   <li>comment-tree walk + persistence — {@link CommentTreeBuilder}</li>
 *   <li>async comment backfill + resurface — {@link DeferredCommentBackfill}</li>
 * </ul>
 *
 * <h3>Data flow</h3>
 *
 * <pre>
 * scanSubreddit()          → listing JSON → batch of RedditThread → repository
 *   └ processThreadNode()  → delta check against cached version
 *       └ fetchThreadContext() → deep JSON fetch → RedditComment → repository
 * </pre>
 *
 * <h3>Delta detection</h3>
 * Before persisting a thread, the scraper compares the incoming score and
 * comment count against the currently cached version in the repository. A deep
 * context fetch is only triggered when the comment count has increased.
 */
@Singleton
public class RedditScraper implements RedditSource {

    private static final Logger LOG = LoggerFactory.getLogger(RedditScraper.class);

    private static final String REDDIT_BASE = "https://www.reddit.com";
    private static final String JSON_SUFFIX = ".json";

    /** Number of threads requested per listing page ({@code ?limit=N}). */
    private static final int LISTING_LIMIT = 50;

    /**
     * Maximum IDs per {@code /by_id/} request. Reddit's endpoint accepts up to
     * 100 comma-separated fullnames before returning a 414 URI Too Long.
     */
    private static final int BATCH_SIZE = 100;

    protected final RedditRepository repository;

    /**
     * Fetches the actual bytes through the shared {@link WebFetcher} seam. In
     * production this is a browser→direct chain; tests/CLI default to the plain
     * {@link DirectWebFetcher}. The OAuth and RSS paths inject their own fetcher.
     */
    private final WebFetcher fetcher;

    /** Shared, thread-safe Jackson mapper, reused across all parse calls. */
    private final ObjectMapper mapper;

    private final RateLimitGuard rateGuard;
    private final SourceHealthReporter health;
    private final RedditThreadMapper threadMapper;
    private final CommentTreeBuilder commentBuilder;
    private final DeferredCommentBackfill backfill;

    @Inject
    public RedditScraper(RedditRepository repository, ApplicationEventBus eventBus,
            WebFetcher fetcher, TokenBucketRateLimiter rateLimiter) {
        this(repository, rateLimiter, eventBus, fetcher);
    }

    /**
     * Convenience constructor for callers that don't need health-status events
     * posted (tests, CLI tools). Uses the plain direct transport.
     */
    public RedditScraper(RedditRepository repository, GlobalConfig config) {
        this(repository, buildLimiterFromConfig(config), null, new DirectWebFetcher());
    }

    protected RedditScraper(RedditRepository repository, TokenBucketRateLimiter rateLimiter,
            ApplicationEventBus eventBus, WebFetcher fetcher) {
        this.repository = repository;
        this.fetcher = fetcher;
        this.mapper = new ObjectMapper();
        this.rateGuard = new RateLimitGuard(rateLimiter);
        this.health = new SourceHealthReporter(eventBus);

        RedditMediaExtractor media = new RedditMediaExtractor();
        this.threadMapper = new RedditThreadMapper(repository, media);
        this.commentBuilder = new CommentTreeBuilder(repository, media);

        // Deferred comment ingestion — mirrors the RSS path so the JSON path has
        // the same fast cold-start. Fetching one comment tree per new thread
        // INLINE is what made a cold-start listing crawl for minutes; clustering
        // only needs title+body+vision, so a new thread is enqueued and a single
        // daemon worker backfills its comments in the background.
        this.backfill = new DeferredCommentBackfill("json-comment-ingest", repository,
                (threadId, permalink) -> {
                    fetchThreadContext(permalink); // populates the repo's comment tree
                    return repository.getThread(threadId) != null;
                });
    }

    private static TokenBucketRateLimiter buildLimiterFromConfig(GlobalConfig config) {
        RedditConfig rc = config.getReddit();
        return new TokenBucketRateLimiter(rc.getRateLimitBurst(), rc.getRateLimitRequestsPerSecond());
    }

    // =====================================================================
    // Thread Context (deep fetch for a single thread)
    // =====================================================================

    /**
     * Fetches the full context of a single thread: title, selftext, image, and
     * all comments. Each discovered comment is saved to the repository as a side
     * effect — the comment tree is only available during this fetch.
     *
     * @param permalink Reddit permalink (e.g. {@code /r/stocks/comments/abc123/title/})
     * @return populated context, or an empty context if the fetch fails
     */
    public ThreadAnalysisContext fetchThreadContext(String permalink) {
        ThreadAnalysisContext context = new ThreadAnalysisContext();

        if (permalink == null || permalink.isEmpty()) {
            LOG.error("Cannot fetch context: Permalink is empty");
            return context;
        }

        permalink = RedditText.normalizePermalink(permalink);
        String url = REDDIT_BASE + permalink + JSON_SUFFIX + "?limit=500&depth=20";
        LOG.info("Fetching Thread Context: {}", url);

        try {
            WebResponse response = executeGet(url);
            if (response.status() != 200)
                return context;

            JsonNode root = mapper.readTree(response.body());
            if (!root.isArray() || root.isEmpty())
                return context;

            // Reddit returns a 2-element array: [0] = thread listing, [1] = comment listing
            threadMapper.parseThreadData(root.get(0), context);

            if (root.size() > 1) {
                commentBuilder.processComments(root.get(1), context, 0);
            }
        } catch (Exception e) {
            LOG.error("Failed to fetch context for {}", permalink, e);
        }
        return context;
    }

    // =====================================================================
    // Subreddit Scanning
    // =====================================================================

    /**
     * Scans the "new" listing of a subreddit for the latest
     * {@value #LISTING_LIMIT} threads.
     *
     * @return delta statistics comparing the fetched state against the cache
     */
    public ScrapeStats scanSubreddit(String subreddit) {
        return scanSubredditListing(subreddit, "new");
    }

    /**
     * Scans the "hot" listing — same behavior as {@link #scanSubreddit} but
     * sorted by Reddit's hotness algorithm rather than chronologically.
     */
    public ScrapeStats scanSubredditHot(String subreddit) {
        return scanSubredditListing(subreddit, "hot");
    }

    private ScrapeStats scanSubredditListing(String subreddit, String listing) {
        LOG.info("Scanning r/{}/{}", subreddit, listing);
        String url = REDDIT_BASE + "/r/" + subreddit + "/" + listing + JSON_SUFFIX + "?limit=" + LISTING_LIMIT;
        ScrapeStats stats = new ScrapeStats();

        try {
            WebResponse response = executeGet(url);
            if (response.status() != 200) {
                LOG.error("Failed to fetch subreddit data: HTTP {}", response.status());
                health.record(false);
                stats.failed = true;
                return stats;
            }

            JsonNode root = mapper.readTree(response.body());
            if (root.has("data") && root.get("data").has("children")) {
                List<RedditThread> batch = new ArrayList<>();
                for (JsonNode child : root.get("data").get("children")) {
                    processThreadNode(child, stats, subreddit, batch);
                }
                if (!batch.isEmpty()) {
                    repository.saveThreadsBatch(batch);
                }
            }
            health.record(true);
        } catch (Exception e) {
            LOG.error("Error scraping subreddit {}", subreddit, e);
            health.record(false);
            stats.failed = true;
        }
        backfill.drainInto(stats);
        return stats;
    }

    // =====================================================================
    // Batch Thread Update
    // =====================================================================

    /**
     * Updates metadata for a list of known thread IDs via Reddit's
     * {@code /by_id/} endpoint, which accepts up to {@value #BATCH_SIZE}
     * comma-separated fullnames per request. IDs are deduplicated and batched.
     *
     * @param threadIds Reddit fullnames ({@code t3_…}) or bare IDs
     */
    public ScrapeStats updateThreadsBatch(List<String> threadIds) {
        ScrapeStats stats = new ScrapeStats();
        if (threadIds == null || threadIds.isEmpty())
            return stats;

        List<String> distinctIds = new ArrayList<>(new HashSet<>(threadIds));

        for (int i = 0; i < distinctIds.size(); i += BATCH_SIZE) {
            int end = Math.min(distinctIds.size(), i + BATCH_SIZE);
            List<String> batch = distinctIds.subList(i, end);

            // Reddit's /by_id/ requires fullname format (t3_ prefix for links)
            String idsParam = batch.stream()
                    .map(id -> id.startsWith("t3_") ? id : "t3_" + id)
                    .collect(Collectors.joining(","));

            String url = REDDIT_BASE + "/by_id/" + idsParam + JSON_SUFFIX;
            LOG.debug("Batch Updating {} threads...", batch.size());

            try {
                WebResponse response = executeGet(url);
                if (response.status() != 200) {
                    LOG.warn("Batch update failed (HTTP {}): {}", response.status(), url);
                    continue;
                }

                JsonNode root = mapper.readTree(response.body());
                if (root.has("data") && root.get("data").has("children")) {
                    List<RedditThread> batchToSave = new ArrayList<>();
                    for (JsonNode child : root.get("data").get("children")) {
                        String sub = child.get("data").path("subreddit").asText("unknown");
                        processThreadNode(child, stats, sub, batchToSave);
                    }
                    if (!batchToSave.isEmpty()) {
                        repository.saveThreadsBatch(batchToSave);
                    }
                }
            } catch (Exception e) {
                LOG.error("Error during batch update", e);
            }
        }
        return stats;
    }

    // =====================================================================
    // Thread Node Processing
    // =====================================================================

    /**
     * Processes a single thread child from a Reddit listing: determines whether
     * this is a new or existing thread (via repository lookup) and delegates to
     * the appropriate handler. Blacklisted threads are silently skipped.
     */
    private void processThreadNode(JsonNode child, ScrapeStats stats, String subredditDefault,
            List<RedditThread> batchCollector) {
        try {
            JsonNode data = child.get("data");
            String id = data.path("name").asText("unknown");

            if (id.equals(RedditConstants.BLACKLISTED_THREAD_ID) || id.contains("nwvkto")) {
                LOG.debug("Skipping Blacklisted Thread: {}", id);
                return;
            }

            stats.scannedIds.add(id);

            RedditThread thread = threadMapper.parseThread(data, id, subredditDefault);
            RedditThread existing = repository.getThread(id);

            if (existing == null) {
                handleNewThread(thread, stats, batchCollector);
            } else {
                handleExistingThread(thread, existing, stats, batchCollector);
            }
        } catch (Exception e) {
            LOG.error("Error processing thread node", e);
        }
    }

    /**
     * Handles a thread that doesn't exist in the repository yet. Records all
     * stats as "new", adds the thread to the batch, and enqueues a background
     * comment backfill.
     */
    private void handleNewThread(RedditThread thread, ScrapeStats stats,
            List<RedditThread> batchCollector) {
        stats.newThreads++;
        stats.newComments += thread.numComments();
        stats.newUpvotes += thread.score();
        stats.threadUpdates.add(thread);
        batchCollector.add(thread);

        logNewThread(thread);
        // Defer the comment fetch to the background worker — clustering needs
        // only title+body+vision, so the thread can be clustered now and its
        // comments backfilled (and the headline re-surfaced) shortly.
        backfill.enqueue(thread.id(), thread.permalink());
    }

    /**
     * INFO-level dump of every newly seen post so the operator can see the
     * editorial input stream end-to-end without flipping log levels. The body
     * snippet is short by design; full bodies land in the cluster report.
     */
    private void logNewThread(RedditThread thread) {
        String bodySnippet = thread.textContent() == null ? ""
                : (thread.textContent().length() > 200
                        ? thread.textContent().substring(0, 200).replace('\n', ' ') + "…"
                        : thread.textContent().replace('\n', ' '));
        String imgTag = thread.imageUrls().isEmpty()
                ? ""
                : (thread.imageUrls().size() == 1
                        ? "  [img]"
                        : "  [img×" + thread.imageUrls().size() + "]");
        String pollTag = thread.pollData() == null ? "" : "  [poll]";
        LOG.info("[REDDIT] new thread {} | score={}, comments={}{}{} | {}",
                thread.id(), thread.score(), thread.numComments(), imgTag, pollTag,
                thread.title());
        if (!bodySnippet.isEmpty()) {
            LOG.info("[REDDIT]   body: {}", bodySnippet);
        }
    }

    /**
     * Handles a thread that already exists. Computes deltas for comments and
     * score, only triggering a deep re-fetch if the comment count increased. The
     * thread is always added to the batch to update mutable metadata.
     *
     * <p><b>Only new content (comments) re-evaluates a thread, never a score
     * change.</b> A score-only delta updates the stored metadata (via the batch)
     * but is NOT added to {@code threadUpdates}, which drives cluster assignment.
     */
    private void handleExistingThread(RedditThread thread, RedditThread existing,
            ScrapeStats stats, List<RedditThread> batchCollector) {
        int commentDiff = thread.numComments() - existing.numComments();
        int scoreDiff = thread.score() - existing.score();

        if (commentDiff > 0)
            stats.newComments += commentDiff;
        if (scoreDiff > 0)
            stats.newUpvotes += scoreDiff;
        // New content only — a score-only change is recorded (batch below) but
        // must not re-trigger the editorial pipeline.
        if (commentDiff > 0)
            stats.threadUpdates.add(thread);

        if (commentDiff > 0) {
            LOG.info("Context Update: {} ({})\n +{} comments, Score: {}",
                    thread.title(), thread.id(), commentDiff, thread.score());
            // Deferred like the new-thread path: enqueue the comment refresh.
            backfill.enqueue(thread.id(), thread.permalink());
        }

        batchCollector.add(thread);
    }

    // =====================================================================
    // HTTP
    // =====================================================================

    /**
     * Executes a GET through the configured {@link WebFetcher} with automatic
     * rate-limit handling (the {@link RateLimitGuard} chokepoint).
     */
    private WebResponse executeGet(String url) throws Exception {
        return rateGuard.execute(fetcher, url,
                RedditConstants.REQUEST_HEADERS, RedditConstants.REQUEST_TIMEOUT);
    }

    @Override
    public boolean probe(String subreddit) {
        if (subreddit == null || subreddit.isBlank()) return false;
        try {
            String url = REDDIT_BASE + "/r/" + subreddit + "/new" + JSON_SUFFIX + "?limit=1";
            return executeGet(url).status() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String sourceName() {
        // Same class serves both OAuth and anonymous .json — the fetcher's name
        // tells them apart for logging (e.g. "JSON[chain[browser→direct]]").
        return "JSON[" + fetcher.name() + "]";
    }
}
