package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.config.RedditConfig;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditComment;
import de.bsommerfeld.wsbg.terminal.core.domain.RedditThread;
import de.bsommerfeld.wsbg.terminal.core.event.ApplicationEventBus;
import de.bsommerfeld.wsbg.terminal.core.event.ControlEvents.LogEvent;
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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
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
 * {@code AgentCoordinator} listens on the other end and decides what (if
 * anything) to do with the change. There is no cross-thread merge/prune step
 * anymore — the feed-wide {@link SubjectRegistry} is the cross-thread layer.
 */
@Singleton
public class PassiveMonitorService {

    private static final Logger LOG = LoggerFactory.getLogger(PassiveMonitorService.class);

    private final RedditSource scraper;
    private final AgentBrain brain;
    private final ApplicationEventBus eventBus;
    private final RedditRepository repository;
    private final AgentRepository agentRepository;
    private final RedditSnapshotStore snapshotStore;
    private final AgentSnapshotStore agentSnapshotStore;
    private final GlobalConfig config;

    private final ClusterEngine clusterEngine;
    private final ScheduledExecutorService scannerExecutor =
            Executors.newSingleThreadScheduledExecutor(daemonFactory("reddit-scanner"));
    private final ExecutorService analysisExecutor =
            Executors.newSingleThreadExecutor(daemonFactory("reddit-analysis"));
    /**
     * Vision pre-fetch pool — a single worker. By default vision shares the
     * editorial agent's model + num_ctx, so both hit ONE Ollama runner with
     * {@code OLLAMA_NUM_PARALLEL=2} request slots. Keeping vision to one
     * worker leaves the second slot free for the latency-sensitive agent
     * tool loop — vision is background cache-warming, so serialising it is
     * the right trade. (When a user picks a text-only agent model, vision
     * loads its own gemma4:e4b runner and this worker simply has it to
     * itself.)
     */
    private final ExecutorService visionExecutor = Executors.newFixedThreadPool(1, r -> {
        Thread t = new Thread(r, "vision-prefetch");
        t.setDaemon(true);
        return t;
    });
    private final ClusterRegistry clusterRegistry;
    private final SubjectRegistry subjectRegistry;

    // Tracking deltas between scan cycles
    private final Map<String, Integer> lastSeenScore = new ConcurrentHashMap<>();
    private final Map<String, Integer> lastSeenComments = new ConcurrentHashMap<>();
    private Instant lastCleanup = Instant.MIN;
    private volatile boolean clustersRestored = false;

    // Config values — loaded once from RedditConfig
    private final Duration cleanupInterval;
    private final long dataRetentionSeconds;
    private final long updateIntervalSeconds;
    private final long snapshotTtlMinutes;

    /**
     * Snapshot persistence is gated on the configured TTL ({@code 0} disables
     * it). Kept as a field so the periodic save + shutdown hook can short-circuit
     * cheaply.
     */
    private final boolean snapshotsEnabled;

    @Inject
    public PassiveMonitorService(RedditSource scraper, AgentBrain brain, ApplicationEventBus eventBus,
            RedditRepository repository, AgentRepository agentRepository,
            RedditSnapshotStore snapshotStore, AgentSnapshotStore agentSnapshotStore,
            ClusterRegistry clusterRegistry, SubjectRegistry subjectRegistry,
            ClusterEngine clusterEngine, GlobalConfig config) {
        this.scraper = scraper;
        this.brain = brain;
        this.eventBus = eventBus;
        this.repository = repository;
        this.agentRepository = agentRepository;
        this.snapshotStore = snapshotStore;
        this.agentSnapshotStore = agentSnapshotStore;
        this.clusterRegistry = clusterRegistry;
        this.subjectRegistry = subjectRegistry;
        this.clusterEngine = clusterEngine;
        this.config = config;

        RedditConfig redditConfig = config.getReddit();
        this.cleanupInterval = Duration.ofHours(1);
        this.dataRetentionSeconds = Duration.ofHours(redditConfig.getDataRetentionHours()).toSeconds();
        this.updateIntervalSeconds = redditConfig.getUpdateIntervalSeconds();
        this.snapshotTtlMinutes = redditConfig.getSnapshotTtlMinutes();
        this.snapshotsEnabled = snapshotTtlMinutes > 0;

        startMonitoring();
    }

