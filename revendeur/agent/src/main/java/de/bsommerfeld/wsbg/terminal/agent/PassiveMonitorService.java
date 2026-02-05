package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.data.reddit.RedditScraper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.store.embedding.CosineSimilarity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import de.bsommerfeld.wsbg.terminal.core.i18n.I18nService;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Passive Monitor Service (Time-Aware).
 * 
 * Capability:
 * - Vector Clustering: Groups related threads.
 * - Temporal Velocity: Detects "Heat" by analyzing the RATE of new content
 * (Threads/Comments per minute).
 * - Algorithmic Significance: Uses a weighted scoring system (Significance
 * Score) factoring in
 * Thread Count, Total Upvotes, and Keyword Volatility to decide when to wake
 * the AI.
 */
@Singleton
public class PassiveMonitorService {

    private static final Logger LOG = LoggerFactory.getLogger(PassiveMonitorService.class);
    private final RedditScraper scraper;
    private final AgentBrain brain;
    private final ApplicationEventBus eventBus;
    private final de.bsommerfeld.wsbg.terminal.db.DatabaseService db;
    private final I18nService i18n;

    // Vector Model (Ollama)
    private final EmbeddingModel embeddingModel;

    // Execution
    private final ScheduledExecutorService scannerExecutor = Executors.newSingleThreadScheduledExecutor();
    private final java.util.concurrent.ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();

    // Memory: Active Investigations
    private final List<Investigation> investigations = new CopyOnWriteArrayList<>();

    // Tracking Global State for Deltas
    private final java.util.Map<String, Integer> lastSeenScore = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.Map<String, Integer> lastSeenComments = new java.util.concurrent.ConcurrentHashMap<>();
    private Instant lastCleanup = Instant.MIN;

    // Users Model Preference
    // private static final String EMBEDDING_MODEL_NAME =
    // "nomic-embed-text-v2-moe:latest";
    private static final String OLLAMA_URL = "http://localhost:11434";

    // Config
    private static final double SIMILARITY_THRESHOLD = 0.55;
    private static final Duration INVESTIGATION_TTL = Duration.ofMinutes(60);

    // Significance Thresholds (The "Wake Up" Levels)
    private static final double SIGNIFICANCE_THRESHOLD_REPORT = 10.0; // Raised from 30.0 to reduce noise

    // Cleanup Config
    private static final Duration CLEANUP_INTERVAL = Duration.ofHours(1);
    private static final long DATA_RETENTION_SECONDS = 6 * 3600;

    @Inject
    public PassiveMonitorService(RedditScraper scraper, AgentBrain brain, ApplicationEventBus eventBus,
            de.bsommerfeld.wsbg.terminal.db.DatabaseService db,
            de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig config,
            I18nService i18n) {
        this.scraper = scraper;
        this.brain = brain;
        this.eventBus = eventBus;
        this.db = db;
        this.i18n = i18n;

        String embeddingModelName = config.getAgent().getEmbeddingModel();

        LOG.info("Initializing Vector Embedding Model (Ollama: {})...", embeddingModelName);

        this.embeddingModel = OllamaEmbeddingModel.builder()
                .baseUrl(OLLAMA_URL)
                .modelName(embeddingModelName)
                .timeout(Duration.ofSeconds(60))
                .build();

        startMonitoring();
    }

    private void startMonitoring() {
        LOG.info("Starting Passive Reddit Monitor (Weighted Significance Logic)...");
        scannerExecutor.scheduleAtFixedRate(this::scanCycle, 30, 60, TimeUnit.SECONDS);
    }

