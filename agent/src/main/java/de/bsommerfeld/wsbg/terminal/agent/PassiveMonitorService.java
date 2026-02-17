package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.agent.ChatService.AgentStreamEndEvent;
import de.bsommerfeld.wsbg.terminal.agent.ChatService.AgentStreamStartEvent;
import de.bsommerfeld.wsbg.terminal.agent.ChatService.AgentTokenEvent;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.config.HeadlineConfig;
import de.bsommerfeld.wsbg.terminal.core.config.RedditConfig;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.LogEvent;
import de.bsommerfeld.wsbg.terminal.core.i18n.I18nService;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import de.bsommerfeld.wsbg.terminal.reddit.RedditScraper;
import de.bsommerfeld.wsbg.terminal.reddit.RedditScraper.ScrapeStats;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.store.embedding.CosineSimilarity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Passive Reddit monitor with vector-based clustering and significance scoring.
 * Groups related threads, detects activity "heat" via temporal velocity,
 * and triggers AI headline generation when a weighted significance threshold
 * is reached.
 */
@Singleton
public class PassiveMonitorService {

    private static final Logger LOG = LoggerFactory.getLogger(PassiveMonitorService.class);

    private final RedditScraper scraper;
    private final AgentBrain brain;
    private final ApplicationEventBus eventBus;
    private final RedditRepository repository;
    private final I18nService i18n;
    private final GlobalConfig config;
    private final ReportBuilder reportBuilder;

    private final EmbeddingModel embeddingModel;
    private final ScheduledExecutorService scannerExecutor = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService analysisExecutor = Executors.newSingleThreadExecutor();
    private final List<InvestigationCluster> investigations = new CopyOnWriteArrayList<>();

    // Tracking deltas between scan cycles
    private final Map<String, Integer> lastSeenScore = new ConcurrentHashMap<>();
    private final Map<String, Integer> lastSeenComments = new ConcurrentHashMap<>();
    private Instant lastCleanup = Instant.MIN;

    // Config values — loaded once from RedditConfig
    private final double similarityThreshold;
    private final Duration investigationTtl;
    private final double significanceThresholdReport;
    private final Duration cleanupInterval;
    private final long dataRetentionSeconds;
    private final long updateIntervalSeconds;

    @Inject
    public PassiveMonitorService(RedditScraper scraper, AgentBrain brain, ApplicationEventBus eventBus,
            RedditRepository repository, GlobalConfig config, I18nService i18n) {
        this.scraper = scraper;
        this.brain = brain;
        this.eventBus = eventBus;
        this.repository = repository;
        this.i18n = i18n;
        this.config = config;
        this.reportBuilder = new ReportBuilder(repository, brain);

        RedditConfig redditConfig = config.getReddit();
        this.similarityThreshold = redditConfig.getSimilarityThreshold();
        this.investigationTtl = Duration.ofMinutes(redditConfig.getInvestigationTtlMinutes());
        this.significanceThresholdReport = redditConfig.getSignificanceThreshold();
        this.cleanupInterval = Duration.ofHours(1);
        this.dataRetentionSeconds = Duration.ofHours(redditConfig.getDataRetentionHours()).toSeconds();
        this.updateIntervalSeconds = redditConfig.getUpdateIntervalSeconds();

        String embeddingModelName = AgentBrain.EMBEDDING_MODEL;
        LOG.info("Initializing Vector Embedding Model: {}", embeddingModelName);

        this.embeddingModel = OllamaEmbeddingModel.builder()
                .baseUrl(AgentBrain.OLLAMA_BASE_URL)
                .modelName(embeddingModelName)
                .timeout(Duration.ofSeconds(60))
                .build();

        startMonitoring();
    }

    /**
     * Retrieves cached AI context for a given investigation cluster.
     * Used by {@link ChatService} to perform follow-up analysis on a
     * previously reported headline without re-fetching from Reddit.
     *
     * @param id cluster ID (8-char UUID prefix)
     * @return cached context string, or {@code null} if the cluster expired
     */
    public String getInvestigationContext(String id) {
        for (InvestigationCluster inv : investigations) {
            if (inv.id.equals(id))
                return inv.cachedContext;
        }
        return null;
    }

    // -- Monitoring Lifecycle --

    private void startMonitoring() {
        LOG.info("Starting Passive Reddit Monitor...");
        scannerExecutor.execute(this::performInitialStartup);
        scannerExecutor.scheduleAtFixedRate(this::scanCycle, 30, updateIntervalSeconds, TimeUnit.SECONDS);
    }

    /**
     * Runs a one-time initial cleanup and seed scan. Fires before the
     * recurring scheduler kicks in — ensures the database is in a good
     * state and the initial thread batch is loaded.
     */
    private void performInitialStartup() {
        LOG.info("Performing initial scan...");
        try {
            repository.cleanupOldThreads(dataRetentionSeconds).thenAcceptAsync(count -> {
                LOG.info("Initial cleanup: removed {} old threads.", count);
                refreshLocalThreads();
            }, scannerExecutor);
        } catch (Exception e) {
            LOG.error("Initial startup failed", e);
        }
    }

