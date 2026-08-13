package de.bsommerfeld.wsbg.terminal.agent.tagging;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * The self-supervised context profile of one instrument (research §3.5, the
 * DBpedia-Spotlight TF·ICF idea with the OWN basin as knowledge base): built
 * from high-precision seed articles — hard-key hits and full-name matches —
 * it says what the instrument's coverage actually talks about. An ambiguous
 * single-token hit is then measured against it: the solar-eclipse article has
 * zero overlap with a fintech profile and falls.
 *
 * <p>No seeds → no profile → the ambiguity is decided by the sense arbiter or
 * not at all. Nothing here is curated; the profile follows the basin.
 */
final class ContextProfile {

    /** How many seed articles a profile needs before it may judge. */
    static final int MIN_SEEDS = 3;
    /** Cosine at or above which an ambiguous hit is confirmed. */
    static final double CONFIRM = 0.08;
    /** Cosine at or below which an ambiguous hit is rejected (with enough seeds). */
    static final double REJECT = 0.01;
    /** Profile size — the strongest TF·IDF tokens only. */
    private static final int TOP_TOKENS = 48;

    private final Map<String, Double> weights;
    final int seeds;
    final int atDocCount;

    private ContextProfile(Map<String, Double> weights, int seeds, int atDocCount) {
        this.weights = weights;
        this.seeds = seeds;
        this.atDocCount = atDocCount;
    }

    static ContextProfile build(Collection<DocRecord> seedDocs, Set<String> ownTokens,
            int totalDocs, ToIntFunction<String> docFreq) {
        Map<String, Double> tf = new HashMap<>();
        for (DocRecord d : seedDocs) {
            for (String t : d.tokenSet()) {
                if (t.length() < 3 || ownTokens.contains(t)) continue;
                tf.merge(t, 1.0, Double::sum);
            }
        }
        Map<String, Double> weighted = new HashMap<>();
        for (Map.Entry<String, Double> e : tf.entrySet()) {
            int df = docFreq.applyAsInt(e.getKey());
            if (df <= 0 || totalDocs <= 0) continue;
            double idf = Math.log((double) (totalDocs + 1) / (df + 1));
            if (idf <= 0) continue;
            weighted.put(e.getKey(), e.getValue() * idf);
        }
        Map<String, Double> top = weighted.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue(Comparator.reverseOrder()))
                .limit(TOP_TOKENS)
                .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue()), Map::putAll);
        return new ContextProfile(top, seedDocs.size(), totalDocs);
    }

    boolean usable() {
        return seeds >= MIN_SEEDS && !weights.isEmpty();
    }

    /** Cosine between the profile and the document's token set (binary doc TF). */
    double cosine(DocRecord doc) {
        if (weights.isEmpty()) return 0.0;
        Set<String> tokens = doc.tokenSet();
        double dot = 0.0;
        double profileNorm = 0.0;
        for (Map.Entry<String, Double> e : weights.entrySet()) {
            profileNorm += e.getValue() * e.getValue();
            if (tokens.contains(e.getKey())) dot += e.getValue();
        }
        if (dot == 0.0 || profileNorm == 0.0 || tokens.isEmpty()) return 0.0;
        return dot / (Math.sqrt(profileNorm) * Math.sqrt(tokens.size()));
    }

    /** How many of the cluster's terms the profile itself carries. */
    int overlap(Set<String> clusterTerms) {
        int n = 0;
        for (String t : clusterTerms) {
            if (weights.containsKey(t)) n++;
        }
        return n;
    }

    /** The profile's strongest terms — for arbiter prompts and tests. */
    List<String> topTerms(int k) {
        return weights.keySet().stream().limit(k).toList();
    }
}
