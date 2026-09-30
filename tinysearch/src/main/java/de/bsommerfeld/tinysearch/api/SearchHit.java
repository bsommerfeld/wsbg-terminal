package de.bsommerfeld.tinysearch.api;

import java.net.URI;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One page the search found, however many engines found it.
 *
 * @param url     where it is - the engine's own redirect already resolved
 * @param title   its title as the best-placed engine shows it
 * @param snippet the text the engine shows under the title; empty if none did
 * @param ranks   each engine that found it, with its place there (1 = first)
 */
public record SearchHit(URI url, String title, String snippet, Map<SearchEngine, Integer> ranks) {

    public SearchHit {
        Objects.requireNonNull(url, "url");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(snippet, "snippet");
        if (ranks.isEmpty()) {
            throw new IllegalArgumentException("a hit needs an engine that found it");
        }
        ranks = Collections.unmodifiableMap(new EnumMap<>(ranks));
    }

    /** The engines that found it. */
    public Set<SearchEngine> engines() {
        return ranks.keySet();
    }
}
