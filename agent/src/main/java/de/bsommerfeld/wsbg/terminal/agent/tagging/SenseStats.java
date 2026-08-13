package de.bsommerfeld.wsbg.terminal.agent.tagging;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToIntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The listed-nowhere ambiguity detector: what a token MEANS in the current
 * basin, read off its collocates. NPMI (Bouma 2009) instead of raw lift —
 * normalized to [-1,1] and far less low-frequency-biased — with a minimum
 * co-occurrence count, exactly the two corrections the PMI measurement round
 * called for. The maximum-lift finding ("growth" ~ lift 3-16, "nvidia" 147+)
 * separated filler from content words but had no concept of SENSE; here the
 * top collocates are additionally CLUSTERED (single-link over pairwise doc
 * co-occurrence): a token with two disjoint strong clusters ("gold" ↔
 * silber/unze AND medaille/olympia) is ambiguous by construction, one with a
 * single cluster is not.
 */
final class SenseStats {

    /** A token's sense picture at one basin size. */
    record Sense(List<Set<String>> clusters, double boundRatio, Set<String> boundPartners,
            double lowercaseRatio, int occurrences, int atDocCount) {

        boolean multiSense() {
            return clusters.size() >= 2;
        }

        /**
         * Whether the basin uses the token as a COMMON WORD rather than a
         * proper name: a meaningful share of its occurrences start lower-case
         * ("trade war", "gold price"), which a company name never does. Only
         * claimed with enough observations to mean something.
         */
        boolean commonWordUsage() {
            return occurrences >= 5 && lowercaseRatio >= 0.25;
        }
    }

    /** Minimum co-occurrence for a collocate to count (low-frequency guard). */
    private static final int MIN_CO_COUNT = 3;
    /** NPMI floor for a strong collocate. */
    private static final double MIN_NPMI = 0.35;
    /** How many top collocates the cluster structure is read from. */
    private static final int TOP_COLLOCATES = 12;
    /** Overlap coefficient joining two collocates into one sense cluster. */
    private static final double CLUSTER_OVERLAP = 0.4;
    /** How many token-bearing documents the statistics are read from at most. */
    private static final int MAX_DOCS = 400;

    private SenseStats() {
    }

    /**
     * Reads the sense picture of {@code token} from the documents that carry
     * it. {@code docFreq} answers basin-wide document frequencies,
     * {@code totalDocs} is the basin size.
     */
    static Sense analyze(String token, Collection<DocRecord> docsWithToken,
            int totalDocs, ToIntFunction<String> docFreq) {
        List<Set<String>> docSets = new ArrayList<>();
        int taken = 0;
        int boundOccurrences = 0;
        int standaloneOccurrences = 0;
        Set<String> partners = new LinkedHashSet<>();
        Pattern standalone = Pattern.compile(
                "(?<![\\p{L}\\p{N}-])" + Pattern.quote(token) + "s?(?![\\p{L}\\p{N}-])");
        Pattern bound = Pattern.compile(
                "(?<![\\p{L}\\p{N}])" + Pattern.quote(token) + "s?-([\\p{L}\\p{N}]+)"
                        + "|([\\p{L}\\p{N}]+)-" + Pattern.quote(token) + "s?(?![\\p{L}\\p{N}])");
        int lowercase = 0;
        int cased = 0;
        Pattern caseProbe = Pattern.compile(
                "(?<![\\p{L}\\p{N}])" + Pattern.quote(token) + "s?(?![\\p{L}\\p{N}])",
                Pattern.CASE_INSENSITIVE);
        for (DocRecord d : docsWithToken) {
            if (taken++ >= MAX_DOCS) break;
            docSets.add(d.tokenSet());
            Matcher sm = standalone.matcher(d.rawNorm);
            while (sm.find()) standaloneOccurrences++;
            Matcher bm = bound.matcher(d.rawNorm);
            while (bm.find()) {
                boundOccurrences++;
                String p = bm.group(1) != null ? bm.group(1) : bm.group(2);
                if (p != null && p.length() >= 3) partners.add(p);
            }
            Matcher cm = caseProbe.matcher(d.caseNorm);
            while (cm.find()) {
                char first = d.caseNorm.charAt(cm.start());
                if (Character.isLetter(first)) {
                    cased++;
                    if (Character.isLowerCase(first)) lowercase++;
                }
            }
        }
        double boundRatio = boundOccurrences + standaloneOccurrences == 0
                ? 0.0
                : (double) boundOccurrences / (boundOccurrences + standaloneOccurrences);
        double lowercaseRatio = cased == 0 ? 0.0 : (double) lowercase / cased;

        List<String> top = topCollocates(token, docSets, totalDocs, docFreq);
        List<Set<String>> clusters = cluster(top, docSets);
        return new Sense(clusters, boundRatio, Set.copyOf(partners), lowercaseRatio, cased, totalDocs);
    }

