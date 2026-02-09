package de.bsommerfeld.wsbg.terminal.reddit;

import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.inject.Inject;
import java.net.http.HttpClient;
import java.io.IOException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * Reddit Scraper utilizing .json endpoints (No API Key).
 * Enforces Rate Limiting to prevent 429s.
 * Checks Delta against Cache/DB before processing.
 */
@Singleton
public class RedditScraper {

    private static final Logger LOG = LoggerFactory.getLogger(RedditScraper.class);
    private static final String REDDIT_BASE = "https://www.reddit.com";
    private final RedditRepository repository;
    private final HttpClient httpClient;

    @Inject
    public RedditScraper(RedditRepository repository) {
        this.repository = repository;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .build();
    }

    private static final String REDDIT_JSON_SUFFIX = ".json";
    private static final String USER_AGENT = "java:de.bsommerfeld.wsbg.terminal:v1.0 (by /u/WsbgTerminal)";

    public static class ThreadAnalysisContext {
        public String threadId; // Added Thread ID for DB linking
        public String title; // Added Title
        public String imageUrl;
        public String selftext;
        public java.util.Map<String, String> imageIdToUrl = new java.util.LinkedHashMap<>();
        public java.util.List<String> comments = new java.util.ArrayList<>();
        public int imageCounter = 1;

        public boolean isEmpty() {
            return (title == null || title.isEmpty()) &&
                    (selftext == null || selftext.isEmpty()) &&
                    comments.isEmpty();
        }
    }

