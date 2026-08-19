package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.config.RedditConfig;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.util.BackgroundThreads;
import de.bsommerfeld.wsbg.terminal.core.util.JitteredScheduler;
import de.bsommerfeld.wsbg.terminal.db.AgentRepository;
import de.bsommerfeld.wsbg.terminal.db.RedditRepository;
import de.bsommerfeld.wsbg.terminal.db.RedditSnapshotStore;
import de.bsommerfeld.wsbg.terminal.reddit.RedditSource;
import de.bsommerfeld.wsbg.terminal.reddit.ScrapeStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Passive Reddit scanner that maintains the cluster set.
 *
 * <p>
 * Responsibilities are narrow now: poll Reddit, compute deltas, and hand each
 * changed thread to {@link ClusterEngine} (one cluster == one thread). Each
 * mutation pushes a {@code notifyChange} into {@link ClusterRegistry}; the
 * {@code AgentCoordinator} listens on the other end. There is no cross-thread
 * merge/prune step — the feed-wide {@link SubjectRegistry} is the cross-thread layer.
 *
 * <p>The delta bookkeeping ({@link DeltaTracker}), the vision pool + cache-warming
 * ({@link VisionPrefetcher}) and short-TTL session persistence
 * ({@link SnapshotCoordinator}) are collaborators; this class owns the scan-loop
 * scheduling and startup orchestration.
 */
@Singleton
public class PassiveMonitorService {

    private static final Logger LOG = LoggerFactory.getLogger(PassiveMonitorService.class);

    private final RedditSource scraper;
    private final ApplicationEventBus eventBus;
    private final RedditRepository repository;
    private final AgentRepository agentRepository;
    private final GlobalConfig config;

    private final ClusterEngine clusterEngine;
    private final ClusterRegistry clusterRegistry;

    private final DeltaTracker deltaTracker = new DeltaTracker();
    private final VisionPrefetcher visionPrefetcher;
    private final SnapshotCoordinator snapshotCoordinator;

    private final ScheduledExecutorService scannerExecutor =
            Executors.newSingleThreadScheduledExecutor(daemonFactory("reddit-scanner"));
    private final ExecutorService analysisExecutor =
            Executors.newSingleThreadExecutor(daemonFactory("reddit-analysis"));

    /** Guards {@link #start()} — the scan loop is armed exactly once. */
    private final java.util.concurrent.atomic.AtomicBoolean started =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private Instant lastCleanup = Instant.MIN;

    // Config values — loaded once from RedditConfig
    private final Duration cleanupInterval;
    private final long dataRetentionSeconds;
    private final long updateIntervalSeconds;

    @Inject
    public PassiveMonitorService(RedditSource scraper, AgentBrain brain, ApplicationEventBus eventBus,
            RedditRepository repository, AgentRepository agentRepository,
            RedditSnapshotStore snapshotStore, AgentSnapshotStore agentSnapshotStore,
            ClusterRegistry clusterRegistry, SubjectRegistry subjectRegistry,
            ClusterEngine clusterEngine, GlobalConfig config) {
        this.scraper = scraper;
        this.eventBus = eventBus;
        this.repository = repository;
        this.agentRepository = agentRepository;
        this.clusterRegistry = clusterRegistry;
        this.clusterEngine = clusterEngine;
        this.config = config;

        RedditConfig redditConfig = config.getReddit();
        this.cleanupInterval = Duration.ofHours(1);
        this.dataRetentionSeconds = Duration.ofHours(redditConfig.getDataRetentionHours()).toSeconds();
        this.updateIntervalSeconds = redditConfig.getUpdateIntervalSeconds();

        this.visionPrefetcher = new VisionPrefetcher(brain, repository, config);
        this.snapshotCoordinator = new SnapshotCoordinator(repository, agentRepository, brain,
                clusterRegistry, subjectRegistry, snapshotStore, agentSnapshotStore,
                deltaTracker, redditConfig.getSnapshotTtlMinutes());
    }

    // -- Monitoring Lifecycle --

    /**
     * Arms the scan loop. Idempotent, and separate from the constructor: this
     * class is an eager singleton, so scanning used to begin inside
     * {@code Guice.createInjector} — before the window existed, competing with
     * the startup intro. The deferred startup calls this once the intro is gone.
     */
    public void start() {
        if (!started.compareAndSet(false, true)) return;
        startMonitoring();
    }