    // -- Monitoring Lifecycle --

    private void startMonitoring() {
        LOG.info("Starting Passive Reddit Monitor...");
        scannerExecutor.execute(this::performInitialStartup);
        // Jittered, not fixed-rate: an exactly periodic scan is the one bot
        // signal the browser fingerprint can't hide (traffic blending, Hebel 1).
        JitteredScheduler.schedule(scannerExecutor, this::scanCycle,
                30, updateIntervalSeconds, TimeUnit.SECONDS,
                config.getNet().getPollJitterPercent());

        if (snapshotsEnabled) {
            // Persist a fresh snapshot periodically and on exit so a quick
            // restart restores instead of re-fetching (see RedditSnapshotStore).
            scannerExecutor.scheduleAtFixedRate(this::saveSnapshot, 5, 5, TimeUnit.MINUTES);
            Runtime.getRuntime().addShutdownHook(new Thread(this::saveSnapshot, "reddit-snapshot-save"));
        }
    }

    /** Daemon thread factory so a dangling scan/analysis task never holds the JVM open. */
    private static ThreadFactory daemonFactory(String name) {
        return r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
    }

    /**
     * Stops the scan + analysis + vision pools. Called from {@code AppMain}'s
     * shutdown sequence BEFORE Ollama/the repositories go down, so the scan loop
     * doesn't fire fresh embedding/vision calls against services that are tearing
     * down. The periodic snapshot save still runs from its own shutdown hook.
     */
    public void shutdown() {
        scannerExecutor.shutdownNow();
        analysisExecutor.shutdownNow();
        visionExecutor.shutdownNow();
        LOG.info("PassiveMonitorService stopped.");
    }

    /** Writes the full session (Reddit data + vision cache + headlines) to disk. */
    private void saveSnapshot() {
        if (!snapshotsEnabled) return;
        try {
            snapshotStore.save(repository.getAllThreads(), repository.getAllComments());
            List<InvestigationCluster.Snapshot> clusterSnapshots = clusterRegistry.getAllClusters()
                    .stream().map(InvestigationCluster::toSnapshot).collect(Collectors.toList());
            agentSnapshotStore.save(brain.exportVisionCache(),
                    agentRepository.getAllHeadlines(), clusterSnapshots,
                    subjectRegistry.snapshotAll());
        } catch (Exception e) {
            LOG.warn("Snapshot save failed: {}", e.getMessage());
        }
    }

