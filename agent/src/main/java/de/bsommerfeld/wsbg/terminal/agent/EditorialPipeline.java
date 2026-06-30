package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.agent.TickerResolver.ResolvedSubject;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
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
 * {@link ReentrantReadWriteLock} (fair) closes that window: the registry fold
 * ({@link EditorialAgent#attributeResolved}) holds the READ lock (shared — folds run
 * concurrently), the merge cadence holds the WRITE lock (exclusive). Crucially the lock
 * wraps ONLY the fold, NOT the preceding extract (LLM) + resolve (Yahoo)
 * ({@link EditorialAgent#resolveClusterSubjects}) — those touch no registry state and run
 * lock-free, so a 10–120 s extract never blocks the merge cadence (which enqueues every
 * compose job). The merge waits at most one in-flight, millisecond-long fold.
 */
@Singleton
public final class EditorialPipeline {

    private static final Logger LOG = LoggerFactory.getLogger(EditorialPipeline.class);

    /** Prep parallelism — resolution is mostly cached I/O + one extract LLM (which the semaphore caps). */
    private static final int PREP_THREADS = 4;
    /**
     * Per-cluster prep cooldown: a cluster re-prepped within this window waits — its fresh
     * evidence accumulates and is extracted ONCE when the window passes. Prep (LLM subject
     * extraction + Yahoo resolution) is the expensive tier; without this a hot thread re-extracts
     * on every new comment, floods the model and starves the compose workers, so the wire never
     * catches up. The first prep of a cluster is immediate; only re-preps are batched.
     */
    private static final long PREP_COOLDOWN_MS = 60_000;
    /** Compose workers — the shared {@code Semaphore(2)} in {@link EditorialAgent} is the real LLM limiter. */
    private static final int WORKER_THREADS = 2;
    /** How often duplicate identities are folded + freshly-dirty units enqueued. */
    private static final long MERGE_INTERVAL_MS = 1_500;
    /**
     * How often stale evidence is rolled back to the context-relief window. This is the
     * documented pipeline step 1 ({@link EditorialAgent#runUnitTick} did it inline); the
     * #3 producer/consumer path replaces that serial tick, so the prune has its own
     * cadence here — without it a {@link SubjectUnit}'s evidence map grows unbounded for
     * the whole process lifetime (and is serialized whole into the snapshot).
     */
    private static final long PRUNE_INTERVAL_MS = 60_000;
    /**
     * Per-unit compose cooldown: after a unit composes, hold off re-composing it for this
     * long even as fresh evidence arrives — the evidence keeps accumulating on the unit and
     * the next compose runs ONCE against the whole batch. Without it, every single new
     * comment re-wakes the unit (one compose per evidence increment), which both floods the
     * compose queue and produces a stream of near-identical "-Update:" lines on a story that
     * hasn't actually moved. A genuine fresh story still surfaces — just batched, not per-tick.
     */
    private static final long COMPOSE_COOLDOWN_MS = 60_000;
    /**
     * Settle delay before a freshly-dirty unit is FIRST composed: give its evidence time to
     * accumulate AND its price/chart time to resolve, so the line is fuller and carries a
     * quote/sparkline instead of firing on a bare first mention. The audience prefers a
     * slightly later but richer headline over an instant thin one.
     */
    private static final long COMPOSE_SETTLE_MS = 30_000;

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
    /** Wall-clock ms a cluster was last prepped — drives the per-cluster prep cooldown (batching). */
    private final Map<String, Long> lastPreppedMs = new ConcurrentHashMap<>();
    /** Clusters whose re-prep is deferred to the end of the cooldown — coalesces the wait. */
    private final Set<String> deferredClusters = ConcurrentHashMap.newKeySet();
    /** Prep tasks actively RUNNING (not just queued) — logged so parallel prep is visible. */
    private final AtomicInteger activePreps = new AtomicInteger();
    /** Last logged queue-depth bucket (size/10) — log only on a threshold crossing, no spam. */
    private final AtomicInteger lastQueueBucket = new AtomicInteger(0);

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
        mergeScheduler.scheduleWithFixedDelay(this::pruneContent,
                PRUNE_INTERVAL_MS, PRUNE_INTERVAL_MS, TimeUnit.MILLISECONDS);
        LOG.info("EditorialPipeline started ({} prep, {} compose worker(s), merge every {}ms, prune every {}ms).",
                PREP_THREADS, WORKER_THREADS, MERGE_INTERVAL_MS, PRUNE_INTERVAL_MS);
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
        // Per-cluster prep cooldown: a cluster prepped within PREP_COOLDOWN_MS waits, so a
        // hot thread's evidence batches into ONE re-extraction instead of flooding the model.
        long last = lastPreppedMs.getOrDefault(clusterId, 0L);
        long since = System.currentTimeMillis() - last;
        if (last > 0 && since < PREP_COOLDOWN_MS) {
            if (deferredClusters.add(clusterId)) {
                mergeScheduler.schedule(() -> {
                    deferredClusters.remove(clusterId);
                    submitCluster(clusterId);
                }, PREP_COOLDOWN_MS - since, TimeUnit.MILLISECONDS);
                LOG.info("[PIPE] cluster {} re-prep deferred {}s (batching)", clusterId,
                        (PREP_COOLDOWN_MS - since) / 1000);
            }
            return;
        }
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
            // extract (LLM) + resolve (Yahoo) hold NO registry lock — they touch no shared
            // registry state, so they run fully parallel across prep threads AND, crucially,
            // never block the merge cadence that enqueues every compose job. The old wide
            // read lock spanned this 10–120 s of work and (fair mode) let the periodic merge
            // writer stall every other prep behind it → the whole pipeline went serial.
            List<ResolvedSubject> resolved = agent.resolveClusterSubjects(clusterId);
            // Only the registry FOLD takes the read lock: shared with other preps' folds,
            // exclusive with the merge write lock. Millisecond critical section.
            registryLock.readLock().lock();
            try {
                agent.attributeResolved(clusterId, subjectRegistry, resolved);
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
            lastPreppedMs.put(clusterId, System.currentTimeMillis()); // start the prep cooldown
            inProgress.remove(clusterId);
            // Replay a request that landed mid-run — but submitCluster now applies the prep
            // cooldown, so the replay batches (deferred) instead of re-extracting back-to-back.
            if (rerunRequested.remove(clusterId)) submitCluster(clusterId);
        }
    }

    // -- merge cadence: fold identities + enqueue freshly-dirty units --

    /** Logs only when the compose-queue depth crosses a 10-job threshold (10/20/30/…), no spam. */
    private void noteQueueDepth(int size) {
        int bucket = size / 10;
        int prev = lastQueueBucket.getAndSet(bucket);
        if (bucket != prev) {
            // Dump the queued ids (insertion order) so we SEE what's piling up + in what order.
            LOG.info("[PIPE] compose queue {} {} jobs ({}) :: {}",
                    bucket > prev ? "▲ over" : "▼ under", Math.max(bucket, prev) * 10, size,
                    queue.pendingIds());
        }
    }

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
            int stale = 0;
            int cooling = 0;
            long nowMs = System.currentTimeMillis();
            for (String unitId : dirty) {
                SubjectUnit unit = subjectRegistry.get(unitId);
                if (unit == null) continue; // merged/removed since it was marked dirty
                // Drop a dirty mark whose evidence an in-flight copy already composed
                // against. Without this, a long in-flight compose lets the 1.5s cadence
                // keep re-marking the unit dirty, and once that copy finishes the lingering
                // mark fires a second, near-identical "-Update:" line over the same facts.
                if (!unit.hasUncomposedEvidence()) {
                    stale++;
                    continue;
                }
                // Settle: hold a freshly-dirty unit until its evidence + price/chart have had
                // COMPOSE_SETTLE_MS to land, so the first line is fuller and carries a quote,
                // not a bare first mention.
                long sinceDirty = nowMs - unit.dirtySinceMs();
                if (unit.dirtySinceMs() > 0 && sinceDirty < COMPOSE_SETTLE_MS) {
                    subjectRegistry.markDirty(unitId);
                    cooling++;
                    continue;
                }
                // Per-unit compose cooldown: a unit composed within COMPOSE_COOLDOWN_MS keeps
                // its dirty mark but is NOT re-enqueued yet — fresh evidence accumulates on it
                // and composes once when the window passes (batching, not one compose per
                // comment). The first compose is gated only by the settle above.
                long since = nowMs - unit.lastComposedAtMs();
                if (unit.lastComposedAtMs() > 0 && since < COMPOSE_COOLDOWN_MS) {
                    subjectRegistry.markDirty(unitId);
                    cooling++;
                    continue;
                }
                if (queue.offer(new ComposeJob.SubjectJob(unitId))) {
                    enqueued++;
                } else {
                    // A copy is still queued/in-flight → put the dirty mark back so this
                    // unit's fresh evidence re-enqueues on the next cadence once it clears.
                    subjectRegistry.markDirty(unitId);
                    LOG.info("[PIPE] re-mark dirty {} (compose in-flight)", unitId);
                }
            }
            noteQueueDepth(queue.size());
            // M>0 is the interesting case (subjects flowing) → INFO; an idle tick → DEBUG.
            if (!dirty.isEmpty()) {
                LOG.info("[PIPE] merge cadence: {} unit(s) dirty → {} enqueued{}{}{} (queue={})",
                        dirty.size(), enqueued,
                        cooling > 0 ? ", " + cooling + " cooling" : "",
                        stale > 0 ? ", " + stale + " stale-dropped" : "",
                        merged > 0 ? " (" + merged + " identity-merged)" : "", queue.size());
            } else {
                LOG.debug("[PIPE] merge cadence: 0 unit(s) dirty{}",
                        merged > 0 ? " (" + merged + " identity-merged)" : "");
            }
        } catch (Exception e) {
            LOG.warn("EditorialPipeline: merge/enqueue failed: {}", e.getMessage());
        }
    }

    // -- context relief: roll stale evidence back to the TTL window --

    /**
     * Prunes each {@link SubjectUnit}'s evidence older than the snapshot TTL (the same
     * rolling window the lab + restore use). Story-memory (published headlines) is kept
     * forever — only evidence is rolled back. Held under the WRITE lock so it is exclusive
     * with the concurrent prep folds, matching {@link #mergeAndEnqueue}'s contract. Runs
     * on the single merge thread, so it never overlaps a merge.
     */
    private void pruneContent() {
        if (!running) return;
        long ttlMinutes = config.getReddit().getSnapshotTtlMinutes();
        if (ttlMinutes <= 0) return; // TTL disabled → unbounded retention is intentional
        try {
            int pruned;
            registryLock.writeLock().lock();
            try {
                pruned = subjectRegistry.pruneContentOlderThan(java.time.Duration.ofMinutes(ttlMinutes));
            } finally {
                registryLock.writeLock().unlock();
            }
            if (pruned > 0) LOG.debug("[PIPE] context-relief: pruned {} stale evidence ref(s)", pruned);
        } catch (Exception e) {
            LOG.warn("EditorialPipeline: context-relief prune failed: {}", e.getMessage());
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
    /**
     * First-compose priority: a subject with NO published headline yet is boosted above any
     * re-compose, so a fresh tip isn't starved behind re-composes of an already-covered hot
     * subject. Larger than any realistic evidence count, smaller than the queue's age-promotion
     * bonus (anti-starvation always wins). Within a tier, live evidence still orders.
     */
    static final int FIRST_COMPOSE_BONUS = 10_000;

    private int strengthOf(ComposeJob job) {
        return switch (job) {
            case ComposeJob.SubjectJob s -> {
                SubjectUnit u = subjectRegistry.get(s.unitId());
                if (u == null) yield -1;
                yield u.evidenceCount() + (u.hasPublishedHeadline() ? 0 : FIRST_COMPOSE_BONUS);
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