    private void scanCycle() {
        try {
            if (Duration.between(lastCleanup, Instant.now()).compareTo(CLEANUP_INTERVAL) > 0) {
                int deleted = db.cleanupOldThreads(DATA_RETENTION_SECONDS);
                lastCleanup = Instant.now();
                eventBus.post(new de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.LogEvent(
                        String.valueOf(deleted), "CLEANUP"));
            }

            RedditScraper.ScrapeStats stats = scraper.scanSubreddit("wallstreetbetsGER");

            // Fetch updates from ScrapeStats to pass to analysis
            List<RedditThread> updates = stats.threadUpdates;

            // Always run analysis if we have active high-heat investigations, even if no
            // *new* threads came in
            // (e.g. comments/scores updating on existing ones)
            if (updates.isEmpty() && investigations.isEmpty()) {
                return;
            }
            analysisExecutor.submit(() -> processUpdates(updates));
        } catch (Exception e) {
            LOG.error("Passive Monitor Scan Failed", e);
        }
    }

    private void processUpdates(List<RedditThread> updates) {
        try {
            boolean meaningfulChange = false;

            for (RedditThread t : updates) {
                // Determine Deltas
                int prevScore = lastSeenScore.getOrDefault(t.getId(), t.getScore());
                int deltaScore = t.getScore() - prevScore;
                lastSeenScore.put(t.getId(), t.getScore());

                int prevComments = lastSeenComments.getOrDefault(t.getId(), t.getNumComments());
                int deltaComments = t.getNumComments() - prevComments;
                lastSeenComments.put(t.getId(), t.getNumComments());

                if (deltaScore > 0 || deltaComments > 0) {
                    LOG.info("[INTERNAL][REDDIT] Update for '{}': +{} Score, +{} Comments (Total: {}/{})",
                            t.getTitle(), deltaScore, deltaComments, t.getScore(), t.getNumComments());
                }

                // KEYWORD CHECK (Bonus Weight)
                String titleUpper = t.getTitle().toUpperCase();
                boolean isKeyword = titleUpper.contains("EARNINGS") ||
                        titleUpper.contains("QUARTALSZAHLEN") ||
                        titleUpper.contains("BERICHT") ||
                        titleUpper.contains("FED") ||
                        titleUpper.contains("ZINSEN") ||
                        titleUpper.contains("INFLATION") ||
                        titleUpper.contains("CPI") ||
                        titleUpper.contains("EZB") ||
                        titleUpper.contains("CRASH") ||
                        titleUpper.contains("INSOLVENZ") ||
                        titleUpper.contains("WAR") ||
                        titleUpper.contains("KRIEG");

                String content = t.getTitle() + " " + (t.getTextContent() != null ? t.getTextContent() : "");

                Embedding threadEmbedding = embeddingModel.embed(content).content();

                // Match Logic
                Investigation bestMatch = null;
                double bestScore = -1.0;

                for (Investigation inv : investigations) {
                    double score = CosineSimilarity.between(threadEmbedding, inv.centroid);
                    if (score > bestScore) {
                        bestScore = score;
                        bestMatch = inv;
                    }
                }

                if (bestMatch != null && bestScore >= SIMILARITY_THRESHOLD) {
                    // Update: Only add event if something actually changed
                    if (deltaScore > 0 || deltaComments > 0) {
                        bestMatch.addUpdate(t, deltaScore, deltaComments);
                        if (isKeyword)
                            bestMatch.keywordBonus = true;
                        meaningfulChange = true;
                        LOG.info("UPDATE '{}': +{} Score, +{} Comments", bestMatch.initialTitle, deltaScore,
                                deltaComments);
                    }
                } else {
                    // New Investigation
                    Investigation newInv = new Investigation(t, threadEmbedding);
                    if (isKeyword)
                        newInv.keywordBonus = true;
                    investigations.add(newInv);
                    meaningfulChange = true;
                    LOG.info("NEW Investigation: '{}'", t.getTitle());
                }
            }

            pruneInvestigations();

            if (meaningfulChange || !investigations.isEmpty()) {
                analyzeInvestigations();
            }

        } catch (Exception e) {
            LOG.error("Passive Monitor Processing Failed", e);
        }
    }

