package de.bsommerfeld.wsbg.terminal.price;

import de.bsommerfeld.wsbg.terminal.core.domain.MarketSnapshot;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-TTL cache of resolved EUR snapshots, keyed by ticker (upper) or name.
 * Extracted from {@link FallbackPriceSource} so the caching concern stops being
 * interleaved with venue orchestration.
 *
 * <p>Resolved snapshots (EUR price + day-move + spark) are reused for
 * {@link #CACHE_TTL_SECONDS}s. The same ticker is priced once per subject AND
 * re-priced across many clusters within one editorial tick, and each miss is up
 * to TWO slow browser fetches (info + chart) per source — that serial I/O was the
 * dominant per-tick blocker. Off-hours the underlying value doesn't move,
 * in-session 2 min is well inside the venues' own refresh cadence.
 */
final class SnapshotCache {

    private static final long CACHE_TTL_SECONDS = 120;

    /** A cached resolved snapshot with the epoch-second it was stored. */
    private record Cached(MarketSnapshot snapshot, long storedAt) {}

    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    /** A still-fresh cached snapshot for {@code key}, or empty (miss / expired / null key). */
    Optional<MarketSnapshot> get(String key) {
        if (key == null) return Optional.empty();
        Cached hit = cache.get(key);
        if (hit != null && Instant.now().getEpochSecond() - hit.storedAt() < CACHE_TTL_SECONDS) {
            return Optional.of(hit.snapshot());
        }
        return Optional.empty();
    }

    /** Stores {@code s} under {@code key} (no-op for a null key). */
    void put(String key, MarketSnapshot s) {
        if (key != null) cache.put(key, new Cached(s, Instant.now().getEpochSecond()));
    }
}
