package de.bsommerfeld.wsbg.terminal.db;

import de.bsommerfeld.wsbg.terminal.db.HeadlineRecord;

/**
 * The single source of truth for a headline's append-idempotency identity.
 *
 * <p>The archive's append idempotency and the wire's session-tracking BOTH key
 * on this formula ({@code createdAt | clusterId | headline}). It used to be
 * copy-pasted into {@link HeadlineArchive} and {@link AgentRepository}, with a
 * comment warning the two must stay in sync — a real correctness landmine. There
 * is now exactly one definition; both callers route through it.
 */
final class HeadlineIdentity {

    private HeadlineIdentity() {
    }

    /** Identity key of a headline record — {@code createdAt|clusterId|headline}. */
    static String of(HeadlineRecord r) {
        return r.createdAt() + "|" + r.clusterId() + "|" + r.headline();
    }
}
