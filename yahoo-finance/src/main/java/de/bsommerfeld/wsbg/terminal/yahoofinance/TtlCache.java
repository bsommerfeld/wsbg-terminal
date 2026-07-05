package de.bsommerfeld.wsbg.terminal.yahoofinance;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tiny module-private TTL cache. Wraps every value in a {@link Entry} carrying
 * its store time, so {@code null} values are cached as first-class entries
 * (distinct from a miss) — the Yahoo client relies on this to negatively-cache
 * a {@code null} snapshot for a 404 symbol and NOT re-fetch it for the TTL.
 *
 * <p>The epoch-second "now" is passed in by the caller (each fetch method takes
 * one {@code Instant.now().getEpochSecond()} snapshot and reuses it), so the
 * cache never reads the clock itself.
 */
final class TtlCache<K, V> {

    /** A stored value plus the epoch-second it was written. {@code value} may be null. */
    record Entry<V>(V value, long storedAt) {
    }

    private final Map<K, Entry<V>> map = new ConcurrentHashMap<>();
    private final long ttlSeconds;

    TtlCache(long ttlSeconds) {
        this.ttlSeconds = ttlSeconds;
    }

    /**
     * The still-fresh entry for {@code key}, or {@code null} if absent/expired.
     * A returned entry may itself hold a {@code null} {@link Entry#value()} — that
     * is a valid, fresh negative cache hit, not a miss.
     */
    Entry<V> getFresh(K key, long nowSeconds) {
        Entry<V> e = map.get(key);
        return (e != null && nowSeconds - e.storedAt() < ttlSeconds) ? e : null;
    }

    void put(K key, V value, long nowSeconds) {
        map.put(key, new Entry<>(value, nowSeconds));
    }

    void clear() {
        map.clear();
    }
}
