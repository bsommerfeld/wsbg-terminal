package de.bsommerfeld.wsbg.terminal.agent;

/**
 * One unit of LLM compose work queued by the prep stage and drained by the
 * compose worker ({@code EditorialPipeline}). A job is a pure pointer into the
 * registries' already-resolved state — the worker reads the live
 * {@link SubjectUnit} from the SSOT and makes ONE model call, no I/O.
 *
 * <p>{@link #id()} is the stable de-dup key used by {@link EditorialQueue} so the
 * same subject is never queued twice while one is still pending.
 */
public sealed interface ComposeJob permits ComposeJob.SubjectJob {

    /** Stable de-dup key — unique per logical job, namespaced by producer. */
    String id();

    /** A per-subject headline job. Carries only the unit id — the unit lives in {@link SubjectRegistry} (SSOT). */
    record SubjectJob(String unitId) implements ComposeJob {
        @Override
        public String id() {
            return "unit:" + unitId;
        }
    }
}