    private static List<String> topCollocates(String token, List<Set<String>> docSets,
            int totalDocs, ToIntFunction<String> docFreq) {
        if (totalDocs <= 0 || docSets.isEmpty()) return List.of();
        Map<String, Integer> coCount = new HashMap<>();
        for (Set<String> doc : docSets) {
            for (String t : doc) {
                if (t.length() < 3 || t.equals(token) || t.equals(token + "s")) continue;
                coCount.merge(t, 1, Integer::sum);
            }
        }
        record Scored(String t, double npmi) {
        }
        List<Scored> scored = new ArrayList<>();
        int n = totalDocs;
        int tokenDf = docSets.size();
        for (Map.Entry<String, Integer> e : coCount.entrySet()) {
            int co = e.getValue();
            if (co < MIN_CO_COUNT) continue;
            int df = docFreq.applyAsInt(e.getKey());
            if (df <= 0) continue;
            double pxy = (double) co / n;
            double px = (double) tokenDf / n;
            double py = (double) df / n;
            double denom = -Math.log(pxy);
            if (denom <= 0) continue;
            double npmi = Math.log(pxy / (px * py)) / denom;
            if (npmi >= MIN_NPMI) scored.add(new Scored(e.getKey(), npmi));
        }
        scored.sort(Comparator.comparingDouble(Scored::npmi).reversed());
        List<String> top = new ArrayList<>();
        for (int i = 0; i < scored.size() && i < TOP_COLLOCATES; i++) {
            top.add(scored.get(i).t());
        }
        return top;
    }

    /**
     * Single-link clustering of the collocates by their doc co-membership
     * WITHIN the token's documents: collocates that appear together belong to
     * one sense, disjoint groups are separate senses. Singletons are noise.
     */
    private static List<Set<String>> cluster(List<String> collocates, List<Set<String>> docSets) {
        if (collocates.size() < 2) {
            return collocates.isEmpty() ? List.of() : List.of(Set.copyOf(collocates));
        }
        Map<String, Integer> count = new HashMap<>();
        Map<String, Integer> pairCount = new HashMap<>();
        for (Set<String> doc : docSets) {
            List<String> present = new ArrayList<>();
            for (String c : collocates) {
                if (doc.contains(c)) present.add(c);
            }
            for (String c : present) count.merge(c, 1, Integer::sum);
            for (int i = 0; i < present.size(); i++) {
                for (int j = i + 1; j < present.size(); j++) {
                    pairCount.merge(pairKey(present.get(i), present.get(j)), 1, Integer::sum);
                }
            }
        }
        Map<String, List<String>> adjacency = new HashMap<>();
        for (int i = 0; i < collocates.size(); i++) {
            for (int j = i + 1; j < collocates.size(); j++) {
                String a = collocates.get(i);
                String b = collocates.get(j);
                int co = pairCount.getOrDefault(pairKey(a, b), 0);
                int min = Math.min(count.getOrDefault(a, 0), count.getOrDefault(b, 0));
                if (min <= 0) continue;
                if ((double) co / min >= CLUSTER_OVERLAP) {
                    adjacency.computeIfAbsent(a, k -> new ArrayList<>()).add(b);
                    adjacency.computeIfAbsent(b, k -> new ArrayList<>()).add(a);
                }
            }
        }
        List<Set<String>> clusters = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String c : collocates) {
            if (seen.contains(c) || !adjacency.containsKey(c)) continue;
            Set<String> component = new LinkedHashSet<>();
            Deque<String> queue = new ArrayDeque<>();
            queue.add(c);
            seen.add(c);
            while (!queue.isEmpty()) {
                String cur = queue.poll();
                component.add(cur);
                for (String next : adjacency.getOrDefault(cur, List.of())) {
                    if (seen.add(next)) queue.add(next);
                }
            }
            if (component.size() >= 2) clusters.add(component);
        }
        // No structure at all: treat the whole collocate list as ONE sense.
        if (clusters.isEmpty() && !collocates.isEmpty()) {
            clusters.add(new LinkedHashSet<>(collocates));
        }
        return clusters;
    }

    private static String pairKey(String a, String b) {
        return a.compareTo(b) < 0 ? a + ' ' + b : b + ' ' + a;
    }
}
