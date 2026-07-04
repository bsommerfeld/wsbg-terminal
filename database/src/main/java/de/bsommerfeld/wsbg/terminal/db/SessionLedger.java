package de.bsommerfeld.wsbg.terminal.db;

import de.bsommerfeld.wsbg.terminal.db.AgentRepository.HeadlineRecord;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which headlines belong to the CURRENT session — published live this
 * run, or restored from the short-TTL snapshot — as opposed to those merely
 * re-seeded from the permanent archive at startup.
 *
 * <p>This is the rule "Archiv löschen" leans on: it drops the archived history
 * from the wire while keeping the live session intact
 * ({@link AgentRepository#clearArchiveKeepSession()}). Identity is the one shared
 * {@link HeadlineIdentity} formula. Backed by a concurrent key-set so the
 * live-publish path and reads never contend.
 */
final class SessionLedger {

    private final Set<String> identities = ConcurrentHashMap.newKeySet();

    /** Marks a record as belonging to this session. */
    void markLive(HeadlineRecord r) {
        identities.add(HeadlineIdentity.of(r));
    }

    /** True if the record was live-published or snapshot-restored this session. */
    boolean contains(HeadlineRecord r) {
        return identities.contains(HeadlineIdentity.of(r));
    }

    void clear() {
        identities.clear();
    }
}
