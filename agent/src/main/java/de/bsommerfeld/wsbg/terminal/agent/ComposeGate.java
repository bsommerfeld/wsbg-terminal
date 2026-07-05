package de.bsommerfeld.wsbg.terminal.agent;

/**
 * Evidence-versioning + compose-timing state of a {@link SubjectUnit} (extracted).
 * Answers "has anything actually changed since the last compose?", "is the fresh
 * evidence settled yet?" and "are we still cooling down from the last compose?" —
 * orthogonal to what the evidence <em>is</em>.
 *
 * <p><b>Not internally synchronized:</b> every method is invoked by
 * {@link SubjectUnit} under its {@code synchronized(this)} monitor, so the
 * version-bump + dirty-stamp stay atomic while the unit keeps ONE lock. This state
 * is deliberately NOT snapshotted — a restored unit starts a fresh gate at 0/0 so
 * its re-seeded story isn't re-published.
 */
final class ComposeGate {

    /**
     * Monotonic counter bumped every time genuinely-new evidence is added (the same
     * trigger that marks the unit dirty). Paired with {@link #composedEvidenceVersion}
     * it answers "has anything actually changed since the last compose?".
     */
    private long evidenceVersion;
    /** The {@link #evidenceVersion} the last completed compose ran against. */
    private long composedEvidenceVersion;
    /** Wall-clock ms of the last completed compose — drives the per-unit compose cooldown. */
    private long lastComposedAtMs;
    /** Wall-clock ms the unit first became dirty since its last compose (0 = clean) — drives the settle delay. */
    private long dirtySinceMs;

    /** Called when genuinely-new evidence lands: starts the settle clock (if clean) and bumps the version. */
    void onEvidenceAdded() {
        if (evidenceVersion == composedEvidenceVersion) dirtySinceMs = System.currentTimeMillis();
        evidenceVersion++;
    }

    long evidenceVersion() {
        return evidenceVersion;
    }

    /**
     * Records that a compose ran against {@code version} (captured before the compose
     * started). Monotonic: a stale stamp from a slower path can't move it backwards.
     */
    void markComposedAt(long version) {
        if (version > composedEvidenceVersion) composedEvidenceVersion = version;
        lastComposedAtMs = System.currentTimeMillis();
        dirtySinceMs = 0; // composed → clean; the next fresh evidence restarts the settle clock
    }

    long lastComposedAtMs() {
        return lastComposedAtMs;
    }

    long dirtySinceMs() {
        return dirtySinceMs;
    }

    boolean hasUncomposedEvidence() {
        return evidenceVersion > composedEvidenceVersion;
    }
}