    public ThreadAnalysisContext fetchThreadContext(String permalink) {
        ThreadAnalysisContext context = new ThreadAnalysisContext();

        if (permalink == null || permalink.isEmpty()) {
            LOG.error("Cannot fetch context: Permalink is empty");
            return context;
        }

        if (permalink.endsWith("/"))
            permalink = permalink.substring(0, permalink.length() - 1);

        // Ensure permalink starts with / if not present (defensive)
        if (!permalink.startsWith("/"))
            permalink = "/" + permalink;

        String url = REDDIT_BASE + permalink + REDDIT_JSON_SUFFIX + "?limit=500&depth=20";
        LOG.info("Fetching Thread Context: {}", url);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            checkRateLimit(response);

            if (response.statusCode() == 200) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(response.body());

                if (root.isArray() && root.size() > 0) {
                    // Extract Title & Data (Index 0)
                    JsonNode tData = root.get(0).get("data").get("children").get(0).get("data");

                    if (tData.has("name")) {
                        context.threadId = tData.get("name").asText();
                    } else if (tData.has("id")) {
                        context.threadId = "t3_" + tData.get("id").asText();
                    }

                    if (tData.has("title")) {
                        context.title = tData.get("title").asText();
                    }

                    if (tData.has("selftext")) {
                        context.selftext = tData.get("selftext").asText();
                    }

                    // Fallback for link posts with no selftext
                    if ((context.selftext == null || context.selftext.isEmpty())
                            && tData.has("url_overridden_by_dest")) {
                        context.selftext = "[Link: " + tData.get("url_overridden_by_dest").asText() + "]";
                    }

                    String candidateUrl = null;

                    if (tData.has("url_overridden_by_dest")) {
                        candidateUrl = unescapeUrl(tData.get("url_overridden_by_dest").asText());
                    } else if (tData.has("url")) {
                        candidateUrl = unescapeUrl(tData.get("url").asText());
                    }

                    if (candidateUrl != null && isImage(candidateUrl)) {
                        context.imageUrl = candidateUrl;
                    }

                    // Extract Comments (Index 1)
                    if (root.size() > 1) {
                        processComments(root.get(1), context, 0);
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to fetch context for {}", permalink, e);
        }
        return context;
    }

    private static class ImageExtractionResult {
        String text;
        java.util.List<String> images;

        public ImageExtractionResult(String text, java.util.List<String> images) {
            this.text = text;
            this.images = images;
        }
    }

    private void processComments(JsonNode node, ThreadAnalysisContext context, int depth) {
        if (depth > 8)
            return; // Limit nesting depth
        if (node.has("data") && node.get("data").has("children")) {
            int count = 0;
            for (JsonNode child : node.get("data").get("children")) {
                JsonNode data = child.get("data");
                if (data.has("body")) {
                    String indent = "  ".repeat(depth);
                    String rawBody = data.get("body").asText().replace("\n", " ");

                    // Extract and tag images locally for this comment
                    ImageExtractionResult searchResult = extractImages(rawBody, context);
                    String body = searchResult.text;

                    String rawAuthor = data.has("author") ? data.get("author").asText() : "anon";
                    int score = data.has("score") ? data.get("score").asInt() : 0;
                    String author = (!rawAuthor.equals("anon") && !rawAuthor.equals("[deleted]"))
                            ? "`u/" + rawAuthor + "`"
                            : rawAuthor;
                    context.comments.add(indent + author + " (Score: " + score + "): " + body);

                    // --- Save Comment to DB ---
                    String id = data.has("name") ? data.get("name").asText() : "unknown_" + java.util.UUID.randomUUID();
                    String parentId = data.has("parent_id") ? data.get("parent_id").asText() : context.threadId;
                    long createdUtc = data.has("created_utc") ? data.get("created_utc").asLong()
                            : System.currentTimeMillis() / 1000;
                    long now = System.currentTimeMillis() / 1000;

                    de.bsommerfeld.wsbg.terminal.core.domain.RedditComment dbComment = new de.bsommerfeld.wsbg.terminal.core.domain.RedditComment(
                            id,
                            context.threadId != null ? context.threadId : "unknown",
                            parentId,
                            rawAuthor,
                            body,
                            score,
                            createdUtc,
                            now, // fetched_at
                            now, // last_updated_utc (assume fresh activity if we are fetching it)
                            searchResult.images // Pass found images
                    );
                    repository.saveComment(dbComment);
                    // ---------------------------

                    if (data.has("replies") && data.get("replies").isObject()) {
                        processComments(data.get("replies"), context, depth + 1);
                    }
                    count++;
                }
            }
        }
    }

    private ImageExtractionResult extractImages(String text, ThreadAnalysisContext context) {
        // Regex to find http/https URLs (greedy matching to include query params)
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("https?://\\S+");
        java.util.regex.Matcher m = p.matcher(text);
        StringBuffer sb = new StringBuffer();
        java.util.List<String> imagesFound = new java.util.ArrayList<>();

        while (m.find()) {
            String fullMatch = m.group();
            String cleanUrl = fullMatch;

            // Trim common trailing punctuation often caught by \\S
            while (cleanUrl.endsWith(")") || cleanUrl.endsWith("]") || cleanUrl.endsWith(".") || cleanUrl.endsWith(",")
                    || cleanUrl.endsWith(";")) {
                cleanUrl = cleanUrl.substring(0, cleanUrl.length() - 1);
            }

            String suffix = fullMatch.substring(cleanUrl.length()); // Build suffix to restore punctuation

            // Unescape HTML entities (fixes &amp; issues in Reddit URLs)
            String unescapedUrl = unescapeUrl(cleanUrl);

            if (isImage(unescapedUrl)) {
                String id = "IMG_" + (context.imageCounter++);
                // context.imageIdToUrl.put(id, unescapedUrl); // Can still populate context if
                // needed for legacy, or remove
                imagesFound.add(unescapedUrl);

                // Replace URL with URL + [Image: IMG_X] + original suffix
                // Or just [Image] marker? The UI will pick it up from DB.
                // Converting to marker helps text analysis know there is an image.
                m.appendReplacement(sb,
                        java.util.regex.Matcher.quoteReplacement(cleanUrl + " [Image]" + suffix));
            } else {
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(fullMatch));
            }
        }
        m.appendTail(sb);
        return new ImageExtractionResult(sb.toString(), imagesFound);
    }

    private String unescapeUrl(String url) {
        if (url == null)
            return null;
        return url.replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");
    }

    private boolean isImage(String url) {
        if (url == null)
            return false;
        String lower = url.toLowerCase();
        // Support common image formats
        return lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png") || lower.contains(".webp")
                || lower.contains(".gif");
    }

    /**
     * Deprecated: Use fetchThreadContext instead.
     */
    public java.util.List<String> fetchThreadComments(String permalink) {
        return fetchThreadContext(permalink).comments;
    }

    public static class ScrapeStats {
        public int newThreads = 0;
        public int newUpvotes = 0;
        public int newComments = 0;
        public java.util.List<de.bsommerfeld.wsbg.terminal.core.domain.RedditThread> threadUpdates = new java.util.ArrayList<>();
        public java.util.Set<String> scannedIds = new java.util.HashSet<>();

