package de.bsommerfeld.wsbg.terminal.reddit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
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
 * scraper blocks the calling thread for the reset window to avoid HTTP 429
 * responses that would temporarily ban the IP.
 *
 * <h3>User-Agent convention</h3>
 * Reddit requires a descriptive User-Agent matching the pattern
 * {@code <platform>:<app-id>:<version> (by /u/<username>)}. Requests with
 * generic or missing User-Agents are aggressively rate-limited. The version
 * is injected from {@code reddit-version.properties} at build time via Maven
 * resource filtering.
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
 *
 * @see TestRedditScraper
 */
@Singleton
public class RedditScraper {

    private static final Logger LOG = LoggerFactory.getLogger(RedditScraper.class);

    private static final String REDDIT_BASE = "https://www.reddit.com";
    private static final String JSON_SUFFIX = ".json";

    /**
     * Reddit's API convention:
     * {@code <platform>:<app-id>:<version> (by /u/<user>)}.
     * Using a generic browser UA causes aggressive rate-limiting, so Reddit
     * strongly recommends this format to identify well-behaved bots.
     */
    private static final String USER_AGENT = buildUserAgent();

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

    protected final RedditRepository repository;
    private final HttpClient httpClient;

    /**
     * Single shared mapper instance. Jackson's {@link ObjectMapper} is
     * thread-safe for reading, so one instance is reused across all parse
     * calls instead of creating a new one per request.
     */
    private final ObjectMapper mapper;

    @Inject
    public RedditScraper(RedditRepository repository) {
        this.repository = repository;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .build();
        this.mapper = new ObjectMapper();
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
            HttpResponse<String> response = executeGet(url);
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

        if ((context.selftext == null || context.selftext.isEmpty())
                && data.has("url_overridden_by_dest")) {
            context.selftext = "[Link: " + data.get("url_overridden_by_dest").asText() + "]";
        }

        String candidateUrl = resolveImageCandidate(data);
        if (candidateUrl != null && isImageUrl(candidateUrl)) {
            context.imageUrl = candidateUrl;
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

            ImageExtractionResult extraction = extractImages(rawBody, context);
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
            HttpResponse<String> response = executeGet(url);
            if (response.statusCode() != 200) {
                LOG.error("Failed to fetch subreddit data: HTTP {}", response.statusCode());
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
        } catch (Exception e) {
            LOG.error("Error scraping subreddit {}", subreddit, e);
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
                HttpResponse<String> response = executeGet(url);
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

        String imageUrl = resolveImageCandidate(data);
        if (imageUrl != null && !isImageUrl(imageUrl)) {
            imageUrl = null;
        }

        long resolvedActivity = resolveLastActivity(id, created, numComments);

        return new RedditThread(id, subreddit, title, author, selftext, created,
                permalink, score, upvoteRatio, numComments, resolvedActivity, imageUrl);
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

        LOG.debug("New Thread found: {}", thread.title());
        LOG.info("Fetching full context for new thread: {}", thread.id());
        fetchThreadContext(thread.permalink());
    }

    /**
     * Handles a thread that already exists. Computes deltas for comments
     * and score, only triggering a deep re-fetch if the comment count
     * increased. The thread is always added to the batch to update mutable
     * metadata (score, upvote ratio).
     */
    private void handleExistingThread(RedditThread thread, RedditThread existing,
            ScrapeStats stats, List<RedditThread> batchCollector) {
        int commentDiff = thread.numComments() - existing.numComments();
        int scoreDiff = thread.score() - existing.score();

        if (commentDiff > 0)
            stats.newComments += commentDiff;
        if (scoreDiff > 0)
            stats.newUpvotes += scoreDiff;
        if (commentDiff > 0 || scoreDiff > 0)
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
     * @return the modified text and a list of discovered image URLs
     */
    private ImageExtractionResult extractImages(String text, ThreadAnalysisContext context) {
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
        return new ImageExtractionResult(sb.toString(), imagesFound);
    }

    // =====================================================================
    // HTTP
    // =====================================================================

    /**
     * Executes a GET request with the Reddit User-Agent header and
     * automatic rate-limit handling. Every outgoing HTTP call flows
     * through this method to ensure consistent headers and backoff.
     */
    private HttpResponse<String> executeGet(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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
    private void checkRateLimit(HttpResponse<?> response) {
        response.headers().firstValue("x-ratelimit-remaining").ifPresent(remaining -> {
            try {
                double rem = Double.parseDouble(remaining);
                if (rem < 2.0) {
                    response.headers().firstValue("x-ratelimit-reset").ifPresent(reset -> {
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
     * Extracts the best image URL candidate from a thread's JSON data.
     * Prefers {@code url_overridden_by_dest} (user-submitted link) over
     * the generic {@code url} field (which may point to the Reddit thread
     * itself for self-posts).
     */
    private String resolveImageCandidate(JsonNode data) {
        String url = null;
        if (data.has("url_overridden_by_dest")) {
            url = unescapeHtml(data.get("url_overridden_by_dest").asText());
        } else if (data.has("url")) {
            url = unescapeHtml(data.get("url").asText());
        }
        return url;
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
    // User-Agent
    // =====================================================================

    /**
     * Builds the Reddit User-Agent string from the Maven-filtered version
     * property. Falls back to "unknown" if the properties file is missing
     * (e.g. during IDE-only runs without a Maven build).
     */
    private static String buildUserAgent() {
        String version = "unknown";
        try (InputStream in = RedditScraper.class.getResourceAsStream("/reddit-version.properties")) {
            if (in != null) {
                Properties props = new Properties();
                props.load(in);
                version = props.getProperty("app.version", "unknown");
            }
        } catch (IOException e) {
            // Fall back to "unknown" — a missing version must not prevent startup
        }
        return "java:de.bsommerfeld.wsbg.terminal:v" + version + " (by /u/WsbgTerminal)";
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

    /**
     * Aggregated context for a single thread, populated during a deep fetch.
     * Used by the AI agent for topic relevance analysis.
     *
     * <p>
     * Fields are intentionally mutable and public — this is a
     * builder-style accumulator that is populated incrementally during
     * recursive comment tree traversal, not a value object.
     */
    public static class ThreadAnalysisContext {
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

    /**
     * Accumulates delta statistics across one or more scrape cycles.
     *
     * <p>
     * Tracks the difference between what was fetched and what was
     * already cached: new threads, new upvotes (score increases), new
     * comments, and the full set of thread IDs that were examined. The
     * caller uses {@link #hasUpdates()} to decide whether a notification
     * or analysis cycle is warranted.
     */
    public static class ScrapeStats {
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
}