    private void startMonitoring() {
        LOG.info("Starting Passive Reddit Monitor...");
        scannerExecutor.execute(this::performInitialStartup);
        // Jittered, not fixed-rate: an exactly periodic scan is the one bot
        // signal the browser fingerprint can't hide (traffic blending, Hebel 1).
        JitteredScheduler.schedule(scannerExecutor, this::scanCycle,
                30, updateIntervalSeconds, TimeUnit.SECONDS,
                config.getNet().getPollJitterPercent());

        if (snapshotCoordinator.enabled()) {
            // Persist a fresh snapshot periodically and on exit so a quick
            // restart restores instead of re-fetching (see RedditSnapshotStore).
            scannerExecutor.scheduleAtFixedRate(snapshotCoordinator::save, 5, 5, TimeUnit.MINUTES);
            Runtime.getRuntime().addShutdownHook(new Thread(snapshotCoordinator::save, "reddit-snapshot-save"));
        }
    }

    /** Daemon thread factory so a dangling scan/analysis task never holds the JVM open. */
    private static ThreadFactory daemonFactory(String name) {
        return BackgroundThreads.single(name);
    }

    /**
     * Stops the scan + analysis + vision pools. Called from {@code AppMain}'s
     * shutdown sequence BEFORE Ollama/the repositories go down, so the scan loop
     * doesn't fire fresh vision calls against services that are tearing down. The
     * periodic snapshot save still runs from its own shutdown hook.
     */
    public void shutdown() {
        scannerExecutor.shutdownNow();
        analysisExecutor.shutdownNow();
        visionPrefetcher.shutdown();
        LOG.info("PassiveMonitorService stopped.");
    }

    /**
     * Runs a one-time initial cleanup and seed scan. Fires before the
     * recurring scheduler kicks in — ensures the database is in a good
     * state and the initial thread batch is loaded.
     */
    private void performInitialStartup() {
        LOG.info("Performing initial scan...");
        try {
            SnapshotCoordinator.RestoreOutcome outcome = snapshotCoordinator.restore();
            repository.cleanupOldThreads(dataRetentionSeconds).thenAcceptAsync(count -> {
                LOG.info("Initial cleanup: removed {} old threads.", count);
                if (outcome.dataRestored() && outcome.clustersRestored()) {
                    // Exact prior state is back (clusters + vision + headlines).
                    // Nothing to rebuild, nothing to re-fetch.
                    LOG.info("Snapshot restored verbatim: {} clusters resumed, no re-fetch/re-seed.",
                            clusterRegistry.size());
                } else if (outcome.dataRestored()) {
                    // Reddit data is fresh but no cluster snapshot — rebuild the
                    // cluster set locally (vision from the restored cache) and
                    // skip the cold-start fetch.
                    LOG.info("Snapshot restored (no clusters) — seeding clusters locally, no re-fetch.");
                    analysisExecutor.submit(this::seedClustersFromRepository);
                } else {
                    refreshLocalThreads();
                }
            }, scannerExecutor);
        } catch (Exception e) {
            LOG.error("Initial startup failed", e);
        }
    }

    /**
     * Rebuilds the cluster set from whatever is already in the repository
     * (i.e. a restored snapshot) without touching the network. Vision is used
     * cache-only here to avoid a cold-cache stampede on startup; image vision
     * caches are warmed asynchronously so the reports fill in over the next ticks.
     */
    private void seedClustersFromRepository() {
        if (!config.getHeadlines().isEnabled()) return;
        try {
            // Stream-discovered stubs are excluded: a cluster is an editorial
            // atom, and the comment stream is not an editorial lane.
            List<RedditThread> threads = repository.getAllThreads().stream()
                    .filter(this::isEditorialThread).collect(Collectors.toList());
            if (threads.isEmpty()) return;
            LOG.info("Seeding clusters from {} restored threads...", threads.size());

            for (RedditThread t : threads) {
                // Prime delta baselines so restored threads don't read as "new".
                deltaTracker.seedBaseline(t);
            }
            for (RedditThread t : threads) {
                // Use vision from the restored cache (cache-only, no recompute);
                // threads with no cached vision assign on text alone.
                clusterEngine.assign(t, 0, 0, visionPrefetcher.cachedVisionText(t));
            }
            // One cluster == one thread now — seeding just re-creates each
            // thread's own cluster; there is no merge/prune step to defer.
            for (RedditThread t : threads) {
                visionPrefetcher.warm(t);
            }
            LOG.info("Cluster seeding complete: {} clusters.", clusterRegistry.size());
        } catch (Exception e) {
            LOG.error("Cluster seeding failed", e);
        }
    }