        public void add(ScrapeStats other) {
            this.newThreads += other.newThreads;
            this.newUpvotes += other.newUpvotes;
            this.newComments += other.newComments;
            this.threadUpdates.addAll(other.threadUpdates);
            this.scannedIds.addAll(other.scannedIds);
        }

        public boolean hasUpdates() {
            return newThreads > 0 || newUpvotes > 0 || newComments > 0;
        }

        @Override
        public String toString() {
            return String.format("%d new threads, %d new upvotes, %d new comments", newThreads, newUpvotes,
                    newComments);
        }
    }

    public ScrapeStats scanSubreddit(String subreddit) {
        return scanSubredditListing(subreddit, "new");
    }

    public ScrapeStats scanSubredditHot(String subreddit) {
        return scanSubredditListing(subreddit, "hot");
    }

    private ScrapeStats scanSubredditListing(String subreddit, String listing) {
        LOG.info("Scanning r/{}/{}", subreddit, listing);
        String url = REDDIT_BASE + "/r/" + subreddit + "/" + listing + REDDIT_JSON_SUFFIX + "?limit=50";
        ScrapeStats stats = new ScrapeStats();

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            checkRateLimit(response);

            if (response.statusCode() != 200) {
                LOG.error("Failed to fetch subreddit data: HTTP {}", response.statusCode());
                return stats;
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(response.body());

            if (root.has("data") && root.get("data").has("children")) {
                java.util.List<de.bsommerfeld.wsbg.terminal.core.domain.RedditThread> batchToSave = new java.util.ArrayList<>();
                for (JsonNode child : root.get("data").get("children")) {
                    processThreadNode(child, stats, subreddit, batchToSave);
                }
                // Batch Save!
                if (!batchToSave.isEmpty()) {
                    repository.saveThreadsBatch(batchToSave);
                }
            }

        } catch (Exception e) {
            LOG.error("Error scraping subreddit {}", subreddit, e);
        }
        return stats;
    }