    private void refreshLocalThreads() {
        try {
            List<String> ids = repository.getAllThreads().stream()
                    .map(RedditThread::id).collect(Collectors.toList());
            if (!ids.isEmpty()) {
                ScrapeStats stats = scraper.updateThreadsBatch(ids);
                if (stats.hasUpdates()) {
                    LOG.info("Local thread refresh: {}", stats);
                    if (!stats.threadUpdates.isEmpty()) {
                        analysisExecutor.submit(() -> processUpdates(stats.threadUpdates));
                    }
                }
            }
        } catch (Exception e) {
            LOG.error("Failed to refresh local threads", e);
        }
    }

    // -- Scan Cycle --

    private void scanCycle() {
        try {
            if (Duration.between(lastCleanup, Instant.now()).compareTo(cleanupInterval) > 0) {
                lastCleanup = Instant.now();
                repository.cleanupOldThreads(dataRetentionSeconds)
                        .thenAccept(count -> eventBus.post(new LogEvent(String.valueOf(count), "CLEANUP")));
            }

            ScrapeStats stats = new ScrapeStats();
            for (String sub : config.getReddit().getSubreddits()) {
                stats.add(scraper.scanSubreddit(sub));
            }

            // Gap-fill: update threads not covered by the subreddit scan
            List<String> idsToUpdate = repository.getAllThreads().stream()
                    .map(RedditThread::id)
                    .filter(id -> !stats.scannedIds.contains(id))
                    .collect(Collectors.toList());
            if (!idsToUpdate.isEmpty()) {
                stats.add(scraper.updateThreadsBatch(idsToUpdate));
            }

            if (stats.threadUpdates.isEmpty() && investigations.isEmpty())
                return;
            analysisExecutor.submit(() -> processUpdates(stats.threadUpdates));
        } catch (Exception e) {
            LOG.error("Passive monitor scan failed", e);
        }
    }

    // -- Vector Clustering --

    private void processUpdates(List<RedditThread> updates) {
        HeadlineConfig headlineConfig = config.getHeadlines();
        if (!headlineConfig.isEnabled())
            return;

        try {
            boolean meaningfulChange = false;

            for (RedditThread t : updates) {
                int[] deltas = computeDeltas(t);
                if (deltas[0] > 0 || deltas[1] > 0) {
                    LOG.info("Update '{}': +{} score, +{} comments", t.title(), deltas[0], deltas[1]);
                }
                if (clusterThread(t, deltas[0], deltas[1])) {
                    meaningfulChange = true;
                }
            }

            pruneInvestigations();
            mergeConvergedClusters();
            if (meaningfulChange || !investigations.isEmpty()) {
                analyzeInvestigations();
            }
        } catch (Exception e) {
            LOG.error("Processing failed", e);
        }
    }

    /**
     * Tracks score/comment deltas between scan cycles.
     * Returns [deltaScore, deltaComments].
     */
    private int[] computeDeltas(RedditThread t) {
        int deltaScore = t.score() - lastSeenScore.getOrDefault(t.id(), t.score());
        lastSeenScore.put(t.id(), t.score());

        int deltaComments = t.numComments() - lastSeenComments.getOrDefault(t.id(), t.numComments());
        lastSeenComments.put(t.id(), t.numComments());

        return new int[] { deltaScore, deltaComments };
    }

    /**
     * Embeds thread content and assigns it to the best matching cluster,
     * or creates a new investigation if no cluster exceeds the similarity
     * threshold.
     * Returns true if this triggered a meaningful state change.
     */
    private boolean clusterThread(RedditThread t, int deltaScore, int deltaComments) {
        String content = t.title() + " " + (t.textContent() != null ? t.textContent() : "");
        Embedding embedding = embeddingModel.embed(content).content();

        InvestigationCluster bestMatch = null;
        double bestScore = -1.0;
        for (InvestigationCluster inv : investigations) {
            double score = CosineSimilarity.between(embedding, inv.centroid());
            if (score > bestScore) {
                bestScore = score;
                bestMatch = inv;
            }
        }

        if (bestMatch != null && bestScore >= similarityThreshold) {
            if (deltaScore > 0 || deltaComments > 0) {
                bestMatch.addUpdate(t, deltaScore, deltaComments, embedding);
                return true;
            }
            return false;
        }

        InvestigationCluster newInv = new InvestigationCluster(t, embedding);
        investigations.add(newInv);
        LOG.info("New investigation: '{}'", t.title());
        return true;
    }

    private void pruneInvestigations() {
        Instant now = Instant.now();
        Iterator<InvestigationCluster> it = investigations.iterator();
        while (it.hasNext()) {
            if (Duration.between(it.next().lastActivity, now).compareTo(investigationTtl) > 0) {
                it.remove();
            }
        }
    }

