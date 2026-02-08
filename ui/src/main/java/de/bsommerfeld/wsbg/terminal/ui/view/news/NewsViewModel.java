package de.bsommerfeld.wsbg.terminal.ui.view.news;

import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.reddit.RedditScraper;
import de.bsommerfeld.wsbg.terminal.db.DatabaseService;
import de.bsommerfeld.wsbg.terminal.agent.ChatService;
import jakarta.inject.Inject;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

@Singleton
public class NewsViewModel {

    private static final Logger LOG = LoggerFactory.getLogger(NewsViewModel.class);

    private final RedditScraper scraper;
    private final de.bsommerfeld.wsbg.terminal.db.RedditRepository repository; // Changed
    private final de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig config;
    private final ChatService chatService;
    private final de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus eventBus;

    // Observable List for UI
    private final ObservableList<RedditThread> threads = FXCollections.observableArrayList();

    private final java.util.concurrent.ScheduledExecutorService scheduler = java.util.concurrent.Executors
            .newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "NewsScheduler");
                t.setDaemon(true); // Ensure app exits
                return t;
            });

    @Inject
    public NewsViewModel(RedditScraper scraper, de.bsommerfeld.wsbg.terminal.db.RedditRepository repository,
            ChatService chatService,
            de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig config,
            de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus eventBus) {
        this.scraper = scraper;
        this.repository = repository; // Changed
        this.chatService = chatService;
        this.config = config;
        this.eventBus = eventBus;

        // Auto-Refresh every 60s
        scheduler.scheduleAtFixedRate(this::refreshNews, 0, 60, java.util.concurrent.TimeUnit.SECONDS);
    }

    public void refreshNews() {
        LOG.info("Refreshing News...");
        CompletableFuture.runAsync(() -> {
            try {
                RedditScraper.ScrapeStats totalStats = new RedditScraper.ScrapeStats();
                for (String sub : config.getReddit().getSubreddits()) {
                    totalStats.add(scraper.scanSubreddit(sub));
                }

                if (totalStats.hasUpdates()) {
                    String payload = totalStats.newThreads + "," + totalStats.newUpvotes + "," + totalStats.newComments;
                    eventBus.post(new de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.LogEvent(
                            payload, "REDDIT"));
                } else {
                    // Optional: Log no updates or just be silent
                    // eventBus.post(new
                    // de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.LogEvent("[REDDIT] No
                    // updates.", "INFO"));
                }

                // Increased limit to 500 to catch older threads with new activity
                var recent = repository.getRecentThreads(500);
                // Sorting is now handled by SQL (Order By Correctness) but list integrity is
                // key
                // recent.sort(...) is no longer strictly needed if SQL does it, but we can keep
                // a secondary sort or just rely on SQL.
                // SQL is ORDER BY last_activity_utc DESC.
                // But let's verify if we need Java sort. No, SQL is better.
                // However, we must ensure the list is mutable if we want to change it?
                // db.getRecentThreads returns ArrayList.

                // Re-enforcing sort just in case
                recent.sort((t1, t2) -> Long.compare(t2.getLastActivityUtc(), t1.getLastActivityUtc()));

                // Log the update
                final int finalCount = recent.size();
                eventBus.post(new de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.LogEvent(
                        "News Feed Updated: " + finalCount + " active threads loaded.", "INFO"));

                Platform.runLater(() -> {
                    threads.setAll(recent);
                });
            } catch (Exception e) {
                LOG.error("News Refresh Failed", e);
            }
        });
    }

    public void analyzeSentiment(RedditThread thread) {
        LOG.info("Analyzing thread: {}", thread.getTitle());
        CompletableFuture.runAsync(() -> {
            var context = scraper.fetchThreadContext(thread.getPermalink());
            String commentText = context.comments.isEmpty() ? "No comments found."
                    : String.join("\n", context.comments);

            java.util.Map<String, String> imagesToAnalyze = new java.util.LinkedHashMap<>();
            if (context.imageUrl != null) {
                imagesToAnalyze.put("THREAD_MAIN_IMAGE", context.imageUrl);
            }
            if (context.imageIdToUrl != null) {
                imagesToAnalyze.putAll(context.imageIdToUrl);
            }

            java.time.LocalDateTime now = java.time.LocalDateTime.now();
            String dateStr = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(now);

            StringBuilder basePrompt = new StringBuilder();
            basePrompt.append("SYSTEM_TIME: ").append(dateStr).append("\n");
            basePrompt.append("NOTE: You are live. Verify facts for ").append(now.getYear())
                    .append(". IGNORE training cutoff.\n\n");

            basePrompt.append("You are a financial analyst. Analyze this Reddit thread:\n\n");
            basePrompt.append("Title: ").append(thread.getTitle()).append("\n");
            basePrompt.append("Score: ").append(thread.getScore()).append(" (Upvote Ratio: ")
                    .append((int) (thread.getUpvoteRatio() * 100)).append("%)\n");
            String authorName = thread.getAuthor();
            if (authorName != null && !authorName.startsWith("u/") && !authorName.equals("unknown")
                    && !authorName.equals("[deleted]") && !authorName.equals("anon")) {
                authorName = "u/" + authorName;
            }
            basePrompt.append("Author: ").append(authorName).append("\n");
            basePrompt.append("Content: ").append(thread.getTextContent()).append("\n\n");

            if (!imagesToAnalyze.isEmpty()) {
                // Parallel analysis of all images (Limit to 3 to prevent overload)
                var futures = imagesToAnalyze.entrySet().stream()
                        .limit(3)
                        .map(entry -> {
                            String id = entry.getKey();
                            String url = entry.getValue();
                            return chatService.analyzeVision(url)
                                    .thenApply(desc -> "Image ID: " + id + "\nSource: " + url + "\nAnalysis: " + desc);
                        })
                        .collect(java.util.stream.Collectors.toList());

                final int finalImageCount = imagesToAnalyze.size(); // Effective final variable for lambda

                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).thenAccept(v -> {
                    String combinedVision = futures.stream()
                            .map(CompletableFuture::join)
                            .collect(java.util.stream.Collectors.joining("\n\n"));

                    // BROADCAST VISION RESULT TO UI
                    eventBus.post(new de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.LogEvent(
                            "Image Analysis Result (" + finalImageCount + " images processed with IDs):\n"
                                    + combinedVision,
                            "INFO"));

                    basePrompt.append("--- VISUAL CONTEXT ---\n").append(combinedVision).append("\n\n");

                    finishAndSend(basePrompt, commentText);
                });
            } else {
                finishAndSend(basePrompt, commentText);
            }
        });
    }

    private void finishAndSend(StringBuilder basePrompt, String commentText) {
        basePrompt.append("Top Comments (incl. replies):\n").append(commentText).append("\n\n");
        basePrompt.append("Task: \n");
        basePrompt.append(
                "1. INTERNAL LOGIC: Calculate 'Holistic Consensus' (Thread + Comment Scores) silently. Do NOT explain the math.\n");
        basePrompt.append(
                "   - Rule: High-score Comments (> Thread Score) OVERRIDE the Post's sentiment.\n");
        basePrompt.append(
                "   - Rule: Score 1 is Noise. Ignore.\n");
        basePrompt.append("2. OUTPUT: Provide a CONCISE Executive Summary (Max 3-4 sentences).\n");
        basePrompt.append("   - Focus only on the CONCLUSION (Bullish/Bearish/Neutral).\n");
        basePrompt.append(
                "   - Cite specific users, but DO NOT print the raw Score. Instead use phrases like 'Highly supported', 'Top comment', or 'Controversial' to reflect the score weight.\n");
        basePrompt.append("   - NO Wall of Text. NO 'Based on...'. Just the insight.\n");
        basePrompt.append(
                "   - **CRITICAL**: If '--- VISUAL CONTEXT ---' is present (charts/images), you MUST incorporate that data into your analysis.\n\n");

        basePrompt.append("Constraints:\n");
        basePrompt.append("- STRICT: Output ONLY the final summary text.\n");
        basePrompt.append(
                "- DO NOT output the raw 'Consensus Score', 'Gesamtkonsens', or any math. The score determines your TONE (e.g. 'Strongly Bullish'), not the output text.\n");
        basePrompt.append(
                "- **Visuals**: Integrate image/chart findings naturally into the story. Do NOT create a separate 'Visual Context' section. Say things like 'u/User shared a chart showing...'.\n");
        basePrompt.append("- Ensure all Reddit users are prefixed with 'u/'.\n");
        basePrompt.append("- NO Headers. Plain English.\n");

        chatService.analyzeText(basePrompt.toString());
    }

    public ObservableList<RedditThread> getThreads() {
        return threads;
    }
}
