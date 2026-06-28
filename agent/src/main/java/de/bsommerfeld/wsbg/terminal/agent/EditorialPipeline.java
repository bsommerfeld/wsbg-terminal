package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.agent.TickerResolver.ResolvedSubject;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The two-tier producer/consumer editorial pipeline (#3) — replaces the serial
 * batch tick ({@link EditorialAgent#runUnitTick}) in production wiring while leaving
 * that method intact as a fallback.
 *
 * <h3>Tier 1 — prep pool (PARALLEL)</h3>
 * Each changed cluster is prepped on a small daemon pool: extract its subjects
 * (one LLM call), resolve ticker/news/price (mostly cached I/O), and attribute the
 * evidence into the feed-wide {@link SubjectRegistry} — i.e. the unchanged
 * {@link EditorialAgent#attributeCluster}. Resolution thus runs for many clusters
 * at once instead of behind one serial loop. If cluster-theme is enabled, prep also
 * enqueues a {@link ComposeJob.ThemeJob} carrying the prep-resolved subjects.
 * Coalescing: a cluster already being prepped is not re-submitted; a re-request that
 * lands during its run is replayed once the run finishes, so fresh evidence is never
 * dropped.
 *
 * <h3>Merge cadence (single thread)</h3>
 * {@link SubjectRegistry#mergeIdentities} is run on a light fixed cadence rather than
 * per prep task. <b>Why a cadence, not per-cluster:</b> merge scans + mutates the
 * WHOLE registry (folds name units into ticker units), so running it from several
 * prep threads at once would race and could <em>swallow</em> evidence. A single-threaded
 * cadence under the write lock (below) runs it exclusively, after the parallel attributes
 * have settled, so duplicates are folded BEFORE a unit is composed and no subject is lost.
 * The cadence then drains the registry's dirty set and enqueues a
 * {@link ComposeJob.SubjectJob} per dirty unit (re-marking any whose enqueue is rejected
 * because a copy is still in-flight, so it re-queues after that copy completes).
 *
 * <h3>Tier 2 — compose worker(s)</h3>
 * Worker threads drain the {@link EditorialQueue} FIFO by arrival order and run ONE
 * model call each ({@link EditorialAgent#composeAndPublishUnit} /
 * {@link EditorialAgent#runThemeJob}), then publish. There is intentionally NO forced
 * cluster→subject adjacency: a slower-resolving subject simply lands later.
 *
 * <h3>LLM concurrency cap</h3>
 * Both tiers funnel every model call through the single {@link EditorialAgent} instance,
 * which holds a {@code Semaphore(2)} around the actual {@code model.chat} call — so prep
 * extraction + worker composition together never exceed Ollama's {@code NUM_PARALLEL=2}.
 *
 * <h3>Registry concurrency</h3>
 * {@link SubjectUnit} mutations are already synchronized and {@link SubjectRegistry} is
 * ConcurrentHashMap-backed, so concurrent {@code attribute} from many prep threads is
 * safe on its own. The one unsafe interaction is {@code attribute} overlapping
 * {@code mergeIdentities} (absorb could miss a concurrently-added evidence ref). A
 * {@link ReentrantReadWriteLock} (fair, to avoid writer starvation) closes that window:
 * prep attributes hold the READ lock (shared — they run concurrently), the merge cadence
 * holds the WRITE lock (exclusive). The read lock spans the whole prep call for simplicity;
 * the merge then waits at most one in-flight extract, which is acceptable for a background
 * cadence.
 */
@Singleton
public final class EditorialPipeline {

    private static final Logger LOG = LoggerFactory.getLogger(EditorialPipeline.class);

    /** Prep parallelism — resolution is mostly cached I/O + one extract LLM (which the semaphore caps). */
    private static final int PREP_THREADS = 4;
    /** Compose workers — the shared {@code Semaphore(2)} in {@link EditorialAgent} is the real LLM limiter. */
    private static final int WORKER_THREADS = 2;
    /** How often duplicate identities are folded + freshly-dirty units enqueued. */
    private static final long MERGE_INTERVAL_MS = 1_500;

    private final EditorialAgent agent;
    private final ClusterRegistry clusterRegistry;
    private final SubjectRegistry subjectRegistry;
    private final EditorialQueue queue;
    private final GlobalConfig config;

    private final ExecutorService prepPool =
            Executors.newFixedThreadPool(PREP_THREADS, daemonFactory("editorial-prep"));
    private final ExecutorService workerPool =
            Executors.newFixedThreadPool(WORKER_THREADS, daemonFactory("editorial-worker"));
    private final ScheduledExecutorService mergeScheduler =
            Executors.newSingleThreadScheduledExecutor(daemonFactory("editorial-merge"));

    /** Clusters with a prep task queued/running — coalesces repeat submits. */
    private final Set<String> inProgress = ConcurrentHashMap.newKeySet();
    /** Clusters re-requested while their prep ran — replayed once it finishes (no lost evidence). */
    private final Set<String> rerunRequested = ConcurrentHashMap.newKeySet();
    /** Prep tasks actively RUNNING (not just queued) — logged so parallel prep is visible. */
    private final AtomicInteger activePreps = new AtomicInteger();

    /**
     * Fair RW lock: prep {@code attribute} = read (shared), merge cadence = write
     * (exclusive). Fair mode keeps the merge writer from starving under a steady
     * stream of prep readers on a busy cold start.
     */
    private final ReentrantReadWriteLock registryLock = new ReentrantReadWriteLock(true);

    private volatile boolean running = true;

    @Inject
    public EditorialPipeline(EditorialAgent agent, ClusterRegistry clusterRegistry,
            SubjectRegistry subjectRegistry, EditorialQueue queue, GlobalConfig config) {
        this.agent = agent;
        this.clusterRegistry = clusterRegistry;
        this.subjectRegistry = subjectRegistry;
        this.queue = queue;
        this.config = config;

        for (int i = 0; i < WORKER_THREADS; i++) workerPool.submit(this::workerLoop);
        mergeScheduler.scheduleWithFixedDelay(this::mergeAndEnqueue,
                MERGE_INTERVAL_MS, MERGE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        LOG.info("EditorialPipeline started ({} prep, {} compose worker(s), merge every {}ms).",
                PREP_THREADS, WORKER_THREADS, MERGE_INTERVAL_MS);
    }

    // -- submission (called by AgentCoordinator after debounce) --

    /** Submits a batch of changed clusters to the prep pool. */
    public void submitClusters(Set<String> clusterIds) {
        if (clusterIds == null) return;
        LOG.info("[PIPE] submit: {} cluster(s) → prep", clusterIds.size());
        for (String id : clusterIds) submitCluster(id);
    }

    /**
     * Submits one changed cluster to the prep pool, coalescing if it's already being
     * prepped (the duplicate is remembered and replayed once the in-flight prep ends,
     * so evidence that arrives mid-run isn't lost).
     */
    public void submitCluster(String clusterId) {
        if (clusterId == null || !running) return;
        if (!inProgress.add(clusterId)) {
            rerunRequested.add(clusterId);
            LOG.info("[PIPE] cluster {} already prepping → rerun queued", clusterId);
            return;
        }
        prepPool.submit(() -> prep(clusterId));
    }

    // -- tier 1: prep --

    private void prep(String clusterId) {
        int active = activePreps.incrementAndGet();
        LOG.info("[PIPE] prep START cluster {} (prep-active={})", clusterId, active);
        int themeEnqueued = 0;
        try {
            List<ResolvedSubject> resolved;
            // READ lock spans the whole attribute (extract + resolve + fold): the LLM/Yahoo
            // work doesn't touch the registry, but folding does, and the read lock simply
            // keeps it from overlapping a merge. Reads are shared, so prep stays parallel.
            registryLock.readLock().lock();
            try {
                resolved = agent.attributeCluster(clusterId, subjectRegistry);
            } finally {
                registryLock.readLock().unlock();
            }
            // The per-subject SubjectJobs are enqueued by the merge cadence (after
            // identity-merge), NOT here — so this log reports only the theme. The cluster's
            // own THEME line is opt-in and enqueued here, carrying the prep-resolved
            // subjects so the worker does no I/O.
            if (config.getHeadlines().isClusterThemeEnabled()
                    && queue.offer(new ComposeJob.ThemeJob(clusterId, resolved))) {
                themeEnqueued = 1;
            }
        } catch (Exception e) {
            LOG.warn("EditorialPipeline: prep for cluster {} failed: {}", clusterId, e.getMessage());
        } finally {
            activePreps.decrementAndGet();
            // K subject job(s) are NOT counted here: subjects are enqueued by the merge
            // cadence (its own [PIPE] line), so this reports the theme + the live queue depth.
            LOG.info("[PIPE] prep DONE cluster {} → theme={} enqueued, subjects via merge cadence (queue={})",
                    clusterId, themeEnqueued, queue.size());
            inProgress.remove(clusterId);
            // Replay a request that landed mid-run so fresh evidence is re-attributed.
            if (rerunRequested.remove(clusterId)) submitCluster(clusterId);
        }
    }

    // -- merge cadence: fold identities + enqueue freshly-dirty units --

    private void mergeAndEnqueue() {
        if (!running) return;
        try {
            Set<String> dirty;
            int merged;
            registryLock.writeLock().lock();
            try {
                merged = subjectRegistry.mergeIdentities();
                dirty = subjectRegistry.drainDirty();
            } finally {
                registryLock.writeLock().unlock();
            }
            int enqueued = 0;
            for (String unitId : dirty) {
                if (queue.offer(new ComposeJob.SubjectJob(unitId))) {
                    enqueued++;
                } else {
                    // A copy is still queued/in-flight → put the dirty mark back so this
                    // unit's fresh evidence re-enqueues on the next cadence once it clears.
                    subjectRegistry.markDirty(unitId);
                    LOG.info("[PIPE] re-mark dirty {} (compose in-flight)", unitId);
                }
            }
            // M>0 is the interesting case (subjects flowing) → INFO; an idle tick → DEBUG.
            if (!dirty.isEmpty()) {
                LOG.info("[PIPE] merge cadence: {} unit(s) dirty → {} enqueued{} (queue={})",
                        dirty.size(), enqueued,
                        merged > 0 ? " (" + merged + " identity-merged)" : "", queue.size());
            } else {
                LOG.debug("[PIPE] merge cadence: 0 unit(s) dirty{}",
                        merged > 0 ? " (" + merged + " identity-merged)" : "");
            }
        } catch (Exception e) {
            LOG.warn("EditorialPipeline: merge/enqueue failed: {}", e.getMessage());
        }
    }

    // -- tier 2: compose worker --

    private void workerLoop() {
        while (running && !Thread.currentThread().isInterrupted()) {
            ComposeJob job;
            try {
                // Dispatch by LIVE evidence strength (not FIFO): the strongest queued job
                // wins, scored at this instant, so a job that gathered evidence while waiting
                // rises to the front without ever leaving/re-entering the queue.
                job = queue.take(this::strengthOf);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            // queue depth + free LLM permits at pickup time → shows strength drain + LLM contention.
            LOG.info("[PIPE] worker TAKE {} (queue={}, llm-permits-free={})",
                    job.id(), queue.size(), agent.availableLlmPermits());
            try {
                process(job);
            } catch (Exception e) {
                LOG.warn("EditorialPipeline: compose job {} failed: {}", job.id(), e.getMessage());
            } finally {
                queue.done(job);
                LOG.info("[PIPE] worker DONE {} (queue={})", job.id(), queue.size());
            }
        }
    }

    private void process(ComposeJob job) {
        switch (job) {
            case ComposeJob.SubjectJob s -> {
                SubjectUnit u = subjectRegistry.get(s.unitId());
                if (u == null) return; // merged/removed since it was enqueued — nothing to do
                agent.composeAndPublishUnit(u);
            }
            case ComposeJob.ThemeJob t -> agent.runThemeJob(t.clusterId(), t.resolved());
        }
    }

    /**
     * Live evidence strength of a queued job, read fresh from the SSOT registries at
     * dispatch time so a job that accumulated evidence while waiting ranks higher.
     * Pure evidence COUNT (no ticker/IMPORTANT bias, by directive) on one comparable
     * scale across both job kinds:
     * <ul>
     *   <li>{@link ComposeJob.SubjectJob} → the unit's live evidence count
     *       ({@link SubjectUnit#evidenceCount()});</li>
     *   <li>{@link ComposeJob.ThemeJob} → the cluster's backing material
     *       ({@code threadCount + totalComments}).</li>
     * </ul>
     * A unit/cluster removed or merged away since enqueue scores {@code -1} so it sorts
     * last (the worker's {@code process} then no-ops it and {@code done()} clears its id).
     */
    private int strengthOf(ComposeJob job) {
        return switch (job) {
            case ComposeJob.SubjectJob s -> {
                SubjectUnit u = subjectRegistry.get(s.unitId());
                yield u == null ? -1 : u.evidenceCount();
            }
            case ComposeJob.ThemeJob t -> {
                InvestigationCluster c = clusterRegistry.getCluster(t.clusterId());
                yield c == null ? -1 : c.threadCount + c.totalComments;
            }
        };
    }

    // -- lifecycle --

    /** Stops the prep/worker/merge pools. Called from the app shutdown sequence before Ollama goes down. */
    public void shutdown() {
        running = false;
        prepPool.shutdownNow();
        workerPool.shutdownNow();
        mergeScheduler.shutdownNow();
        LOG.info("EditorialPipeline stopped.");
    }

    private static ThreadFactory daemonFactory(String name) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, name + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
