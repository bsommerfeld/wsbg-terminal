package de.bsommerfeld.wsbg.terminal.reddit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.config.RedditConfig;
import de.bsommerfeld.wsbg.terminal.core.domain.PollData;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.RedditHealthEvent;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Scrapes Reddit threads and comments via the public {@code .json} endpoints
 * — no API key required.
 *
 * <h3>How it works</h3>
 * Reddit exposes every listing and thread page as JSON by appending
 * {@code .json} to the URL (e.g. {@code /r/stocks/new.json}). This class
 * fetches those endpoints with a standard {@link HttpClient}, parses the
 * response with Jackson, and persists the data through the
 * {@link RedditRepository}.
 *
 * <h3>Rate limiting</h3>
 * Reddit returns {@code x-ratelimit-remaining} and {@code x-ratelimit-reset}
 * headers on every response. When remaining requests drop below 2, the
 * scraper blocks the calling thread for the reset window so it stays within
 * Reddit's published limit and backs off cooperatively.
 *
 * <p>
 * The unauthenticated JSON endpoint has a documented soft limit of
 * 100 req / 10 min per IP+UA pair. We stay inside that with a 180 s
 * scan cadence + activity-windowed gap-fill (see
 * {@code PassiveMonitorService.scanCycle}). Outside the cadence
 * controls, the scraper surfaces the throttle state through a
 * {@code RedditHealthEvent} so the UI can render a "RATE LIMITED" badge
 * (see {@link #recordFetchOutcome}). Once Reddit blacklists the pair,
 * recovery typically takes 1-6 hours.
 *
 * <h3>OAuth fallback (planned)</h3>
 * Long-term, the correct fix for daily-use scenarios and any user
 * distribution is to switch this class to authenticated mode against
 * {@code oauth.reddit.com}. With a registered Reddit app and a
 * persisted user-token, the budget rises to ~600 req / min on the supported
 * API, which removes the 403/429 friction. The UI hook for the OAuth prompt lives in
 * {@code RedditHealthPublisher} — once the user has signed in and a
 * token is on disk, this class should branch on token presence and
 * swap base URL + Authorization header. See {@code TODO(oauth-login)}
 * comments throughout the codebase for every touchpoint.
 *
 * <h3>Transport</h3>
 * The actual byte-fetching is delegated to a {@link RedditTransport}, so this
 * class is agnostic to whether a request goes out via a JDK {@code HttpClient}
 * or the embedded browser. Rate limiting and User-Agent concerns live with the
 * transport / this class respectively; see {@link JdkRedditTransport}.
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
 * comment count against the currently cached version in the repository.
 * A deep context fetch (expensive — one HTTP call + full comment tree parse)
 * is only triggered when the comment count has increased, avoiding redundant
 * network calls for threads that haven't changed.
 */
@Singleton
public class RedditScraper implements RedditSource {

    private static final Logger LOG = LoggerFactory.getLogger(RedditScraper.class);

    private static final String REDDIT_BASE = "https://www.reddit.com";
    private static final String JSON_SUFFIX = ".json";

    /**
     * Maximum recursive depth when traversing the comment tree. Reddit
     * threads can theoretically nest hundreds of levels deep, but anything
     * beyond 8 is noise for analysis purposes and not worth the parse time.
     */
    private static final int MAX_COMMENT_DEPTH = 8;

    /** Number of threads requested per listing page ({@code ?limit=N}). */
    private static final int LISTING_LIMIT = 50;

    /**
     * Maximum IDs per {@code /by_id/} request. Reddit's endpoint accepts
     * up to 100 comma-separated fullnames before returning a 414 URI Too Long.
     */
    private static final int BATCH_SIZE = 100;

    /**
     * Thread permanently excluded from scraping.
     * ID: {@code t3_nwvkto} — "Willkommen im r/wallstreetbetsGER LiveChat!"
     * It has a massive comment history with extremely low signal-to-noise
     * ratio, causing unnecessary load and noise during analysis.
     */
    private static final String BLACKLISTED_THREAD_ID = "t3_nwvkto";

    /**
     * Greedy URL pattern used by {@link #extractImages} to find HTTP(S) links
     * embedded in comment text. The greedy {@code \\S+} deliberately
     * over-matches (capturing trailing punctuation), which is then stripped
     * by {@link #stripTrailingPunctuation}.
     */
    private static final Pattern URL_PATTERN = Pattern.compile("https?://\\S+");

    /**
     * Matches a Reddit inline-media reference inside selftext markdown, e.g.
     * {@code ![img](abc123 "caption")} or {@code ![gif](xy7q9)}. The captured
     * group is the {@code media_id} — a key into the post's
     * {@code media_metadata} map, NOT a URL. These are the images a user
     * embeds mid-text via the rich-text editor; {@code is_gallery} is false
     * for such posts, so they bypass the gallery path. See
     * {@link #extractInlineBodyImages}.
     */
    private static final Pattern INLINE_MEDIA_REF =
            Pattern.compile("!\\[[^\\]]*]\\(([^)\\s\"]+)[^)]*\\)");

    protected final RedditRepository repository;

    /**
     * Fetches the actual bytes. Swapped for a browser-backed implementation in
     * production so the {@code .json} endpoint is reached as an ordinary browser
     * request; defaults to {@link JdkRedditTransport} for tests/CLI.
     */
    private final RedditTransport transport;

    /**
     * Single shared mapper instance. Jackson's {@link ObjectMapper} is
     * thread-safe for reading, so one instance is reused across all parse
     * calls instead of creating a new one per request.
     */
    private final ObjectMapper mapper;

    /**
     * Caps the sustained outgoing request rate so the scanner spreads its
     * work across the scan interval instead of bursting all calls back-to-back.
     * Every HTTP call goes through {@link #executeGet}, which acquires a token
     * before sending — the limiter is the single chokepoint for outbound load.
     */
    private final TokenBucketRateLimiter rateLimiter;

    /**
     * Health state for the unauthenticated scrape endpoint. Reddit
     * returns HTTP 403/429 on anonymous JSON access once a client
     * crosses an undocumented per-IP / per-UA threshold. We
     * track consecutive failures + the start of the current degraded
     * run so the UI can fade in a status label and — later, when the
     * outage persists — surface a Reddit-OAuth login CTA.
     *
     * <p>
     * TODO(oauth-login): when {@code degradedSinceEpochMs > 0} and the
     * gap exceeds a threshold (e.g. 10 min), the UI should prompt the
     * user to sign in to Reddit, which switches the scraper to
     * authenticated mode (oauth.reddit.com endpoint, 600 req/min budget).
     * The login flow + settings panel come later; for now we only emit
     * the OK/DEGRADED transitions so the label can animate.
     */
    private final ApplicationEventBus eventBus;
    private volatile boolean degraded = false;
    private volatile long degradedSinceEpochMs = 0L;

    @Inject
    public RedditScraper(RedditRepository repository, ApplicationEventBus eventBus,
            RedditTransport transport, TokenBucketRateLimiter rateLimiter) {
        this(repository, rateLimiter, eventBus, transport);
    }

    /**
     * Convenience constructor for callers that don't need health-status events
     * posted (tests, CLI tools). Uses the plain JDK transport. Production wiring
     * goes through the {@link Inject @Inject} constructor, which receives the
     * browser-backed transport.
     */
    public RedditScraper(RedditRepository repository, GlobalConfig config) {
        this(repository, buildLimiterFromConfig(config), null, new JdkRedditTransport());
    }

    protected RedditScraper(RedditRepository repository, TokenBucketRateLimiter rateLimiter,
            ApplicationEventBus eventBus, RedditTransport transport) {
        this.repository = repository;
        this.transport = transport;
        this.mapper = new ObjectMapper();
        this.rateLimiter = rateLimiter;
        this.eventBus = eventBus;
    }

    /**
     * Records the outcome of an outbound listing fetch and posts a
     * {@link RedditHealthEvent} only on state transitions. Idempotent
     * within a state — repeated failures while already degraded don't
     * spam the bus.
     */
    private void recordFetchOutcome(boolean success) {
        boolean wasDegraded = degraded;
        if (success) {
            if (wasDegraded) {
                degraded = false;
                degradedSinceEpochMs = 0L;
                postHealth(RedditHealthEvent.State.OK, 0L);
            }
        } else {
            if (!wasDegraded) {
                degraded = true;
                degradedSinceEpochMs = System.currentTimeMillis();
                postHealth(RedditHealthEvent.State.DEGRADED, degradedSinceEpochMs);
            }
        }
    }

    private void postHealth(RedditHealthEvent.State state, long sinceMs) {
        if (eventBus == null) return;
        try {
            eventBus.post(new RedditHealthEvent(state, sinceMs));
        } catch (Exception e) {
            LOG.debug("Failed to post RedditHealthEvent: {}", e.getMessage());
        }
    }

    private static TokenBucketRateLimiter buildLimiterFromConfig(GlobalConfig config) {
        RedditConfig rc = config.getReddit();
        return new TokenBucketRateLimiter(rc.getRateLimitBurst(), rc.getRateLimitRequestsPerSecond());
    }

    // =====================================================================
    // Thread Context (deep fetch for a single thread)
    // =====================================================================

    /**
     * Fetches the full context of a single thread: title, selftext, image,
     * and all comments up to {@value #MAX_COMMENT_DEPTH} levels deep.
     *
     * <p>
     * Each discovered comment is saved to the repository as a side effect.
     * This is intentional — the comment tree is only available during this
     * fetch, and deferring persistence would require buffering the entire tree.
     *
     * @param permalink Reddit permalink (e.g.
     *                  {@code /r/stocks/comments/abc123/title/})
     * @return populated context, or an empty context if the fetch fails
     */
    public ThreadAnalysisContext fetchThreadContext(String permalink) {
        ThreadAnalysisContext context = new ThreadAnalysisContext();

        if (permalink == null || permalink.isEmpty()) {
            LOG.error("Cannot fetch context: Permalink is empty");
            return context;
        }

        permalink = normalizePermalink(permalink);
        String url = REDDIT_BASE + permalink + JSON_SUFFIX + "?limit=500&depth=20";
        LOG.info("Fetching Thread Context: {}", url);

        try {
            RedditResponse response = executeGet(url);
            if (response.statusCode() != 200)
                return context;

            JsonNode root = mapper.readTree(response.body());
            if (!root.isArray() || root.isEmpty())
                return context;

            // Reddit returns a 2-element array: [0] = thread listing, [1] = comment listing
            parseThreadData(root.get(0), context);

            if (root.size() > 1) {
                processComments(root.get(1), context, 0);
            }
        } catch (Exception e) {
            LOG.error("Failed to fetch context for {}", permalink, e);
        }
        return context;
    }

    /**
     * Extracts thread metadata from the first element of the Reddit JSON
     * response array. Reddit wraps the thread in a listing with a single
     * child, so the path is {@code [0].data.children[0].data}.
     *
     * <p>
     * For link posts that have no selftext, the linked URL is used as a
     * fallback body to ensure the context is never completely empty.
     */
    private void parseThreadData(JsonNode listing, ThreadAnalysisContext context) {
        JsonNode data = listing.get("data").get("children").get(0).get("data");

        // "name" is the fullname (t3_abc123), "id" is the bare ID (abc123)
        context.threadId = data.has("name")
                ? data.get("name").asText()
                : "t3_" + data.path("id").asText();

        context.title = data.path("title").asText(null);
        context.selftext = data.path("selftext").asText(null);

        if (context.selftext == null || context.selftext.isEmpty()) {
            JsonNode crosspostParent = firstCrosspostParent(data);
            if (crosspostParent != null) {
                String parentText = crosspostParent.path("selftext").asText("");
                if (!parentText.isEmpty()) context.selftext = parentText;
            }
        }
        if ((context.selftext == null || context.selftext.isEmpty())
                && data.has("url_overridden_by_dest")) {
            context.selftext = "[Link: " + data.get("url_overridden_by_dest").asText() + "]";
        }

        List<String> images = resolveImageUrls(data);
        if (!images.isEmpty()) {
            context.imageUrl = images.get(0);
        }
    }

    // =====================================================================
    // Comment Processing
    // =====================================================================

    /**
     * Recursively traverses the comment tree, extracting text, images,
     * and metadata from each comment node.
     *
     * <p>
     * Reddit's comment JSON nests replies as a full listing object inside
     * each comment's {@code replies} field. The recursion mirrors this
     * structure, incrementing {@code depth} at each level. Comments beyond
     * {@value #MAX_COMMENT_DEPTH} are discarded — deeply nested replies
     * rarely contain actionable information for analysis.
     *
     * <p>
     * Each comment is persisted to the repository immediately during
     * traversal. Formatted versions (with indentation and author prefix) are
     * also collected in the context for the AI agent's text analysis.
     */
    private void processComments(JsonNode node, ThreadAnalysisContext context, int depth) {
        if (depth > MAX_COMMENT_DEPTH)
            return;
        if (!node.has("data") || !node.get("data").has("children"))
            return;

        for (JsonNode child : node.get("data").get("children")) {
            JsonNode data = child.get("data");
            // Reddit returns no "body" field for non-comment children (e.g. "more"
            // objects),
            // and null body for deleted/removed comments — skip both
            if (!data.has("body") || data.get("body").isNull())
                continue;

            String indent = "  ".repeat(depth);
            String rawBody = data.get("body").asText().replace("\n", " ");

            ImageExtractionResult extraction = extractImages(rawBody, data, context);
            String body = extraction.text();

            String rawAuthor = data.path("author").asText("anon");
            int score = data.path("score").asInt(0);
            String displayAuthor = isRealAuthor(rawAuthor) ? "`u/" + rawAuthor + "`" : rawAuthor;

            context.comments.add(indent + displayAuthor + " (Score: " + score + "): " + body);

            saveCommentToRepository(data, context, body, rawAuthor, score, extraction.images());

            if (data.has("replies") && data.get("replies").isObject()) {
                processComments(data.get("replies"), context, depth + 1);
            }
        }
    }

    /**
     * Maps a single JSON comment node to a {@link RedditComment} domain
     * object and persists it. The parent–child relationship is preserved
     * via {@code parent_id}, which the UI uses to reconstruct the tree.
     */
    private void saveCommentToRepository(JsonNode data, ThreadAnalysisContext context,
            String body, String author, int score,
            List<String> images) {
        String id = data.has("name")
                ? data.get("name").asText()
                : "unknown_" + UUID.randomUUID();
        String parentId = data.path("parent_id").asText(context.threadId);
        long createdUtc = data.path("created_utc").asLong(System.currentTimeMillis() / 1000);
        long now = System.currentTimeMillis() / 1000;

        RedditComment comment = new RedditComment(
                id,
                context.threadId != null ? context.threadId : "unknown",
                parentId, author, body, score, createdUtc,
                now, now, images);

        // INFO-level dump of every parsed comment so the operator can
        // follow the conversation feeding the cluster reports. Body is
        // truncated to keep the log scannable; full body lands in the
        // brief the editorial agent reads.
        if (body != null && !body.isBlank()) {
            String snippet = body.length() > 140
                    ? body.substring(0, 140).replace('\n', ' ') + "…"
                    : body.replace('\n', ' ');
            LOG.info("[REDDIT]   comment on {} | {} (score={}): {}",
                    context.threadId, author, score, snippet);
        }
        repository.saveComment(comment);
    }

    // =====================================================================
    // Subreddit Scanning
    // =====================================================================

    /**
     * Scans the "new" listing of a subreddit for the latest
     * {@value #LISTING_LIMIT} threads.
     *
     * <p>
     * New threads trigger an immediate deep context fetch. Existing
     * threads with increased comment counts also trigger a re-fetch.
     * All threads (new and existing) are batch-saved to the repository
     * to update metadata fields like score and upvote ratio.
     *
     * @return delta statistics comparing the fetched state against the cache
     */
    public ScrapeStats scanSubreddit(String subreddit) {
        return scanSubredditListing(subreddit, "new");
    }

    /**
     * Scans the "hot" listing — same behavior as {@link #scanSubreddit}
     * but sorted by Reddit's hotness algorithm rather than chronologically.
     */
    public ScrapeStats scanSubredditHot(String subreddit) {
        return scanSubredditListing(subreddit, "hot");
    }

    /**
     * Shared implementation for both listing types. The {@code listing}
     * parameter determines the sort order ("new", "hot", "rising", etc.)
     * appended to the subreddit URL.
     */
    private ScrapeStats scanSubredditListing(String subreddit, String listing) {
        LOG.info("Scanning r/{}/{}", subreddit, listing);
        String url = REDDIT_BASE + "/r/" + subreddit + "/" + listing + JSON_SUFFIX + "?limit=" + LISTING_LIMIT;
        ScrapeStats stats = new ScrapeStats();

        try {
            RedditResponse response = executeGet(url);
            if (response.statusCode() != 200) {
                LOG.error("Failed to fetch subreddit data: HTTP {}", response.statusCode());
                recordFetchOutcome(false);
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
            recordFetchOutcome(true);
        } catch (Exception e) {
            LOG.error("Error scraping subreddit {}", subreddit, e);
            recordFetchOutcome(false);
        }
        return stats;
    }

    // =====================================================================
    // Batch Thread Update
    // =====================================================================

    /**
     * Updates metadata for a list of known thread IDs via Reddit's
     * {@code /by_id/} endpoint, which accepts up to {@value #BATCH_SIZE}
     * comma-separated fullnames per request.
     *
     * <p>
     * This is used to refresh score, upvote ratio, and comment counts
     * for threads already in the database without re-scraping the entire
     * subreddit listing. IDs are deduplicated and batched automatically.
     *
     * @param threadIds list of Reddit fullnames (e.g. {@code t3_abc123}) or
     *                  bare IDs (the {@code t3_} prefix is added if missing)
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
                RedditResponse response = executeGet(url);
                if (response.statusCode() != 200) {
                    LOG.warn("Batch update failed (HTTP {}): {}", response.statusCode(), url);
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
     * Processes a single thread child from a Reddit listing. Determines
     * whether this is a new or existing thread (via repository lookup) and
     * delegates to the appropriate handler.
     *
     * <p>
     * Blacklisted threads are silently skipped. Every non-blacklisted
     * thread ID is recorded in {@code stats.scannedIds} regardless of
     * whether it was new or existing.
     */
    private void processThreadNode(JsonNode child, ScrapeStats stats, String subredditDefault,
            List<RedditThread> batchCollector) {
        try {
            JsonNode data = child.get("data");
            String id = data.path("name").asText("unknown");

            if (id.equals(BLACKLISTED_THREAD_ID) || id.contains("nwvkto")) {
                LOG.debug("Skipping Blacklisted Thread: {}", id);
                return;
            }

            stats.scannedIds.add(id);

            RedditThread thread = parseThread(data, id, subredditDefault);
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
     * Maps a raw JSON thread data node to a {@link RedditThread} domain object.
     *
     * <p>
     * For link posts (no selftext), the linked URL is used as the body
     * prefixed with {@code [Link: ...]} so the AI agent still has something
     * to analyze. The author is prefixed with {@code u/} unless it's a
     * placeholder value like "unknown" or "[deleted]".
     */
    private RedditThread parseThread(JsonNode data, String id, String subredditDefault) {
        String subreddit = data.path("subreddit").asText(subredditDefault);
        String title = data.path("title").asText("No Title");
        String rawAuthor = data.path("author").asText("unknown");
        String author = isRealAuthor(rawAuthor) ? "u/" + rawAuthor : rawAuthor;

        String selftext = data.path("selftext").asText("");
        if (selftext.isEmpty()) {
            // Crossposts render the original post's body; pull it from the
            // parent so the agent analyses the actual content, not a bare link.
            JsonNode crosspostParent = firstCrosspostParent(data);
            if (crosspostParent != null) {
                selftext = crosspostParent.path("selftext").asText("");
            }
        }
        if (selftext.isEmpty()) {
            if (data.has("url_overridden_by_dest")) {
                selftext = "[Link: " + data.get("url_overridden_by_dest").asText() + "]";
            } else if (data.has("url")) {
                selftext = "[Link: " + data.get("url").asText() + "]";
            }
        }

        long created = data.path("created_utc").asLong(0);
        String permalink = data.path("permalink").asText("");
        int score = data.path("score").asInt(0);
        double upvoteRatio = data.path("upvote_ratio").asDouble(0.0);
        int numComments = data.path("num_comments").asInt(0);

        List<String> imageUrls = resolveImageUrls(data);

        long resolvedActivity = resolveLastActivity(id, created, numComments);
        PollData pollData = parsePollData(data);

        return new RedditThread(id, subreddit, title, author, selftext, created,
                permalink, score, upvoteRatio, numComments, resolvedActivity,
                imageUrls, pollData);
    }

    /**
     * Extracts the {@code poll_data} object Reddit emits on poll-type
     * posts. Shape:
     *
     * <pre>
     * "poll_data": {
     *   "options": [
     *     {"id":"abc", "text":"rot",       "vote_count": 13},
     *     {"id":"def", "text":"grün",      "vote_count": 11},
     *     {"id":"ghi", "text":"enthalten", "vote_count": 3}
     *   ],
     *   "total_vote_count": 27,
     *   "voting_end_timestamp": 1779944000000  // milliseconds
     * }
     * </pre>
     *
     * Returns {@code null} when the post isn't a poll. The
     * {@code voting_end_timestamp} is reported by Reddit in milliseconds;
     * we normalise to epoch-seconds to match the rest of the domain.
     */
    private PollData parsePollData(JsonNode data) {
        JsonNode poll = data.path("poll_data");
        if (poll.isMissingNode() || poll.isNull()) return null;

        JsonNode optionsNode = poll.path("options");
        if (!optionsNode.isArray() || optionsNode.isEmpty()) return null;

        List<PollData.PollOption> options = new ArrayList<>();
        for (JsonNode opt : optionsNode) {
            String text = opt.path("text").asText("").trim();
            if (text.isEmpty()) continue;
            options.add(new PollData.PollOption(
                    opt.path("id").asText(""),
                    text,
                    opt.path("vote_count").asInt(0)));
        }
        if (options.isEmpty()) return null;

        int total = poll.path("total_vote_count").asInt(0);
        // Reddit emits voting_end_timestamp in MILLISECONDS — divide.
        long endsMs = poll.path("voting_end_timestamp").asLong(0L);
        long endsSec = endsMs > 0 ? endsMs / 1000L : 0L;
        return new PollData(options, total, endsSec);
    }

    /**
     * Resolves the "last activity" timestamp for a thread.
     *
     * <p>
     * For new threads (not yet in the repository), the creation time is
     * used. For existing threads, if the comment count has increased since
     * the last fetch, the activity is bumped to "now" — this is what drives
     * the "recent activity" sort order in the UI. Otherwise the previously
     * cached value is preserved.
     */
    private long resolveLastActivity(String threadId, long createdUtc, int numComments) {
        RedditThread existing = repository.getThread(threadId);
        if (existing == null)
            return createdUtc;

        if (numComments > existing.numComments()) {
            return System.currentTimeMillis() / 1000;
        }
        return existing.lastActivityUtc();
    }

    /**
     * Handles a thread that doesn't exist in the repository yet. Records
     * all stats as "new", adds the thread to the batch, and triggers an
     * immediate deep context fetch to capture the initial comments.
     */
    private void handleNewThread(RedditThread thread, ScrapeStats stats,
            List<RedditThread> batchCollector) {
        stats.newThreads++;
        stats.newComments += thread.numComments();
        stats.newUpvotes += thread.score();
        stats.threadUpdates.add(thread);
        batchCollector.add(thread);

        // INFO-level dump of every newly seen post so the operator can
        // see the editorial input stream end-to-end without flipping
        // log levels. The body snippet is short by design; full bodies
        // land in the cluster report the agent reads.
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
        LOG.debug("Fetching full context for new thread: {}", thread.id());
        fetchThreadContext(thread.permalink());
    }

    /**
     * Handles a thread that already exists. Computes deltas for comments
     * and score, only triggering a deep re-fetch if the comment count
     * increased. The thread is always added to the batch to update mutable
     * metadata (score, upvote ratio).
     *
     * <p><b>Only new content (comments) re-evaluates a thread, never a score
     * change.</b> A score is pure sentiment colour, not editorial substance —
     * re-clustering / re-headlining a cluster just because votes ticked would
     * republish a near-identical headline off nothing new. So a score-only delta
     * updates the stored metadata (via the batch) but is NOT added to
     * {@code threadUpdates}, which is what drives cluster assignment downstream.
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
            fetchThreadContext(thread.permalink());
        }

        batchCollector.add(thread);
    }

    // =====================================================================
    // Image Extraction
    // =====================================================================

    /**
     * Scans comment text for embedded URLs, identifies image links, and
     * replaces them with {@code [Image]} markers.
     *
     * <p>
     * The marker approach serves two purposes: (1) the AI agent sees
     * a structured hint that an image is present without needing to parse
     * URLs, and (2) the actual image URLs are collected separately for
     * storage in the {@code reddit_images} table.
     *
     * <p>
     * URL matching is intentionally greedy ({@code \\S+}) to avoid
     * missing URLs with query parameters. Trailing punctuation captured
     * by the greedy match is stripped via {@link #stripTrailingPunctuation}
     * before image detection.
     *
     * <p>
     * After the URL pass, inline images embedded via the rich-text editor
     * are resolved against the comment node's {@code media_metadata} (same
     * mechanism as post bodies): the {@code ![img](<media_id>)} reference is
     * looked up and rewritten to an {@code [Image]} marker. Inline refs carry
     * no {@code http}, so the two passes never collide.
     *
     * @return the modified text and a list of discovered image URLs
     */
    private ImageExtractionResult extractImages(String text, JsonNode data,
            ThreadAnalysisContext context) {
        Matcher m = URL_PATTERN.matcher(text);
        StringBuffer sb = new StringBuffer();
        List<String> imagesFound = new ArrayList<>();

        while (m.find()) {
            String fullMatch = m.group();
            String cleanUrl = stripTrailingPunctuation(fullMatch);
            String suffix = fullMatch.substring(cleanUrl.length());
            String unescapedUrl = unescapeHtml(cleanUrl);

            if (isImageUrl(unescapedUrl)) {
                context.imageCounter++;
                imagesFound.add(unescapedUrl);
                m.appendReplacement(sb,
                        Matcher.quoteReplacement(cleanUrl + " [Image]" + suffix));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(fullMatch));
            }
        }
        m.appendTail(sb);

        String withInline = resolveInlineMediaRefs(sb.toString(), data, imagesFound, context);
        return new ImageExtractionResult(withInline, imagesFound);
    }

    /**
     * Resolves Reddit inline-media references ({@code ![img](<media_id>)}) in
     * a comment body against the comment node's {@code media_metadata},
     * appending any newly discovered image URLs to {@code imagesFound} (kept
     * de-duplicated) and rewriting each resolved reference to an
     * {@code [Image]} marker. Unresolvable references (videos, missing
     * entries, or plain markdown image links whose target is a URL rather
     * than a media_id) are left untouched.
     */
    private String resolveInlineMediaRefs(String text, JsonNode data,
            List<String> imagesFound, ThreadAnalysisContext context) {
        JsonNode metadata = data.path("media_metadata");
        if (metadata.isMissingNode() || metadata.isNull()) return text;

        Matcher m = INLINE_MEDIA_REF.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String url = mediaUrlOf(metadata, m.group(1));
            if (url != null) {
                if (!imagesFound.contains(url)) {
                    imagesFound.add(url);
                    context.imageCounter++;
                }
                m.appendReplacement(sb, Matcher.quoteReplacement("[Image]"));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // =====================================================================
    // HTTP
    // =====================================================================

    /**
     * Executes a GET through the configured {@link RedditTransport} with
     * automatic rate-limit handling. Every outgoing call flows through this
     * method so the token bucket is the single outbound chokepoint regardless
     * of which transport is wired in.
     */
    private RedditResponse executeGet(String url) throws Exception {
        rateLimiter.acquire();
        RedditResponse response = transport.get(url);
        checkRateLimit(response);
        return response;
    }

    /**
     * Inspects Reddit's rate-limit response headers. If fewer than 2
     * requests remain in the current window, the thread blocks for the
     * {@code x-ratelimit-reset} duration plus 1 second of safety margin.
     *
     * <p>
     * This preemptive backoff prevents the next request from receiving
     * a 429, which would impose a longer ban. Parsing failures in the
     * header values are silently ignored — a malformed header should not
     * crash the scraper.
     */
    private void checkRateLimit(RedditResponse response) {
        response.header("x-ratelimit-remaining").ifPresent(remaining -> {
            try {
                double rem = Double.parseDouble(remaining);
                if (rem < 2.0) {
                    response.header("x-ratelimit-reset").ifPresent(reset -> {
                        int waitSecs = (int) Double.parseDouble(reset) + 1;
                        LOG.warn("Reddit Rate Limit Near. Sleeping for {}s", waitSecs);
                        try {
                            Thread.sleep(waitSecs * 1000L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                }
            } catch (NumberFormatException e) {
                // Malformed header — ignore
            }
        });
    }

    // =====================================================================
    // URL & Text Utilities
    // =====================================================================

    /**
     * Normalizes a Reddit permalink by stripping a trailing slash and
     * ensuring a leading slash. Reddit URLs may or may not include these
     * depending on the source (API vs. web).
     */
    private String normalizePermalink(String permalink) {
        if (permalink.endsWith("/")) {
            permalink = permalink.substring(0, permalink.length() - 1);
        }
        if (!permalink.startsWith("/")) {
            permalink = "/" + permalink;
        }
        return permalink;
    }

    /**
     * Maximum number of gallery slides stored per thread. Reddit caps
     * galleries at 20; we keep up to 10. Storage is cheap (just URLs) —
     * the cost is vision, and that's decoupled downstream: only the first
     * few slides feed the clustering embedding (bounded latency), while the
     * rest are prefetched asynchronously and shown in the report when ready
     * (see PassiveMonitorService + ReportBuilder). So a deep DD deck keeps
     * all its slides reachable without stalling time-to-headline.
     */
    private static final int MAX_GALLERY_IMAGES = 10;

    /**
     * Resolves the image URLs for a thread, returning an empty list when
     * the post carries no images.
     *
     * <p>Gallery posts ({@code is_gallery: true}) expose their slides via
     * {@code gallery_data.items[].media_id} pointing into the
     * {@code media_metadata} map. We pull the {@code s.u} URL out of each
     * metadata entry in slide order — that's the full-size preview. The
     * list is capped at {@value #MAX_GALLERY_IMAGES} to stay within the
     * vision budget.
     *
     * <p>Single-image posts use {@code url_overridden_by_dest} (preferred)
     * or {@code url}. The candidate is only kept when it actually looks
     * like an image URL.
     */
    private List<String> resolveImageUrls(JsonNode data) {
        // Crossposts ("In anderer Community posten"): this node carries no
        // media of its own — the original post sits in crosspost_parent_list
        // and holds the gallery/inline/link images. Recurse into it so the
        // crosspost inherits the full image-resolution logic.
        JsonNode crosspostParent = firstCrosspostParent(data);
        if (crosspostParent != null) {
            List<String> fromParent = resolveImageUrls(crosspostParent);
            if (!fromParent.isEmpty()) return fromParent;
        }

        if (data.path("is_gallery").asBoolean(false)) {
            List<String> gallery = extractGalleryUrls(data);
            if (!gallery.isEmpty()) return gallery;
        }

        // Images embedded mid-text in the body via the rich-text editor.
        List<String> inline = extractInlineBodyImages(data);
        if (!inline.isEmpty()) return inline;

        String url = null;
        if (data.has("url_overridden_by_dest")) {
            url = unescapeHtml(data.get("url_overridden_by_dest").asText());
        } else if (data.has("url")) {
            url = unescapeHtml(data.get("url").asText());
        }
        if (url != null && isImageUrl(url)) {
            return List.of(url);
        }
        return List.of();
    }

    /**
     * Returns the original post node of a crosspost (the first element of
     * {@code crosspost_parent_list}), or {@code null} when this post isn't a
     * crosspost. Reddit nests the source post's full data — including its
     * {@code media_metadata}, {@code gallery_data} and {@code selftext} —
     * inside that list; the crosspost wrapper itself is otherwise empty.
     */
    private JsonNode firstCrosspostParent(JsonNode data) {
        JsonNode list = data.path("crosspost_parent_list");
        if (list.isArray() && !list.isEmpty()) {
            JsonNode parent = list.get(0);
            if (parent != null && !parent.isNull() && !parent.isMissingNode()) {
                return parent;
            }
        }
        return null;
    }

    /**
     * Collects images embedded inline in the post body. Reddit's rich-text
     * editor stores such images in {@code media_metadata} (same map as
     * galleries) and references each from the selftext markdown as
     * {@code ![img](<media_id> "caption")}. We walk those references in text
     * order, resolve each {@code media_id} against {@code media_metadata},
     * and keep the valid image URLs (de-duplicated, capped at
     * {@value #MAX_GALLERY_IMAGES}). Returns an empty list for posts without
     * inline media.
     */
    private List<String> extractInlineBodyImages(JsonNode data) {
        JsonNode metadata = data.path("media_metadata");
        if (metadata.isMissingNode() || metadata.isNull()) return List.of();
        String selftext = data.path("selftext").asText("");
        if (selftext.isEmpty()) return List.of();

        List<String> urls = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Matcher m = INLINE_MEDIA_REF.matcher(selftext);
        while (m.find() && urls.size() < MAX_GALLERY_IMAGES) {
            String mediaId = m.group(1);
            if (mediaId.isEmpty() || !seen.add(mediaId)) continue;
            String url = mediaUrlOf(metadata, mediaId);
            if (url != null) urls.add(url);
        }
        return urls;
    }

    /**
     * Walks {@code gallery_data.items} in display order, looking each
     * {@code media_id} up in {@code media_metadata}, and collects the
     * {@code s.u} preview URL of every valid image entry. Non-image
     * entries (e.g. embedded videos) and missing metadata are skipped.
     */
    private List<String> extractGalleryUrls(JsonNode data) {
        JsonNode items = data.path("gallery_data").path("items");
        JsonNode metadata = data.path("media_metadata");
        if (!items.isArray() || metadata.isMissingNode() || metadata.isNull()) {
            return List.of();
        }
        List<String> urls = new ArrayList<>();
        for (JsonNode item : items) {
            if (urls.size() >= MAX_GALLERY_IMAGES) break;
            String mediaId = item.path("media_id").asText(null);
            if (mediaId == null || mediaId.isEmpty()) continue;
            String url = mediaUrlOf(metadata, mediaId);
            if (url != null) urls.add(url);
        }
        return urls;
    }

    /**
     * Resolves a single {@code media_id} against a {@code media_metadata}
     * map, returning the full-size preview URL ({@code s.u}) for valid
     * still/animated images, or {@code null} for missing, non-image, or
     * invalid entries (e.g. embedded videos). Shared by the gallery and
     * inline-body image paths.
     */
    private String mediaUrlOf(JsonNode metadata, String mediaId) {
        JsonNode entry = metadata.path(mediaId);
        if (entry.isMissingNode() || entry.isNull()) return null;
        if (!"valid".equals(entry.path("status").asText(""))) return null;
        String type = entry.path("e").asText("");
        if (!"Image".equals(type) && !"AnimatedImage".equals(type)) return null;
        String url = entry.path("s").path("u").asText(null);
        if (url == null || url.isEmpty()) return null;
        return unescapeHtml(url);
    }

    /**
     * Checks whether a URL points to an image by looking for common image
     * file extensions anywhere in the URL. Uses {@code .contains()} rather
     * than {@code .endsWith()} because image CDNs often append query
     * parameters after the extension (e.g. {@code image.jpg?width=640}).
     */
    private boolean isImageUrl(String url) {
        if (url == null)
            return false;
        String lower = url.toLowerCase();
        return lower.contains(".jpg") || lower.contains(".jpeg")
                || lower.contains(".png") || lower.contains(".webp")
                || lower.contains(".gif");
    }

    /**
     * Returns {@code true} if the author name represents a real Reddit
     * user. Placeholder values like "anon", "unknown", and "[deleted]" are
     * excluded — they don't carry useful attribution information.
     */
    private boolean isRealAuthor(String author) {
        return !author.equals("anon") && !author.equals("unknown") && !author.equals("[deleted]");
    }

    /**
     * Reverses HTML entity encoding that Reddit's JSON occasionally
     * contains in URL fields. Without this, URLs with query parameters
     * (e.g. {@code &amp;} instead of {@code &}) would fail to resolve.
     */
    private String unescapeHtml(String url) {
        if (url == null)
            return null;
        return url.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");
    }

    /**
     * Strips trailing punctuation characters that the greedy URL regex
     * ({@code \\S+}) often captures. Markdown-formatted links like
     * {@code [text](url)} leave a trailing {@code )} on the URL, and
     * sentence-ending punctuation like {@code .}, {@code ,}, {@code ;}
     * is also commonly appended.
     */
    private String stripTrailingPunctuation(String url) {
        while (url.endsWith(")") || url.endsWith("]") || url.endsWith(".")
                || url.endsWith(",") || url.endsWith(";")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    // =====================================================================
    // Inner Types
    // =====================================================================

    /**
     * Result of scanning comment text for image URLs. Contains the modified
     * text (with image URLs tagged as {@code [Image]}) and the list of
     * extracted image URLs for separate database storage.
     */
    record ImageExtractionResult(String text, List<String> images) {
    }

    @Override
    public boolean probe(String subreddit) {
        if (subreddit == null || subreddit.isBlank()) return false;
        try {
            String url = REDDIT_BASE + "/r/" + subreddit + "/new" + JSON_SUFFIX + "?limit=1";
            return executeGet(url).statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String sourceName() {
        // Same class serves both OAuth and anonymous .json — the transport
        // tells them apart for logging.
        return "JSON[" + transport.getClass().getSimpleName() + "]";
    }
}
