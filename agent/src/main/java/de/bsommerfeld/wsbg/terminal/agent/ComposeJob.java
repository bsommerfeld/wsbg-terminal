package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.agent.TickerResolver.ResolvedSubject;

import java.util.List;

/**
 * One unit of LLM compose work queued by the prep stage and drained by the
 * compose worker ({@code EditorialPipeline}). A job is a pure pointer into the
 * registries' already-resolved state — the worker reads the live
 * {@link SubjectUnit}/{@link InvestigationCluster} from the SSOT and makes ONE
 * model call, no I/O.
 *
 * <p>Two flavours, matching the two independent headline producers:
 * <ul>
 *   <li>{@link SubjectJob} — one headline for a feed-wide {@link SubjectUnit}
 *       (the editorial atom in prod), keyed by unit id.</li>
 *   <li>{@link ThemeJob} — one cluster-theme headline (the thread's narrative),
 *       keyed by cluster id. Opt-in (off by default).</li>
 * </ul>
 *
 * <p>{@link #id()} is the stable de-dup key used by {@link EditorialQueue} so the
 * same subject/cluster is never queued twice while one is still pending.
 */
public sealed interface ComposeJob permits ComposeJob.SubjectJob, ComposeJob.ThemeJob {

    /** Stable de-dup key — unique per logical job, namespaced by producer. */
    String id();

    /** A per-subject headline job. Carries only the unit id — the unit lives in {@link SubjectRegistry} (SSOT). */
    record SubjectJob(String unitId) implements ComposeJob {
        @Override
        public String id() {
            return "unit:" + unitId;
        }
    }

    /**
     * A cluster-theme headline job. Carries the cluster id PLUS the prep-stage
     * resolved subjects — the cluster's resolved-subject list is not registry-backed
     * (unlike a {@link SubjectUnit}), so it rides along on the job as already-resolved
     * state so the worker still does zero I/O ({@code HeadlineWriter.publish} validates
     * tickers/snapshots against this list, never the model).
     */
    record ThemeJob(String clusterId, List<ResolvedSubject> resolved) implements ComposeJob {
        @Override
        public String id() {
            return "theme:" + clusterId;
        }
    }
}
