package de.bsommerfeld.wsbg.terminal.data.reddit;

import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.db.DatabaseService;
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
 * Checks Delta against DB before processing.
 */
@Singleton
public class RedditScraper {

    private static final Logger LOG = LoggerFactory.getLogger(RedditScraper.class);
    private static final String REDDIT_BASE = "https://www.reddit.com";
    private final DatabaseService databaseService;
    private final HttpClient httpClient;

    @Inject
    public RedditScraper(DatabaseService databaseService) {
        this.databaseService = databaseService;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .build();
    }

    private static final String REDDIT_JSON_SUFFIX = ".json";
    private static final String USER_AGENT = "java:de.bsommerfeld.wsbg.terminal:v1.0 (by /u/WsbgTerminal)";

    public static class ThreadAnalysisContext {
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

        String url = REDDIT_BASE + permalink + REDDIT_JSON_SUFFIX;
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

                    // ... (Image extraction logic continues)

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

    private void processComments(JsonNode node, ThreadAnalysisContext context, int depth) {
        if (depth > 3)
            return; // Limit nesting depth
        if (node.has("data") && node.get("data").has("children")) {
            int count = 0;
            for (JsonNode child : node.get("data").get("children")) {
                // if (count > 10 && depth == 0) break; // Limit removed as per user request
                // (ALLE)

                JsonNode data = child.get("data");
                if (data.has("body")) {
                    String indent = "  ".repeat(depth);
                    String body = data.get("body").asText().replace("\n", " ");

                    // Extract and tag images
                    body = extractAndTagImages(body, context);

                    String rawAuthor = data.has("author") ? data.get("author").asText() : "anon";
                    int score = data.has("score") ? data.get("score").asInt() : 0;
                    String author = (!rawAuthor.equals("anon") && !rawAuthor.equals("[deleted]"))
                            ? "`u/" + rawAuthor + "`"
                            : rawAuthor;
                    context.comments.add(indent + author + " (Score: " + score + "): " + body);

                    if (data.has("replies") && data.get("replies").isObject()) {
                        processComments(data.get("replies"), context, depth + 1);
                    }
                    count++;
                }
            }
        }
    }

    private String extractAndTagImages(String text, ThreadAnalysisContext context) {
        // Regex to find http/https URLs (greedy matching to include query params)
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("https?://\\S+");
        java.util.regex.Matcher m = p.matcher(text);
        StringBuffer sb = new StringBuffer();

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
                context.imageIdToUrl.put(id, unescapedUrl);
                // Replace URL with URL + [Image: IMG_X] + original suffix
                m.appendReplacement(sb,
                        java.util.regex.Matcher.quoteReplacement(cleanUrl + " [Image: " + id + "]" + suffix));
            } else {
                // Not an image, keep original text (essentially a no-op replacement, but
                // advances buffer)
                m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(fullMatch));
            }
        }
        m.appendTail(sb);
        return sb.toString();
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

        public void add(ScrapeStats other) {
            this.newThreads += other.newThreads;
            this.newUpvotes += other.newUpvotes;
            this.newComments += other.newComments;
            this.threadUpdates.addAll(other.threadUpdates);
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
        LOG.info("Scanning r/{}", subreddit);
        String url = REDDIT_BASE + "/r/" + subreddit + "/new" + REDDIT_JSON_SUFFIX;
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
                for (JsonNode child : root.get("data").get("children")) {
                    JsonNode data = child.get("data");

                    String id = data.has("name") ? data.get("name").asText() : "unknown";
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

                    de.bsommerfeld.wsbg.terminal.core.domain.RedditThread thread = new de.bsommerfeld.wsbg.terminal.core.domain.RedditThread(
                            id, subreddit, title, author, selftext, created, permalink, score, upvoteRatio,
                            numComments);

                    // --- Calc Deltas ---
                    de.bsommerfeld.wsbg.terminal.core.domain.RedditThread existing = databaseService.getThread(id);
                    if (existing == null) {
                        // New Thread
                        stats.newThreads++;
                        stats.newComments += numComments;
                        stats.newUpvotes += score;
                        stats.threadUpdates.add(thread);
                        LOG.debug("New Thread found: {}", title);
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
                    }

                    databaseService.saveThread(thread);
                }
            }

        } catch (Exception e) {
            LOG.error("Error scraping subreddit {}", subreddit, e);
        }
        return stats;
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