    /**
     * Merges cluster pairs whose centroids have drifted into similarity.
     * Uses a higher threshold than initial assignment to avoid premature merging.
     */
    private void mergeConvergedClusters() {
        double mergeThreshold = similarityThreshold + 0.10;
        List<InvestigationCluster> snapshot = new ArrayList<>(investigations);

        for (int i = 0; i < snapshot.size(); i++) {
            InvestigationCluster a = snapshot.get(i);
            if (!investigations.contains(a))
                continue;

            for (int j = i + 1; j < snapshot.size(); j++) {
                InvestigationCluster b = snapshot.get(j);
                if (!investigations.contains(b))
                    continue;

                double sim = CosineSimilarity.between(a.centroid(), b.centroid());
                if (sim >= mergeThreshold) {
                    // Absorb smaller into larger
                    InvestigationCluster primary = a.threadCount >= b.threadCount ? a : b;
                    InvestigationCluster secondary = primary == a ? b : a;

                    primary.absorb(secondary);
                    investigations.remove(secondary);
                    LOG.info("Merged cluster '{}' into '{}' (sim={})",
                            secondary.initialTitle, primary.initialTitle,
                            String.format("%.3f", sim));
                }
            }
        }
    }

    // -- AI Headline Generation --

    private void analyzeInvestigations() {
        HeadlineConfig headlineConfig = config.getHeadlines();
        List<InvestigationCluster> candidates = new ArrayList<>();

        for (InvestigationCluster inv : investigations) {
            if (inv.reported)
                continue;

            SignificanceScore result = SignificanceScorer.compute(inv);
            inv.currentSignificance = result.score();

            if (result.meetsThreshold(significanceThresholdReport)) {
                LOG.info("Cluster '{}' passes heuristic gate: {}",
                        inv.initialTitle, result.score());
                candidates.add(inv);
            }
        }

        if (candidates.isEmpty())
            return;

        for (InvestigationCluster inv : candidates) {
            String reportData = reportBuilder.buildReportData(inv);
            String combinedContext = reportBuilder.buildCombinedContext(inv, reportData);
            inv.cachedContext = reportData;

            String historyBlock = inv.reportHistory.isEmpty() ? "NONE"
                    : String.join("\n", inv.reportHistory.stream()
                            .map(h -> "- " + h).collect(Collectors.toList()));

            // Single AI call: significance + topic relevance + headline
            String prompt = reportBuilder.buildHeadlinePrompt(historyBlock, combinedContext,
                    headlineConfig.isShowAll(), headlineConfig.getTopics());
            String response = collectStreamBlocking(brain.ask("passive-monitor", prompt)).trim();
            LOG.info("[AI-RESPONSE] {}", response);

            if (response.contains("-1"))
                continue;
            if (!response.contains("REPORT:"))
                continue;

            String headline = reportBuilder.extractHeadline(response);
            if (headline.isEmpty())
                continue;

            inv.reported = true;
            inv.addToHistory(headline);
            inv.cachedContext = combinedContext;

            translateAndStream(inv, headline);
        }
    }

    private void translateAndStream(InvestigationCluster inv, String headline) {
        String targetLang = config.getUser().getLanguage();

        // AI produces English headlines; skip translation when user language is English
        if ("en".equalsIgnoreCase(targetLang)) {
            String payload = "||PASSIVE||" + headline + "||REF||ID:" + inv.id;
            eventBus.post(new AgentStreamStartEvent(i18n.get("log.source.passive_agent"), "source-AI"));
            eventBus.post(new AgentStreamEndEvent(payload));
            return;
        }

        Locale targetLocale = Locale.forLanguageTag(targetLang);
        String targetName = targetLocale.getDisplayLanguage(Locale.ENGLISH);

        TokenStream stream = brain.translate(headline, "English", "en", targetName, targetLang);
        if (stream == null)
            return;

        eventBus.post(new AgentStreamStartEvent(i18n.get("log.source.passive_agent"), "source-AI"));
        StringBuilder translated = new StringBuilder();
        CountDownLatch latch = new CountDownLatch(1);

        stream.onNext(token -> {
            translated.append(token);
            eventBus.post(new AgentTokenEvent(token));
        }).onComplete(res -> latch.countDown()).onError(ex -> latch.countDown()).start();

        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        String payload = "||PASSIVE||" + translated + "||REF||ID:" + inv.id;
        eventBus.post(new AgentStreamEndEvent(payload));

        try {
            Thread.sleep(500);
        } catch (InterruptedException ignored) {
        }
    }

    private String collectStreamBlocking(TokenStream stream) {
        if (stream == null)
            return "";
        CompletableFuture<String> future = new CompletableFuture<>();
        stream.onNext(token -> {
        })
                .onComplete(response -> future.complete(response.content().text()))
                .onError(future::completeExceptionally)
                .start();
        try {
            return future.get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            LOG.error("Stream collection failed", e);
            return "";
        }
    }
}
