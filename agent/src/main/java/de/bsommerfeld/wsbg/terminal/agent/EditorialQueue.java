package de.bsommerfeld.wsbg.terminal.agent;

import com.google.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.ToIntFunction;

/**
 * The compose queue between the parallel prep stage and the compose worker(s)
 * ({@code EditorialPipeline}). Two parts:
 * <ul>
 *   <li>an insertion-ordered list of {@link ComposeJob}s;</li>
 *   <li>an {@code inFlight} key set that makes {@link #offer(ComposeJob)}
 *       <b>add-only</b>: a job whose {@link ComposeJob#id() id} is already queued
 *       OR currently being processed is dropped, never duplicated. There is no
 *       drain-and-refill — the queue only ever grows by genuinely-new work.</li>
 * </ul>
 *
 * <p><b>Dispatch is by live evidence strength, not FIFO.</b> {@link #take(ToIntFunction)}
 * does not return the head; it picks the queued job with the maximum strength as
 * scored <em>at dispatch time</em> by the supplied evaluator (the pipeline scores a
 * unit/cluster by its CURRENT backing-evidence count). A job that accumulates more
 * evidence while it waits therefore rises to the front with <b>no re-insertion and no
 * churn</b> — the item sits in the list exactly once; only the selection order is
 * dynamic. Equal strength falls back to insertion order (the FIFO tie-break), which
 * is what keeps a steady-state equal-strength item from starving among its peers.
 *
 * <p>The id is cleared via {@link #done(ComposeJob)} only when the worker has
 * finished that job, so a subject re-dirtied mid-compose can't sneak a second
 * copy in behind the one being written. (The producer that sees its {@code offer}
 * rejected re-marks the unit dirty, so the fresh evidence is picked up on the next
 * drain once the in-flight copy completes — no lost headline.)
 *
 * <p>Thread-safe: many prep threads + the merge cadence call {@link #offer}; the
 * worker thread(s) call {@link #take}/{@link #done}. A single {@link ReentrantLock}
 * guards the list so the scan-for-max + remove is atomic — two workers taking
 * concurrently never grab the same job nor skip one.
 */
@Singleton
public final class EditorialQueue {

    private static final Logger LOG = LoggerFactory.getLogger(EditorialQueue.class);

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    /** Jobs in insertion order — the strongest is selected on take, earliest-inserted wins ties. */
    private final List<ComposeJob> jobs = new ArrayList<>();

    /** Ids that are queued OR in-flight — the add-only/de-dup guard. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    /** When each queued job was enqueued — feeds the age-promotion anti-starvation bonus. */
    private final Map<ComposeJob, Long> enqueuedAt = new java.util.HashMap<>();

    /** A job waiting longer than this is promoted to the front, no matter what's hotter. */
    static final long AGE_PROMOTE_MS = 5 * 60 * 1000L;
    /** Age-promotion bonus — dwarfs any evidence/first-compose score, so an aged job always wins. */
    static final int AGE_BONUS = 1_000_000;

    /**
     * Enqueues {@code job} iff its id isn't already queued/in-flight.
     *
     * @return {@code true} if it was added, {@code false} if a job with the same id
     *         is already pending (the caller should leave the work marked dirty so it
     *         re-enqueues after the in-flight copy finishes).
     */
    public boolean offer(ComposeJob job) {
        if (job == null) return false;
        if (!inFlight.add(job.id())) {
            // The add-only dedup rejecting a duplicate — exactly what we want to SEE.
            LOG.info("[PIPE] offer SKIP (in-flight) {}", job.id());
            return false;
        }
        lock.lock();
        try {
            jobs.add(job);
            enqueuedAt.put(job, System.currentTimeMillis());
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
        return true;
    }

    /**
     * Blocks until a job is available, then removes and returns the queued job with the
     * highest {@code strength} (evaluated LIVE here, at dispatch time), tie-broken by
     * insertion order. The returned job stays marked in-flight until {@link #done}.
     */
    public ComposeJob take(ToIntFunction<ComposeJob> strength) throws InterruptedException {
        lock.lock();
        try {
            while (jobs.isEmpty()) notEmpty.await();
            long now = System.currentTimeMillis();
            int bestIdx = 0;
            int bestStrength = effectiveStrength(jobs.get(0), strength, now);
            // Strict ">" keeps the earliest-inserted job among equal-strength peers,
            // so equal strength dispatches FIFO and no equal peer can be starved.
            for (int i = 1; i < jobs.size(); i++) {
                int s = effectiveStrength(jobs.get(i), strength, now);
                if (s > bestStrength) {
                    bestStrength = s;
                    bestIdx = i;
                }
            }
            ComposeJob picked = jobs.remove(bestIdx);
            enqueuedAt.remove(picked);
            return picked;
        } finally {
            lock.unlock();
        }
    }

    /** External evidence/first-compose strength PLUS the age-promotion bonus once a job has waited too long. */
    private int effectiveStrength(ComposeJob job, ToIntFunction<ComposeJob> strength, long now) {
        int base = strength.applyAsInt(job);
        long age = now - enqueuedAt.getOrDefault(job, now);
        return age > AGE_PROMOTE_MS ? base + AGE_BONUS : base;
    }

    /** FIFO convenience (equal strength for all) — used by tests/diagnostics. */
    public ComposeJob take() throws InterruptedException {
        return take(j -> 0);
    }

    /** Clears a finished job's id so the same subject/cluster can be queued again. */
    public void done(ComposeJob job) {
        if (job != null) inFlight.remove(job.id());
    }

    /** Number of jobs waiting to be taken (excludes the one(s) being processed). */
    public int size() {
        lock.lock();
        try {
            return jobs.size();
        } finally {
            lock.unlock();
        }
    }

    /** Snapshot of the queued job ids in insertion order — for queue-content diagnostics. */
    public List<String> pendingIds() {
        lock.lock();
        try {
            return jobs.stream().map(ComposeJob::id).toList();
        } finally {
            lock.unlock();
        }
    }

    /** True if a job with this id is queued or in-flight. Test/diagnostics helper. */
    public boolean isPending(String id) {
        return inFlight.contains(id);
    }

    /** Count of jobs queued OR in-flight (the dedup guard's size) — diagnostics. */
    public int inFlightCount() {
        return inFlight.size();
    }

    /** Drops all queued work + in-flight markers (lab "Reset" / shutdown). */
    public void clear() {
        lock.lock();
        try {
            jobs.clear();
            enqueuedAt.clear();
        } finally {
            lock.unlock();
        }
        inFlight.clear();
    }
}