    private void pruneInvestigations() {
        Instant now = Instant.now();
        Iterator<Investigation> it = investigations.iterator();
        while (it.hasNext()) {
            Investigation inv = it.next();
            if (Duration.between(inv.lastActivity, now).compareTo(INVESTIGATION_TTL) > 0) {
                investigations.remove(inv);
            }
        }
    }

    private void analyzeInvestigations() {
        List<Investigation> candidates = new ArrayList<>();

        for (Investigation inv : investigations) {
            if (inv.reported)
                continue;

            // --- WEIGHTED SIGNIFICANCE SCORE (The "AI Logic" Formula) ---
            double score = 0.0;
            score += inv.threadCount * 12.0;
            score += inv.totalComments * 2.5;
            score += inv.totalScore * 0.4;
            if (inv.keywordBonus)
                score += 50.0;
            long eventsLast5Min = inv.countRecentEvents(Duration.ofMinutes(5));
            score += eventsLast5Min * 5.0;

            inv.currentSignificance = score;

            LOG.info(
                    "[INTERNAL][AI-REASONING] Topic '{}' Score: {:.2f} (Threads: {} | Comments: {} | BaseScore: {} | Velocity: {}) -> Threshold: {}",
                    inv.initialTitle, score, inv.threadCount, inv.totalComments, inv.totalScore, eventsLast5Min,
                    SIGNIFICANCE_THRESHOLD_REPORT);

            if (score >= SIGNIFICANCE_THRESHOLD_REPORT) {
                candidates.add(inv);
            }
        }

        if (candidates.isEmpty())
            return;

        // Process EACH investigation separately to ensure strictly scoped context
        for (Investigation inv : candidates) {
            // Smart Update Logic: We allow re-scanning with history awareness
            boolean isUpdate = inv.reported;

            // Build History Block
            StringBuilder historyBlock = new StringBuilder();
            if (inv.reportHistory.isEmpty()) {
                historyBlock.append("NONE");
            } else {
                for (String h : inv.reportHistory) {
                    historyBlock.append("- ").append(h).append("\n");
                }
            }

            StringBuilder reportData = new StringBuilder();
            reportData.append("--- CASE ID: ").append(inv.id).append(" ---\n");
            reportData.append("Cluster Topic: ").append(inv.initialTitle).append("\n");

            // Age Calculation
            String ageStr = "Unknown";
            if (inv.threadCreatedUTC > 0) {
                long minutesOld = Duration.between(Instant.ofEpochSecond(inv.threadCreatedUTC), Instant.now())
                        .toMinutes();
                long hours = minutesOld / 60;
                long mins = minutesOld % 60;
                ageStr = String.format("%dh %dm", hours, mins);
            }
            reportData.append("Cluster Age: ").append(ageStr).append("\n");
            reportData.append("Significance Score: ").append(String.format("%.1f", inv.currentSignificance))
                    .append("\n");
            reportData.append("Active Threads: ").append(inv.activePermalinks.size()).append("\n\n");

            // --- DEEP DIVE: Aggregate Context from Top Active Threads ---
            // We select up to 3 permalinks to fetch, prioritizing the "best" and "latest"
            // This allows us to catch comments in Daily Threads or breaking news across
            // duplicate posts.
            java.util.Set<String> targets = new java.util.HashSet<>();
            if (inv.bestPermalink != null)
                targets.add(inv.bestPermalink);
            if (inv.latestPermalink != null)
                targets.add(inv.latestPermalink);

            int count = 0;
            for (String link : inv.activePermalinks) {
                if (count >= 3)
                    break;
                targets.add(link);
                count++;
            }

            int threadIndex = 1;
            for (String permalink : targets) {
                try {
                    var context = scraper.fetchThreadContext(permalink);
                    if (context.isEmpty())
                        continue;

                    reportData.append("=== THREAD SOURCE ").append(threadIndex++).append(" ===\n");
                    reportData.append("Title: ").append(context.title).append("\n");

                    if (context.imageUrl != null && !context.imageUrl.isEmpty()) {
                        reportData.append(" [MAIN IMAGE]: ").append(context.imageUrl).append("\n");

                        // Active Vision Analysis during Passive Scan
                        try {
                            LOG.info("[INTERNAL][VISION] Passive Scan invoking Vision Model on: {}", context.imageUrl);
                            String visionResult = brain.see(context.imageUrl);
                            LOG.info("[INTERNAL][VISION] Result: {}", visionResult);
                            reportData.append("[VISION ANALYSIS]: ").append(visionResult).append("\n");
                        } catch (Exception e) {
                            LOG.error("[INTERNAL][VISION] Failed: {}", e.getMessage());
                        }
                    }

                    if (context.selftext != null && !context.selftext.isEmpty()) {
                        String snippet = context.selftext.length() > 500 ? context.selftext.substring(0, 500) + "..."
                                : context.selftext;
                        reportData.append("Content Snippet: ").append(snippet).append("\n");
                    }

                    if (!context.comments.isEmpty()) {
                        reportData.append("RELEVANT COMMENTS (Nested):\n");
                        context.comments.stream().limit(15).forEach(c -> reportData.append(c).append("\n"));
                    }

                    // Images in Comments
                    if (!context.imageIdToUrl.isEmpty()) {
                        reportData.append("IMAGES IN COMMENTS:\n");
                        for (java.util.Map.Entry<String, String> entry : context.imageIdToUrl.entrySet()) {
                            reportData.append(" - ").append(entry.getKey()).append(": ").append(entry.getValue())
                                    .append("\n");
                        }
                    }
                    reportData.append("\n");

                } catch (Exception e) {
                    LOG.warn("Failed to fetch deep-dive context for {}", permalink);
                }
            }

            reportData.append("-----------------------------\n\n");

            // Living Context: ACCUMULATE context instead of overwriting
            // We append the new snapshot with a timestamp
            java.time.LocalTime now = java.time.LocalTime.now();
            String timeStr = String.format("[%02d:%02d]", now.getHour(), now.getMinute());

            if (inv.cachedContext == null) {
                inv.cachedContext = "";
            }

            // Limit context size to prevent explosion (keep last ~4000 chars + new)
            if (inv.cachedContext.length() > 4000) {
                inv.cachedContext = inv.cachedContext.substring(inv.cachedContext.length() - 4000);
            }

            // Append new update block
            String newBlock = "\n\n=== UPDATE " + timeStr + " ===\n" + reportData.toString();
            // Note: We don't commit to inv.cachedContext yet, we use the combined view for
            // prompting
            String combinedContext = inv.cachedContext + newBlock;

            // Store strictly scoped context
            inv.cachedContext = reportData.toString();

            String prompt = "You are a Real-Time Market Intelligence AI.\n" +
                    "Generate a SINGLE-LINE, STRICTLY FACTUAL financial headline from this CLUSTER of data.\n" +
                    "REPORT HISTORY (Past Alerts):\n" + historyBlock.toString() + "\n" +
                    "DATA (Chronological Updates):\n" + combinedContext + "\n" +
                    "RULES:\n" +
                    "1. SYNTHESIZE: Look for signals across ALL threads/comments.\n" +
                    "   - A headline can emerge from a single highly-upvoted COMMENT, even if the post is generic.\n" +
                    "   - Pay attention to nested comments (replies).\n" +
                    "2. MAX 15 WORDS. Concise.\n" +
                    "3. STYLE: Objective & Direct. Focus on the CONTENT, not the poster.\n" +
                    "   - BAD: 'User predicts crash', 'Poster asks about potential dip'.\n" +
                    "   - GOOD: 'Market crash predicted for tomorrow', 'Inquiry regarding potential dip'.\n" +
                    "4. NO USERNAMES in headlines. NEVER.\n" +
                    "5. CLASSIFY: [LOW] (Noise), [MED], [HIGH] (Breaking).\n" +
                    "5. FILTER: Output strictly -1 (Do NOT use 'REPORT:' format) IF:\n" +
                    "   - Content is Noise/Memes/Jokes.\n" +
                    "   - Situation is unchanged from HISTORY.\n" +
                    "   - Situation is just flipping back and forth (Loop/Volatility) without NEW CONFIRMED FACTS.\n"
                    +
                    "6. FORMAT: 'REASONING: [Your internal thought process]\\nREPORT: [PRIORITY] [Headline]' OR output '-1' if noise.\n";

            LOG.info("[INTERNAL][AI-PROMPT] Generating Headline for '{}'. Context Length: {} chars. History: {}",
                    inv.initialTitle, combinedContext.length(), historyBlock);

            String response = collectStreamBlocking(brain.ask("passive-monitor", prompt)).trim();
            LOG.info("[INTERNAL][AI-RAW-RESPONSE] >>>\n{}", response);

            // Extract Reasoning
            String reasoning = "";
            if (response.contains("REASONING:")) {
                int start = response.indexOf("REASONING:") + 10;
                int end = response.indexOf("REPORT:");
                if (end == -1)
                    end = response.length();
                reasoning = response.substring(start, end).trim();
            } else if (!response.contains("REPORT:")) {
                // If no report, maybe the whole thing is reasoning or -1
                reasoning = response;
            }

            LOG.info("[INTERNAL][AI-THOUGHT] Context: {} chars | Reasoning: {}", combinedContext.length(), reasoning);

            if (response.equals("-1") || response.contains("-1")) {
                // Silence / Noise
                continue;
            }

            if (response.contains("REPORT:")) {
                String coreReport = "";
                for (String line : response.split("\n")) {
                    if (line.trim().startsWith("REPORT:")) {
                        coreReport = line.substring(line.indexOf(":") + 1).trim();
                        break;
                    }
                }

                if (!coreReport.isEmpty() && !coreReport.equals("-1") && !coreReport.contains("-1")) {
                    inv.reported = true;

                    // Add to History (Max 5 entries)
                    java.time.LocalTime nowTime = java.time.LocalTime.now();
                    String timeLabel = String.format("[%02d:%02d]", nowTime.getHour(), nowTime.getMinute());
                    inv.reportHistory.add(timeLabel + " " + coreReport);
                    if (inv.reportHistory.size() > 5) {
                        inv.reportHistory.remove(0);
                    }

                    // Commit the new context block now that we have a valid report/update
                    inv.cachedContext = combinedContext;

                    // Stream Translation
                    dev.langchain4j.service.TokenStream translationStream = brain.translate(coreReport, "English", "en",
                            "German", "de");

                    if (translationStream != null) {
                        eventBus.post(new de.bsommerfeld.wsbg.terminal.agent.ChatService.AgentStreamStartEvent(
                                i18n.get("log.source.passive_agent"), "source-AI"));
                        StringBuilder germanBuilder = new StringBuilder();
                        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

                        translationStream.onNext(token -> {
                            germanBuilder.append(token);
                            eventBus.post(new de.bsommerfeld.wsbg.terminal.agent.ChatService.AgentTokenEvent(token));
                        }).onComplete(res -> latch.countDown()).onError(ex -> latch.countDown()).start();

                        try {
                            latch.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }

                        // Attach specific ID
                        String fullPayload = "||PASSIVE||" + germanBuilder.toString() + "||REF||ID:" + inv.id;
                        eventBus.post(
                                new de.bsommerfeld.wsbg.terminal.agent.ChatService.AgentStreamEndEvent(fullPayload));

                        try {
                            Thread.sleep(500);
                        } catch (InterruptedException ignored) {
                        }
                    }
                }
            }
        }
    }

