package de.bsommerfeld.tinysearch.api;

import java.net.URI;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a search found: the hits of every engine merged into one list, and
 * how each engine fared.
 *
 * @param query   what was searched for
 * @param hits    every page found, once, best first: a page high up on
 *                several engines beats one high up on a single engine
 * @param reports how each engine asked fared, in {@link SearchEngine} order
 */
public record SearchResults(String query, List<SearchHit> hits, Map<SearchEngine, EngineReport> reports) {

    public SearchResults {
        Objects.requireNonNull(query, "query");
        hits = List.copyOf(hits);
        reports = reports.isEmpty() ? Map.of() : Collections.unmodifiableMap(new EnumMap<>(reports));
    }

    /** The addresses of {@link #hits()}, in the same order. */
    public List<URI> urls() {
        return hits.stream().map(SearchHit::url).toList();
    }

    /** The hits one engine found, in its own order. */
    public List<SearchHit> hits(SearchEngine engine) {
        return hits.stream()
                .filter(hit -> hit.ranks().containsKey(engine))
                .sorted(Comparator.comparingInt(hit -> hit.ranks().get(engine)))
                .toList();
    }
}
