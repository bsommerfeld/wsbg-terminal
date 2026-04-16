package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.agent.event.AgentStreamEndEvent;
import de.bsommerfeld.wsbg.terminal.agent.event.AgentStreamStartEvent;

import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.config.Model;
import de.bsommerfeld.wsbg.terminal.core.config.RedditConfig;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.LogEvent;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.TickerSnapshotEvent;
import de.bsommerfeld.wsbg.terminal.core.i18n.I18nService;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository;
import de.bsommerfeld.wsbg.terminal.db.DatabaseService.HeadlineRecord;
import de.bsommerfeld.wsbg.terminal.db.DatabaseService.TickerMentionRecord;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import de.bsommerfeld.wsbg.terminal.reddit.RedditScraper;
import de.bsommerfeld.wsbg.terminal.reddit.RedditScraper.ScrapeStats;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import dev.langchain4j.store.embedding.CosineSimilarity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
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
    private final AgentRepository agentRepository;
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

    // CHL re-evaluation gate: minimum significance delta since last AI call.
    // ~8 points ≈ 1 new thread joining the cluster, or 3–4 new comments.
    // Breaking stories naturally exceed this within seconds, so they are
    // never blocked. Quiet clusters don't re-trigger because their
    // significance plateaus.
    private static final double CHL_SIGNIFICANCE_DELTA = 8.0;

    @Inject
    public PassiveMonitorService(RedditScraper scraper, AgentBrain brain, ApplicationEventBus eventBus,
            RedditRepository repository, AgentRepository agentRepository,
            GlobalConfig config, I18nService i18n) {
        this.scraper = scraper;
        this.brain = brain;
        this.eventBus = eventBus;
        this.repository = repository;
        this.agentRepository = agentRepository;
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

        String embeddingModelName = Model.EMBEDDING.getModelName();
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
                agentRepository.cleanup();
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
        if (!config.getHeadlines().isEnabled())
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
        List<InvestigationCluster> candidates = new ArrayList<>();

        for (InvestigationCluster inv : investigations) {
            // Maturity gate: don't evaluate clusters that were just created.
            // Single-thread clusters with no activity updates are noise —
            // wait for at least one update cycle to confirm real engagement.
            if (inv.threadCount <= 1 && inv.totalComments < 3
                    && Duration.between(inv.firstSeen, Instant.now()).toMinutes() < 2) {
                continue;
            }

            SignificanceScore result = SignificanceScorer.compute(inv);
            inv.currentSignificance = result.score();

            // First-time evaluation: absolute threshold. CHL re-evaluation:
            // activity-based delta — hot stories with rapid context influx
            // are never blocked from producing follow-up headlines.
            if (!result.meetsThreshold(significanceThresholdReport))
                continue;
            if (!inv.isEligibleForEvaluation(inv.currentSignificance, CHL_SIGNIFICANCE_DELTA))
                continue;

            LOG.info("Cluster '{}' passes heuristic gate: {}",
                    inv.initialTitle, result.score());
            candidates.add(inv);
        }

        if (candidates.isEmpty())
            return;

        for (InvestigationCluster inv : candidates) {
            String reportData = reportBuilder.buildReportData(inv);
            String combinedContext = reportBuilder.buildCombinedContext(inv, reportData);

            // Warm cluster state from DB when reportHistory is empty (restart scenario).
            // With deterministic cluster IDs (= initial thread ID), we can reliably find
            // the headlines this cluster produced before the restart and restore:
            //   1. reportHistory — so the AI sees its own editorial history
            //   2. lastEvaluatedAt — so the CHL anti-spam gate applies
            //   3. significanceAtLastEvaluation = current significance — so the delta gate
            //      requires real new activity before allowing a re-evaluation.
            if (inv.reportHistory.isEmpty()) {
                List<HeadlineRecord> past = agentRepository.getHeadlinesByClusterId(inv.id);
                if (!past.isEmpty()) {
                    past.forEach(h -> inv.reportHistory.add(h.headline()));
                    inv.lastEvaluatedAt = Instant.ofEpochSecond(
                            past.stream().mapToLong(HeadlineRecord::createdAt).max().orElse(0));
                    inv.significanceAtLastEvaluation = inv.currentSignificance;
                }
            }

            String historyBlock = inv.reportHistory.isEmpty() ? "NONE"
                    : inv.reportHistory.stream().map(h -> "- " + h).collect(Collectors.joining("\n"));

            // Delta principle: on CHL re-evaluations, extract only lines that are new
            // since the last evaluation and highlight them in the prompt. The AI
            // receives the full context for history but a focused delta for its verdict —
            // forcing it to ask "does this *new* data warrant a new headline?"
            String deltaContext = inv.lastEvaluatedAt != null
                    ? reportBuilder.buildDeltaContext(combinedContext, inv.cachedContext)
                    : "";

            String prompt = reportBuilder.buildHeadlinePrompt(historyBlock, combinedContext,
                    deltaContext, brain.getUserLanguage().displayName());
            String response = brain.ask("passive-monitor", prompt);
            if (response == null) {
                LOG.warn("Brain returned null for headline generation");
                continue;
            }
            response = response.trim();
            LOG.info("[AI-RESPONSE] {}", response);

            // Snapshot significance after every AI call so the delta
            // gate knows when enough new context has accumulated.
            inv.significanceAtLastEvaluation = inv.currentSignificance;
            inv.lastEvaluatedAt = Instant.now();

            // Primary gate: VERDICT must be explicit ACCEPT.
            // The prompt structure forces the AI to choose ACCEPT or REJECT
            // before generating a headline — this fixes the issue where small
            // models never return bare "-1" but always generate REPORT: lines.
            if (!reportBuilder.isAccepted(response)) {
                LOG.info("Cluster '{}' rejected by AI verdict", inv.initialTitle);
                inv.cachedContext = combinedContext;
                continue;
            }

            String headline = reportBuilder.extractHeadline(response);
            if (headline.isEmpty()) {
                inv.cachedContext = combinedContext;
                continue;
            }

            // Safety net: catch headlines that describe irrelevance, meta-commentary
            // about the subreddit, or indirect non-news the AI shouldn't have accepted
            String lowerHeadline = headline.toLowerCase();
            if (lowerHeadline.contains("irrelevant") || lowerHeadline.contains("unrelevant")
                    || lowerHeadline.contains("nicht relevant") || lowerHeadline.contains("not relevant")
                    || lowerHeadline.contains("subreddit")) {
                LOG.info("Rejected meta/irrelevance headline: {}", headline);
                inv.cachedContext = combinedContext;
                continue;
            }

            inv.addToHistory(headline);
            inv.cachedContext = combinedContext;

            agentRepository.saveHeadline(inv.id, headline, combinedContext);

            // Gemma4 already produces headlines in the user's language via
            // prompt injection — no separate translation step needed.
            String payload = "||PASSIVE||" + headline + "||REF||ID:" + inv.id;
            eventBus.post(new AgentStreamStartEvent(i18n.get("log.source.passive_agent"), "source-AI"));
            eventBus.post(new AgentStreamEndEvent(payload));

            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }
        }

        // Ticker extraction: runs on all candidates regardless of headline verdict.
        // Headline-rejected clusters still contain valuable ticker references
        // for the dashboard (e.g., penny stock questions).
        for (InvestigationCluster inv : candidates) {
            try {
                if (inv.cachedContext == null || inv.cachedContext.isBlank())
                    continue;
                TickerExtractionResult result = brain.extractTickers(inv.cachedContext);

                List<TickerMentionRecord> records = result.mentions().stream()
                        .map(m -> new TickerMentionRecord(m.symbol(), m.type(), m.name()))
                        .toList();
                agentRepository.saveTickerMentions(records);
            } catch (Exception e) {
                LOG.warn("Ticker extraction failed for cluster '{}'", inv.initialTitle, e);
            }
        }

        Map<String, Integer> snapshot = agentRepository.getTickerCountsLastHour();
        if (!snapshot.isEmpty()) {
            eventBus.post(new TickerSnapshotEvent(snapshot));
        }
    }
}
