package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bridges {@link ClusterRegistry} updates into the {@link EditorialPipeline} (#3).
 *
 * <p>Since the #3 cutover the coordinator no longer runs the editorial work itself
 * (the old serial {@code EditorialAgent.runUnitTick}); its role shrinks to
 * <b>debouncing the notification flurries</b> a Reddit scan produces and handing the
 * coalesced set of changed clusters to the pipeline, which preps them in parallel and
 * drains a FIFO compose queue. Submission is async, so a "tick" here just drains the
 * dirty set and submits.
 *
 * <p>
 * Two guards remain:
 * <ul>
 * <li><b>Debounce</b>: a {@code notifyChange} burst within {@code DEBOUNCE_MS} ms
 * coalesces into a single drain+submit. Reddit scans always produce flurries.</li>
 * <li><b>Follow-up</b>: changes that arrive during/after a drain stay in the
 * {@link ClusterRegistry} dirty set and schedule another drain (the pipeline itself
 * coalesces a cluster that's still being prepped).</li>
 * </ul>
 */
@Singleton
public class AgentCoordinator {

    private static final Logger LOG = LoggerFactory.getLogger(AgentCoordinator.class);
    /**
     * Production debounce window — pizzeria mode. 3 s is long enough to
     * coalesce the typical 2-3 thread arrivals that land within a few
     * hundred ms of each other after a Reddit scan starts, but short
     * enough that the first headline lands within seconds of the first
     * cluster appearing. The earlier 30 s default was waiting for the
     * full scan cycle to settle, which is the wrong trade — the agent
     * can re-tick on the leftover dirty IDs after each cluster is
     * handled (the in-flight gate already coalesces those follow-ups).
     */
    private static final long DEFAULT_DEBOUNCE_MS = 3_000;

    private final ClusterRegistry clusterRegistry;
    private final EditorialPipeline pipeline;
    private final long debounceMs;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "agent-coordinator");
                t.setDaemon(true);
                return t;
            });

    private final AtomicReference<ScheduledFuture<?>> pending = new AtomicReference<>();
    private final ConcurrentHashMap<String, Boolean> inFlight = new ConcurrentHashMap<>();

    @Inject
    public AgentCoordinator(ClusterRegistry clusterRegistry, EditorialPipeline pipeline) {
        this(clusterRegistry, pipeline, DEFAULT_DEBOUNCE_MS);
    }

    AgentCoordinator(ClusterRegistry clusterRegistry, EditorialPipeline pipeline, long debounceMs) {
        this.clusterRegistry = clusterRegistry;
        this.pipeline = pipeline;
        this.debounceMs = debounceMs;
        this.clusterRegistry.subscribeToChanges(this::onChange);
        LOG.info("AgentCoordinator initialized (debounce {}ms).", debounceMs);
    }

    private void onChange(Set<String> changedIds) {
        // Schedule a run unless one is already pending — the dirty set on the
        // registry is the source of truth, so coalescing is safe.
        ScheduledFuture<?> prev = pending.get();
        if (prev != null && !prev.isDone())
            return;

        ScheduledFuture<?> next = scheduler.schedule(this::tick, debounceMs, TimeUnit.MILLISECONDS);
        pending.set(next);
    }

    private void tick() {
        Set<String> drained = clusterRegistry.drainDirty();
        if (drained.isEmpty()) {
            LOG.debug("AgentCoordinator tick: nothing dirty, skipping.");
            return;
        }

        // Mark in-flight to suppress redundant notifications during the run.
        Set<String> snapshot = new HashSet<>(drained);
        for (String id : snapshot)
            inFlight.put(id, Boolean.TRUE);

        long t0 = System.currentTimeMillis();
        try {
            LOG.info("AgentCoordinator tick: {} dirty cluster(s) → {}", drained.size(), drained);
            // #3 cutover: hand the changed clusters to the producer/consumer pipeline
            // (parallel prep → FIFO compose queue), not the old serial runUnitTick.
            // Submission is async — the pipeline coalesces + drains on its own pools.
            pipeline.submitClusters(drained);
        } catch (Exception e) {
            LOG.error("AgentCoordinator tick failed", e);
        } finally {
            for (String id : snapshot)
                inFlight.remove(id);
            long dt = System.currentTimeMillis() - t0;
            LOG.info("AgentCoordinator tick finished in {}ms", dt);

            // If something arrived during the run, schedule a follow-up.
            if (!clusterRegistry.peekDirty().isEmpty()) {
                ScheduledFuture<?> next = scheduler.schedule(this::tick,
                        debounceMs, TimeUnit.MILLISECONDS);
                pending.set(next);
            }
        }
    }

    public void shutdown() {
        scheduler.shutdownNow();
    }
}