    /**
     * Whether a stored thread belongs to the EDITORIAL scan set — i.e. it came
     * from a subreddit we actually scan, not from the ingest-only comment
     * stream. Stream threads are stubs discovered through a comment; scanning,
     * gap-filling or clustering them would drag a foreign room into the
     * headlines and multiply the request budget, which is precisely what the
     * one-request stream exists to avoid.
     */
    private boolean isEditorialThread(RedditThread t) {
        if (t == null || t.subreddit() == null) return false;
        for (String sub : config.getReddit().getSubreddits()) {
            if (sub.equalsIgnoreCase(t.subreddit())) return true;
        }
        return false;
    }

    private void refreshLocalThreads() {
        try {
            List<String> ids = repository.getAllThreads().stream()
                    .filter(this::isEditorialThread)
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
                        .thenAccept(count -> LOG.debug("[CLEANUP] pruned {} old thread(s)", count));
                // The headline wire is NOT pruned: it mirrors the permanent
                // archive in full, and the page renders only what is on screen.
            }

            ScrapeStats stats = new ScrapeStats();
            for (String sub : config.getReddit().getSubreddits()) {
                stats.add(scraper.scanSubreddit(sub));
            }

            // The sub-wide comment stream — one request per subreddit for the
            // whole room's comment flow. Kept OUT of `stats` on purpose: this
            // lane feeds the repository (the counting layer), never the
            // editorial pipeline, so a busy foreign room cannot reach the
            // headlines. Its threads are also excluded from the gap-fill below.
            for (String sub : config.getReddit().getCommentStreamSubreddits()) {
                ScrapeStats streamStats = scraper.scanComments(sub);
                if (streamStats.newComments > 0) {
                    LOG.info("[STREAM] r/{}: +{} comment(s)", sub, streamStats.newComments);
                }
            }

            // Activity-windowed gap-fill — only refresh threads that showed activity
            // within the last window. Reddit threads cool down quickly: the bulk of
            // comments land in the first 1-2 hours. Polling an idle thread every cycle
            // costs requests against the anon budget for almost no new content. The
            // window is intentionally generous (45 min) so a thread that picks up a
            // fresh comment burst still gets a chance to re-enter the hot set.
            final long gapFillHotWindowSecs = 45 * 60;
            final long nowEpoch = Instant.now().getEpochSecond();
            List<String> idsToUpdate = repository.getAllThreads().stream()
                    .filter(this::isEditorialThread)
                    .filter(t -> !stats.scannedIds.contains(t.id()))
                    .filter(t -> (nowEpoch - t.lastActivityUtc()) < gapFillHotWindowSecs)
                    .map(RedditThread::id)
                    .collect(Collectors.toList());
            if (!idsToUpdate.isEmpty()) {
                stats.add(scraper.updateThreadsBatch(idsToUpdate));
            }

            if (stats.threadUpdates.isEmpty() && clusterRegistry.isEmpty())
                return;
            analysisExecutor.submit(() -> processUpdates(stats.threadUpdates));
        } catch (Exception e) {
            LOG.error("Passive monitor scan failed", e);
        }
    }

    /**
     * Pre-fetches vision, warms the deferred image caches, then assigns each thread
     * to its own cluster with its computed deltas. One cluster == one thread; there
     * is no cross-thread merge/prune. Headline generation is driven by
     * {@code AgentCoordinator} (subscribed to {@link ClusterRegistry#notifyChange}).
     */
    private void processUpdates(List<RedditThread> updates) {
        if (!config.getHeadlines().isEnabled())
            return;

        try {
            // Pre-launch vision so multiple threads' images analyse in parallel on the
            // vision pool; by the time we reach thread N the early futures are usually
            // already done. Empty when vision is opt-out.
            Map<String, CompletableFuture<String>> visionFutures = visionPrefetcher.launchBatch(updates);

            // Cache-warm the images that don't feed the blocking join (deep gallery
            // slides + comment images). Fire-and-forget: ReportBuilder reads cache-only.
            for (RedditThread t : updates) {
                visionPrefetcher.warm(t);
            }

            for (RedditThread t : updates) {
                int[] deltas = deltaTracker.computeDeltas(t);
                if (deltas[0] > 0 || deltas[1] > 0) {
                    LOG.info("Update '{}': +{} score, +{} comments", t.title(), deltas[0], deltas[1]);
                }
                CompletableFuture<String> vf = visionFutures.get(t.id());
                clusterEngine.assign(t, deltas[0], deltas[1], vf == null ? "" : vf.join());
            }
        } catch (Exception e) {
            LOG.error("Processing failed", e);
        }
    }
}