    /**
     * Runs a one-time initial cleanup and seed scan. Fires before the
     * recurring scheduler kicks in — ensures the database is in a good
     * state and the initial thread batch is loaded.
     */
    private void performInitialStartup() {
        LOG.info("Performing initial scan...");
        try {
            boolean restored = restoreSnapshotIfFresh();
            repository.cleanupOldThreads(dataRetentionSeconds).thenAcceptAsync(count -> {
                LOG.info("Initial cleanup: removed {} old threads.", count);
                if (restored && clustersRestored) {
                    // Exact prior state is back (clusters + vision + headlines).
                    // Nothing to rebuild, nothing to re-fetch.
                    LOG.info("Snapshot restored verbatim: {} clusters resumed, no re-fetch/re-seed.",
                            clusterRegistry.size());
                } else if (restored) {
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
     * Restores threads + comments from a fresh on-disk snapshot, if one exists
     * within the configured TTL. Returns {@code true} when data was restored,
     * so the caller can skip the cold-start network fetch.
     */
    private boolean restoreSnapshotIfFresh() {
        if (!snapshotsEnabled) return false;
        var snapshot = snapshotStore.loadIfFresh(snapshotTtlMinutes);
        if (snapshot.isEmpty()) return false;

        RedditSnapshotStore.RedditSnapshot s = snapshot.get();
        if (s.threads() != null) {
            repository.saveThreadsBatch(s.threads());
        }
        if (s.comments() != null) {
            for (RedditComment c : s.comments()) {
                repository.saveComment(c);
            }
        }
        LOG.info("Restored {} threads + {} comments from snapshot.",
                s.threads() != null ? s.threads().size() : 0,
                s.comments() != null ? s.comments().size() : 0);

        // Seed delta baselines so restored threads don't read as brand-new on
        // the first scan (which would re-trigger comment fan-out).
        for (RedditThread t : repository.getAllThreads()) {
            lastSeenScore.put(t.id(), t.score());
            lastSeenComments.put(t.id(), t.numComments());
        }

        // Restore the AI-derived state too: already-analysed images (no
        // re-vision), published headlines (UI shows them immediately + the
        // agent's coverage still knows what produced a headline), and the full
        // cluster state verbatim (centroid, evidence, shown-image markers) so
        // the agent resumes exactly where it left off.
        agentSnapshotStore.loadIfFresh(snapshotTtlMinutes).ifPresent(a -> {
            brain.importVisionCache(a.visionCache());
            agentRepository.restoreHeadlines(a.headlines());
            if (a.clusters() != null && !a.clusters().isEmpty()) {
                List<InvestigationCluster> restored = a.clusters().stream()
                        .map(InvestigationCluster::new)
                        .collect(Collectors.toList());
                clusterRegistry.restore(restored);
                clustersRestored = true;
            }
            // Subject units are the editorial atom — restore them verbatim too, so
            // accumulated evidence, the price anchor, published-headline history and
            // covered-news ids survive a quick restart (otherwise a re-touched unit
            // looks brand-new and the wire could repeat a line it already ran).
            subjectRegistry.restore(a.subjectUnits());
            LOG.info("Restored {} vision entries, {} headlines, {} clusters, {} subject units from agent snapshot.",
                    a.visionCache() != null ? a.visionCache().size() : 0,
                    a.headlines() != null ? a.headlines().size() : 0,
                    a.clusters() != null ? a.clusters().size() : 0,
                    a.subjectUnits() != null ? a.subjectUnits().size() : 0);
        });
        return true;
    }

    /**
     * Rebuilds the cluster set from whatever is already in the repository
     * (i.e. a restored snapshot) without touching the network. Clustering uses
     * title+body embeddings only — vision is skipped here to avoid a cold-cache
     * stampede on startup; image vision caches are warmed asynchronously so the
     * reports fill in over the next ticks.
     */
    private void seedClustersFromRepository() {
        if (!config.getHeadlines().isEnabled()) return;
        try {
            List<RedditThread> threads = repository.getAllThreads();
            if (threads.isEmpty()) return;
            LOG.info("Seeding clusters from {} restored threads (text-only embedding)...", threads.size());

            for (RedditThread t : threads) {
                // Prime delta baselines so restored threads don't read as "new".
                lastSeenScore.put(t.id(), t.score());
                lastSeenComments.put(t.id(), t.numComments());
            }
            for (RedditThread t : threads) {
                // Use vision from the restored cache (cache-only, no recompute)
                // so restored clusters embed with the same signal they had
                // before the restart; threads with no cached vision embed on
                // text alone.
                clusterEngine.assign(t, 0, 0, cachedVisionText(t));
            }
            // One cluster == one thread now — seeding just re-creates each
            // thread's own cluster; there is no merge/prune step to defer.
            for (RedditThread t : threads) {
                prefetchThreadImages(t);
                prefetchCommentImages(t.id());
            }
            LOG.info("Cluster seeding complete: {} clusters.", clusterRegistry.size());
        } catch (Exception e) {
            LOG.error("Cluster seeding failed", e);
        }
    }

    /**
     * Builds an embedding-ready vision block for a thread from the restored
     * vision cache only — never triggers a (slow) fresh analysis. Returns "" if
     * the thread has no images or none are cached yet.
     */
    private String cachedVisionText(RedditThread t) {
        List<String> urls = t.imageUrls();
        if (urls.isEmpty()) return "";
        int n = Math.min(urls.size(), EMBED_GALLERY_IMAGES);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            String desc = brain.describeImageIfCached(urls.get(i));
            if (desc == null || desc.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(desc);
        }
        return sb.toString();
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

            // Activity-windowed gap-fill — only refresh threads that
            // showed activity within the last GAP_FILL_HOT_WINDOW. Reddit
            // threads cool down quickly: the bulk of comments land in the
            // first 1-2 hours and very little arrives after that. Polling
            // an idle thread every cycle costs requests against the anon
            // budget for almost no new content.
            //
            // The window is intentionally generous (45 min) so a thread
            // that picks up a fresh comment burst still gets a chance to
            // re-enter the hot set — and listing-scan still surfaces it
            // independently if it returns to /new via the cluster's new
            // activity.
            //
            // TODO(oauth-login): with OAuth (600 req/min budget) we can
            // widen this back to the full retention window and rescan
            // every thread per cycle without bumping into Reddit's
            // anonymous throttle.
            final long gapFillHotWindowSecs = 45 * 60;
            final long nowEpoch = Instant.now().getEpochSecond();
            List<String> idsToUpdate = repository.getAllThreads().stream()
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

    // -- Vector Clustering --

    private void processUpdates(List<RedditThread> updates) {
        if (!config.getHeadlines().isEnabled())
            return;

        try {
            // Pre-fetch vision in parallel for every thread with a fresh
            // image URL. Vision is the slowest step in the pipeline
            // (~15-25 s per call on gemma4) and serialising it behind
            // the cluster loop turns a 30-thread batch into a 10-min
            // queue. A single prefetch worker runs ahead of the cluster
            // loop on the spare Ollama slot (the other is kept free for
            // the agent's tool loop).
            //
            // The brain caches by URL, so threads we've seen before
            // resolve instantly inside computeIfAbsent — only genuinely
            // new images pay the network + inference cost.
            //
            // describeAll only covers the first EMBED_GALLERY_IMAGES slides:
            // that's all the embedding needs to route the post, and it caps
            // the blocking-join latency regardless of how deep the gallery
            // is. The remaining slides are prefetched async below and shown
            // in the report when ready — so a 10-slide DD deck reaches the
            // headline without stalling clustering.
            // Vision is opt-out (headlines.analyze-images, read live so the toggle
            // takes effect without a restart). When off, visionFutures stays empty:
            // every assign() below falls through to a "" vision block (title+body
            // only) and the fire-and-forget cache-warm paths below no-op too.
            Map<String, CompletableFuture<String>> visionFutures = new HashMap<>();
            if (analyzeImages()) {
                for (RedditThread t : updates) {
                    List<String> urls = t.imageUrls();
                    if (urls.isEmpty()) continue;
                    visionFutures.put(t.id(),
                            CompletableFuture.supplyAsync(() -> describeAll(urls), visionExecutor));
                }
            }

            // Cache-warm the images that don't feed the embedding — gallery
            // slides beyond the embedded set, and the images on top-ranked
            // comments. Fire-and-forget: ReportBuilder reads cache-only, so a
            // cold image simply won't appear this tick and gets re-surfaced
            // once the prefetch settles (see InvestigationCluster.shownImageUrls).
            for (RedditThread t : updates) {
                prefetchThreadImages(t);
                prefetchCommentImages(t.id());
            }

            for (RedditThread t : updates) {
                int[] deltas = computeDeltas(t);
                if (deltas[0] > 0 || deltas[1] > 0) {
                    LOG.info("Update '{}': +{} score, +{} comments", t.title(), deltas[0], deltas[1]);
                }
                // visionFuture is pre-launched above so multiple threads' images
                // analyse in parallel on the vision pool; by the time we reach
                // thread N the early futures are usually already done.
                CompletableFuture<String> vf = visionFutures.get(t.id());
                clusterEngine.assign(t, deltas[0], deltas[1], vf == null ? "" : vf.join());
            }

            // One cluster == one thread: assign() creates or updates each
            // thread's own cluster on arrival; there is no cross-thread merge/
            // prune. Headline generation is driven by AgentCoordinator
            // (subscribed to ClusterRegistry.notifyChange).
        } catch (Exception e) {
            LOG.error("Processing failed", e);
        }
    }

    /**
     * Gallery slides that feed the clustering embedding (and thus block via
     * {@code describeAll}'s join). Kept small so a deep gallery can't stall
     * time-to-headline — the first few slides are plenty to route the post.
     * Slides beyond this are prefetched async for the report only.
     */
    private static final int EMBED_GALLERY_IMAGES = 4;

    /**
     * Prefetches the gallery slides that {@code describeAll} skipped (those
     * beyond {@link #EMBED_GALLERY_IMAGES}). Fire-and-forget — they're shown
     * in the report cache-only once ready. The embedded slides are already
     * being computed by the thread's vision future, so we start past them.
     */
    private void prefetchThreadImages(RedditThread t) {
        if (!analyzeImages()) return; // vision opt-out — no cache-warming
        List<String> urls = t.imageUrls();
        for (int i = EMBED_GALLERY_IMAGES; i < urls.size(); i++) {
            String url = urls.get(i);
            if (brain.isImageCached(url)) continue;
            visionExecutor.submit(() -> brain.describeImage(url));
        }
    }

    /**
     * Submits EVERY image on EVERY comment of {@code threadId} to the vision
     * pool for cache-filling — no top-N cap, no score gate. The wire mirrors
     * the whole room's sentiment, and a downvoted comment's screenshot is
     * sentiment too (often by inversion); filtering by the crowd's "accepted"
     * comments would throw signal away. We only sort by score so high-signal
     * images warm first — nothing is dropped. Fire-and-forget: the report
     * reads cache-only, so a slow backlog just fills in over later ticks
     * (and late arrivals get re-surfaced, see shownImageUrls), never blocking
     * the scrape loop or the agent.
     */
    private void prefetchCommentImages(String threadId) {
        if (!analyzeImages()) return; // vision opt-out — no cache-warming
        List<RedditComment> comments = repository.getCommentsForThread(threadId, 0);
        if (comments.isEmpty()) return;
        comments.stream()
                .filter(c -> !c.imageUrls().isEmpty())
                .sorted((a, b) -> Integer.compare(b.score(), a.score()))
                .forEach(c -> {
                    for (String url : c.imageUrls()) {
                        if (brain.isImageCached(url)) continue;
                        visionExecutor.submit(() -> brain.describeImage(url));
                    }
                });
    }

    /**
     * Live read of the {@code headlines.analyze-images} opt-out (default true).
     * Read per scan, never cached at construction, because {@code SettingsBridge}
     * mutates the in-memory {@link GlobalConfig} — so toggling it off takes effect
     * on the next scan with no restart. When false, all vision is skipped (no
     * {@code describeAll} embedding block, no fire-and-forget cache-warming).
     */
    private boolean analyzeImages() {
        return config.getHeadlines().isAnalyzeImages();
    }

    /**
     * Describes the first {@link #EMBED_GALLERY_IMAGES} slides of a thread's
     * gallery and joins them into one labelled block for the clustering
     * embedding. Deeper slides are intentionally left to async prefetch +
     * the report. Empty descriptions (cache miss + vision failure, or "no
     * image" placeholders) are skipped so the embedding isn't polluted.
     */
    private String describeAll(List<String> urls) {
        if (urls.size() == 1) {
            return brain.describeImage(urls.get(0));
        }
        int n = Math.min(urls.size(), EMBED_GALLERY_IMAGES);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            String desc = brain.describeImage(urls.get(i));
            if (desc == null || desc.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append("[IMAGE ").append(i + 1).append('/').append(n).append("] ").append(desc);
        }
        return sb.toString();
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

}
