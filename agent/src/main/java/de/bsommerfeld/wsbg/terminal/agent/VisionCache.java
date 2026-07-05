package de.bsommerfeld.wsbg.terminal.agent;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Per-URL vision-description cache (extracted from {@link AgentBrain}). A given
 * image is rendered at most once per session regardless of how many times it
 * appears; failed analyses are cached too (a broken image is not re-tried within
 * the session). The compute function ({@link AgentBrain#see}) is supplied by the
 * caller so the cache carries no model coupling.
 */
final class VisionCache {

    private final Map<String, String> byUrl = new ConcurrentHashMap<>();

    /**
     * Returns the description for {@code url}, computing it via {@code compute} on
     * first hit and reusing the result thereafter. Empty string for a null/blank URL.
     */
    String describe(String url, Function<String, String> compute) {
        if (url == null || url.isEmpty())
            return "";
        return byUrl.computeIfAbsent(url, compute);
    }

    /**
     * Lookup-only read: the description if vision has already been computed for
     * {@code url}, otherwise empty string — never triggers analysis.
     */
    String ifCached(String url) {
        if (url == null || url.isEmpty())
            return "";
        return byUrl.getOrDefault(url, "");
    }

    /**
     * Returns {@code true} if {@code url} has already been described, regardless of
     * whether the description is empty (failed analyses are cached too).
     */
    boolean isCached(String url) {
        return url != null && !url.isEmpty() && byUrl.containsKey(url);
    }

    /** Snapshot of the cache for persistence. */
    Map<String, String> export() {
        return new HashMap<>(byUrl);
    }

    /** Restores a persisted cache without clobbering live entries. */
    void importAll(Map<String, String> cache) {
        if (cache == null) return;
        cache.forEach(byUrl::putIfAbsent);
    }
}