    private String collectStreamBlocking(dev.langchain4j.service.TokenStream stream) {
        if (stream == null)
            return "";
        java.util.concurrent.CompletableFuture<String> future = new java.util.concurrent.CompletableFuture<>();
        stream.onNext(token -> {
        })
                .onComplete(response -> future.complete(response.content().text()))
                .onError(future::completeExceptionally)
                .start();
        try {
            return future.get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.error("Stream Collection Failed", e);
            return "";
        }
    }

    public String getInvestigationContext(String id) {
        for (Investigation inv : investigations) {
            // Check current ID
            if (inv.id.equals(id))
                return inv.cachedContext;

            // Allow looking up by old style permalink (fallback/backward compatibility)
            // if (inv.latestPermalink.equals(id)) ...
        }
        return null;
    }

    private static class Investigation {
        String id;
        String initialTitle;
        Instant firstSeen;
        Instant lastActivity;
        Embedding centroid;

        // Cache the context used for the last report
        String cachedContext;
        // History of reports to detect loops
        java.util.List<String> reportHistory = new java.util.ArrayList<>();

        // Metrics
        int threadCount = 0;
        int totalScore = 0;
        int totalComments = 0;
        boolean keywordBonus = false;
        double currentSignificance = 0.0;

        boolean reported = false;