    /**
     * Efficiently updates a list of known thread IDs using batch requests.
     */
    public ScrapeStats updateThreadsBatch(java.util.List<String> threadIds) {
        ScrapeStats stats = new ScrapeStats();
        if (threadIds == null || threadIds.isEmpty())
            return stats;

        // Dedup IDs
        java.util.Set<String> uniqueIds = new java.util.HashSet<>(threadIds);
        java.util.List<String> distinctIds = new java.util.ArrayList<>(uniqueIds);

        int batchSize = 100;
        for (int i = 0; i < distinctIds.size(); i += batchSize) {
            int end = Math.min(distinctIds.size(), i + batchSize);
            java.util.List<String> batch = distinctIds.subList(i, end);

            // Ensure IDs have t3_ prefix if missing (Link IDs)
            String idsParam = batch.stream()
                    .map(id -> id.startsWith("t3_") ? id : "t3_" + id)
                    .collect(java.util.stream.Collectors.joining(","));

            String url = REDDIT_BASE + "/by_id/" + idsParam + REDDIT_JSON_SUFFIX;
            LOG.debug("Batch Updating {} threads...", batch.size());

            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", USER_AGENT)
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                checkRateLimit(response);

                if (response.statusCode() == 200) {
                    ObjectMapper mapper = new ObjectMapper();
                    JsonNode root = mapper.readTree(response.body());
                    if (root.has("data") && root.get("data").has("children")) {
                        java.util.List<de.bsommerfeld.wsbg.terminal.core.domain.RedditThread> batchToSave = new java.util.ArrayList<>();
                        for (JsonNode child : root.get("data").get("children")) {
                            String sub = "unknown";
                            if (child.get("data").has("subreddit")) {
                                sub = child.get("data").get("subreddit").asText();
                            }
                            processThreadNode(child, stats, sub, batchToSave);
                        }
                        // Batch Save!
                        if (!batchToSave.isEmpty()) {
                            repository.saveThreadsBatch(batchToSave);
                        }
                    }
                } else {
                    LOG.warn("Batch update failed (HTTP {}): {}", response.statusCode(), url);
                }
            } catch (Exception e) {
                LOG.error("Error during batch update", e);
            }
        }
        return stats;
    }

    private void processThreadNode(JsonNode child, ScrapeStats stats, String subredditDefault,
            java.util.List<de.bsommerfeld.wsbg.terminal.core.domain.RedditThread> batchCollector) {
        try {
            JsonNode data = child.get("data");

            String id = data.has("name") ? data.get("name").asText() : "unknown";

            // --- BLACKLIST FILTER ---
            // ID: t3_nwvkto (Willkommen im r/wallstreetbetsGER LiveChat!)
            // Reason: Massive history, low signal-to-noise ratio for real-time alerts.
            if (id.equals("t3_nwvkto") || id.contains("nwvkto")) {
                LOG.debug("Skipping Blacklisted Thread: {}", id);
                return;
            }

            stats.scannedIds.add(id);

            String subreddit = data.has("subreddit") ? data.get("subreddit").asText() : subredditDefault;
            String title = data.has("title") ? data.get("title").asText() : "No Title";
            String rawAuthor = data.has("author") ? data.get("author").asText() : "unknown";
            String author = (!rawAuthor.equals("unknown") && !rawAuthor.equals("[deleted]")) ? "u/" + rawAuthor
                    : rawAuthor;

            String selftext = data.has("selftext") ? data.get("selftext").asText() : "";
            if (selftext.isEmpty()) {
                if (data.has("url_overridden_by_dest")) {
                    selftext = "[Link: " + data.get("url_overridden_by_dest").asText() + "]";
                } else if (data.has("url")) {
                    selftext = "[Link: " + data.get("url").asText() + "]";
                }
            }

            long created = data.has("created_utc") ? data.get("created_utc").asLong() : 0;
            String permalink = data.has("permalink") ? data.get("permalink").asText() : "";
            int score = data.has("score") ? data.get("score").asInt() : 0;
            double upvoteRatio = data.has("upvote_ratio") ? data.get("upvote_ratio").asDouble() : 0.0;
            int numComments = data.has("num_comments") ? data.get("num_comments").asInt() : 0;

            String imageUrl = null;
            String candidateUrl = null;
            if (data.has("url_overridden_by_dest")) {
                candidateUrl = data.get("url_overridden_by_dest").asText();
            } else if (data.has("url")) {
                candidateUrl = data.get("url").asText();
            }
            if (candidateUrl != null) {
                String lower = candidateUrl.toLowerCase();
                if (lower.contains(".jpg") || lower.contains(".jpeg") || lower.contains(".png")
                        || lower.contains(".webp")) {
                    imageUrl = candidateUrl;
                }
            }

            long now = System.currentTimeMillis() / 1000;

            // Resolve Last Activity
            de.bsommerfeld.wsbg.terminal.core.domain.RedditThread existing = repository.getThread(id);
            long resolvedActivityUtc = created;

            if (existing != null) {
                resolvedActivityUtc = existing.getLastActivityUtc();
                // Check if we have new comments (Activity)
                if (numComments > existing.getNumComments()) {
                    resolvedActivityUtc = now; // Update to recent time
                }
            } else {
                // New thread: defaulted to created.
            }

            de.bsommerfeld.wsbg.terminal.core.domain.RedditThread thread = new de.bsommerfeld.wsbg.terminal.core.domain.RedditThread(
                    id, subreddit, title, author, selftext, created, permalink, score, upvoteRatio,
                    numComments, resolvedActivityUtc, imageUrl);

            // --- Calc Deltas & Fetch Deep ---
            if (existing == null) {
                // New Thread
                stats.newThreads++;
                stats.newComments += numComments;
                stats.newUpvotes += score;
                stats.threadUpdates.add(thread);
                LOG.debug("New Thread found: {}", title);

                // Add to batch for saving
                batchCollector.add(thread);

                // Fetch full context (comments/images) immediately for new threads.
                // Note: Fetching context is still sequential/individual as required by API
                // structure
                LOG.info("Fetching full context for new thread: {}", id);
                fetchThreadContext(permalink);

            } else {
                // Existing Thread - Check diffs
                int commentDiff = numComments - existing.getNumComments();
                int scoreDiff = score - existing.getScore();
                boolean updated = false;

                if (commentDiff > 0) {
                    stats.newComments += commentDiff;
                    updated = true;
                }

                if (scoreDiff > 0) {
                    stats.newUpvotes += scoreDiff;
                    updated = true;
                }

                if (updated) {
                    stats.threadUpdates.add(thread);
                }

                if (commentDiff > 0) {
                    LOG.info("Context Update: {} ({})\n +{} comments, Score: {}", title, id, commentDiff, score);
                    fetchThreadContext(permalink);
                }

                // Add to batch for saving (Metadata Update)
                batchCollector.add(thread);
            }

        } catch (Exception e) {
            LOG.error("Error processing thread node", e);
        }
    }

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
                // Ignore parsing errors
            }
        });
    }
}
