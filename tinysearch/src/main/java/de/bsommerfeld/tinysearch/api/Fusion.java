package de.bsommerfeld.tinysearch.api;

import de.bsommerfeld.tinysearch.page.PageHit;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Merges the engines' hit lists into one: a page found by several engines
 * becomes one hit, and the order is reciprocal rank fusion - each engine
 * adds {@code 1 / (K + place)} to a page's score. Being found by several
 * engines lifts a page; one engine's first place still beats another
 * page's middling places. The engines' own scores are never shown, so
 * places are all there is to go by.
 */
final class Fusion {

    /**
     * How much a first place outweighs the places below it: the larger, the
     * more being found by several engines counts against the order within
     * one. 60 is the value of the method's paper (Cormack et al., 2009).
     */
    private static final int K = 60;

    private Fusion() {
    }

    /** @param pages each engine's hits in its own order */
    static List<SearchHit> merge(Map<SearchEngine, List<PageHit>> pages) {
        Map<String, Candidate> byPage = new LinkedHashMap<>();
        pages.forEach((engine, hits) -> {
            Set<String> seen = new HashSet<>();
            int place = 0;
            for (PageHit hit : hits) {
                String key = key(hit.url());
                if (!seen.add(key)) {
                    continue;
                }
                place++;
                byPage.computeIfAbsent(key, ignored -> new Candidate()).add(engine, place, hit);
            }
        });
        List<Candidate> candidates = new ArrayList<>(byPage.values());
        // Stable: equal scores keep the order they were first seen in.
        candidates.sort(Comparator.comparingDouble((Candidate candidate) -> candidate.score).reversed());
        return candidates.stream().map(Candidate::hit).toList();
    }

    /**
     * What makes two links the same page: host without {@code www.}, path
     * without a trailing slash, and the query - scheme, port and fragment
     * aside.
     */
    static String key(URI url) {
        String host = url.getHost().toLowerCase(Locale.ROOT);
        if (host.startsWith("www.")) {
            host = host.substring(4);
        }
        String path = url.getRawPath() == null ? "" : url.getRawPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return host + path + (url.getRawQuery() == null ? "" : "?" + url.getRawQuery());
    }

    /** One page across the engines that found it. */
    private static final class Candidate {

        final Map<SearchEngine, Integer> ranks = new EnumMap<>(SearchEngine.class);
        double score;
        PageHit best;
        int bestPlace = Integer.MAX_VALUE;
        String snippet = "";

        void add(SearchEngine engine, int place, PageHit hit) {
            ranks.put(engine, place);
            score += 1.0 / (K + place);
            if (place < bestPlace) {
                best = hit;
                bestPlace = place;
            }
            if (snippet.isEmpty()) {
                snippet = hit.snippet();
            }
        }

        SearchHit hit() {
            String shown = best.snippet().isEmpty() ? snippet : best.snippet();
            return new SearchHit(best.url(), best.title(), shown, ranks);
        }
    }
}