        String latestPermalink; // Store permalink for fetching content
        String bestPermalink; // Store permalink of highest score thread
        int bestThreadScore = -1;

        long threadCreatedUTC = 0; // For Age Context
        List<String> evidenceLog = new ArrayList<>(); // Re-added
        java.util.Set<String> activePermalinks = new java.util.HashSet<>();

        public Investigation(RedditThread initialThread, Embedding embedding) {
            this.id = java.util.UUID.randomUUID().toString().substring(0, 8);
            this.initialTitle = initialThread.getTitle();
            this.firstSeen = Instant.now();
            this.lastActivity = Instant.now();
            this.centroid = embedding;

            // Init Metrics
            this.threadCount = 1;
            this.totalScore = initialThread.getScore();
            this.totalComments = initialThread.getNumComments();
            this.latestPermalink = initialThread.getPermalink();
            this.bestPermalink = initialThread.getPermalink();
            this.bestThreadScore = initialThread.getScore();
            this.threadCreatedUTC = initialThread.getCreatedUtc();

            this.evidenceLog.add("[" + LocalTime.now() + "] New Thread: " + initialThread.getTitle());
            this.activePermalinks.add(initialThread.getPermalink());
        }

        public void addUpdate(RedditThread t, int deltaScore, int deltaComments) {
            this.lastActivity = Instant.now();
            this.totalScore += deltaScore;
            this.totalComments += deltaComments;
            this.latestPermalink = t.getPermalink(); // Update to latest active thread
            this.activePermalinks.add(t.getPermalink());

            if (t.getScore() > this.bestThreadScore) {
                this.bestThreadScore = t.getScore();
                this.bestPermalink = t.getPermalink();
            }

            boolean isNewThread = !evidenceLog.stream().anyMatch(s -> s.contains(t.getTitle()));
            if (isNewThread) {
                this.threadCount++; // Rough heuristic, assuming titles differ slightly or re-posts
                this.evidenceLog.add("[" + LocalTime.now() + "] Related Thread: " + t.getTitle());
            } else {
                this.evidenceLog.add("[" + LocalTime.now() + "] Activity: +" + deltaScore + " score, +" + deltaComments
                        + " comments on '" + t.getTitle() + "'");
            }
        }

        public long countRecentEvents(Duration window) {
            Instant since = Instant.now().minus(window);
            return lastActivity.isAfter(since) ? 1 : 0;
        }

        // Cache for Vision Results (URL -> Analysis Text)
        java.util.Map<String, String> visionCache = new java.util.concurrent.ConcurrentHashMap<>();
    }
}
