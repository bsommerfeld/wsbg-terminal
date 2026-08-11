package de.bsommerfeld.wsbg.terminal.instruments;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The searchable structure over a corpus snapshot plus its lexical ranking —
 * pure and IO-free (extracted from {@code InstrumentCorpus}, 2026-07-04). Built
 * once per refresh/load and swapped in atomically by the corpus; the entries and
 * token maps are effectively immutable after {@link #build}.
 */
record InstrumentIndex(List<InstrumentEntry> entries, Map<String, List<Integer>> byToken,
                       Map<String, List<String>> tokensByPrefix,
                       Map<String, List<String>> tokensByTrigram) {

    static final InstrumentIndex EMPTY =
            new InstrumentIndex(List.of(), Map.of(), Map.of(), Map.of());

    static InstrumentIndex build(List<InstrumentEntry> entries) {
        Map<String, List<Integer>> byToken = new HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            InstrumentEntry e = entries.get(i);
            Set<String> ts = tokens(e.name());
            ts.addAll(tokens(e.symbol()));
            for (String t : ts) byToken.computeIfAbsent(t, k -> new ArrayList<>()).add(i);
        }
        Map<String, List<String>> byPrefix = new HashMap<>();
        // The trigram posting list is what turns the near-miss lookup into a lookup:
        // a corrupted spelling still shares most of its trigrams with the word it
        // corrupts, so the candidate set is reached without touching the other
        // fourteen-thousand-odd entries.
        Map<String, List<String>> byTrigram = new HashMap<>();
        for (String t : byToken.keySet()) {
            if (t.length() >= 2) {
                byPrefix.computeIfAbsent(t.substring(0, 2), k -> new ArrayList<>()).add(t);
            }
            if (t.length() >= MIN_FUZZY_LENGTH) {
                for (String g : trigrams(t)) {
                    byTrigram.computeIfAbsent(g, k -> new ArrayList<>()).add(t);
                }
            }
        }
        return new InstrumentIndex(List.copyOf(entries), byToken, byPrefix, byTrigram);
    }

    int size() {
        return entries.size();
    }

    /**
     * Top-{@code k} entries whose NAME or SYMBOL lexically matches the query:
     * shared significant tokens rank first (more shared = higher), a token
     * PREFIX hit (query token is a prefix of a name token or vice versa) counts
     * half. Deterministic; returns fewer than {@code k} when the corpus has no
     * plausible candidates at all.
     */
    List<InstrumentEntry> search(String query, int k) {
        if (query == null || query.isBlank() || entries.isEmpty() || k <= 0) return List.of();
        Set<String> qTokens = tokens(query);
        if (qTokens.isEmpty()) return List.of();

        Map<Integer, Double> scores = new HashMap<>();
        for (String qt : qTokens) {
            List<Integer> exact = byToken.get(qt);
            if (exact != null) {
                for (int i : exact) scores.merge(i, 1.0, Double::sum);
            }
            // Prefix pass over the token dictionary (bounded: only tokens sharing
            // the first 2 chars are candidates).
            if (qt.length() >= 3) {
                List<String> bucket = tokensByPrefix.get(qt.substring(0, 2));
                if (bucket != null) {
                    for (String t : bucket) {
                        if (t.equals(qt)) continue;
                        if (t.startsWith(qt) || qt.startsWith(t)) {
                            for (int i : byToken.get(t)) scores.merge(i, 0.5, Double::sum);
                        }
                    }
                }
            }
        }
        return scores.entrySet().stream()
                .sorted((a, b) -> {
                    int c = Double.compare(b.getValue(), a.getValue());
                    if (c != 0) return c;
                    // tie-break: shorter name = more specific match
                    return Integer.compare(entries.get(a.getKey()).name().length(),
                            entries.get(b.getKey()).name().length());
                })
                .limit(k)
                .map(e -> entries.get(e.getKey()))
                .toList();
    }

    // -- fuzzy neighbourhood --

    /**
     * How much of a corpus token's trigrams a query token must share before the pair
     * is even scored. Cheap pre-filter: it keeps the candidate set small without
     * deciding anything.
     */
    private static final double TRIGRAM_FLOOR = 0.34;

    /** Minimum similarity a neighbour must reach to be offered at all. */
    private static final double NEAREST_FLOOR = 0.62;

    /** Below this length a word is too short for distance to mean anything. */
    private static final int MIN_FUZZY_LENGTH = 4;

    /**
     * Top-{@code k} entries whose name or symbol is a near-MISS of the query — the
     * lookup {@link #search} cannot do, because a corrupted spelling shares no whole
     * token with the name it corrupts. The room writes „Keinmetall" for Rheinmetall
     * and „SpaceEx" for SpaceX; both are two edits away from a real name and
     * invisible to exact-token matching.
     *
     * <p><b>This proposes, it never decides.</b> Letter distance alone would happily
     * connect two unrelated companies that spell alike, so the result is a candidate
     * list for the identity judge — the same contract the corpus lookup already
     * uses. The trigram bucket keeps it a lookup rather than a scan: only tokens
     * sharing a trigram with the query are ever scored, not all
     * {@link #size} entries.
     */
    List<InstrumentEntry> nearest(String query, int k) {
        if (query == null || entries.isEmpty() || k <= 0) return List.of();
        Set<String> qTokens = tokens(query);
        if (qTokens.isEmpty()) return List.of();

        Map<Integer, Double> scores = new HashMap<>();
        for (String qt : qTokens) {
            if (qt.length() < MIN_FUZZY_LENGTH) continue;
            Set<String> qGrams = trigrams(qt);
            Set<String> seen = new HashSet<>();
            for (String g : qGrams) {
                List<String> bucket = tokensByTrigram.get(g);
                if (bucket == null) continue;
                for (String t : bucket) {
                    if (t.length() < MIN_FUZZY_LENGTH || !seen.add(t) || t.equals(qt)) continue;
                    Set<String> tGrams = trigrams(t);
                    if (overlap(qGrams, tGrams) < TRIGRAM_FLOOR) continue;
                    double sim = similarity(qt, t);
                    if (sim < NEAREST_FLOOR) continue;
                    List<Integer> owners = byToken.get(t);
                    if (owners == null) continue;
                    for (int i : owners) scores.merge(i, sim, Math::max);
                }
            }
        }
        return scores.entrySet().stream()
                .sorted((a, b) -> {
                    int c = Double.compare(b.getValue(), a.getValue());
                    if (c != 0) return c;
                    return Integer.compare(entries.get(a.getKey()).name().length(),
                            entries.get(b.getKey()).name().length());
                })
                .limit(k)
                .map(e -> entries.get(e.getKey()))
                .toList();
    }

    /** Normalized edit similarity in [0,1]; 1 = identical. */
    static double similarity(String a, String b) {
        int max = Math.max(a.length(), b.length());
        if (max == 0) return 1.0;
        return 1.0 - (double) editDistance(a, b) / max;
    }

    /** Levenshtein distance, two-row band — the strings here are single words. */
    private static int editDistance(String a, String b) {
        int n = b.length();
        int[] prev = new int[n + 1];
        int[] cur = new int[n + 1];
        for (int j = 0; j <= n; j++) prev[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= n; j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] swap = prev;
            prev = cur;
            cur = swap;
        }
        return prev[n];
    }

    private static double overlap(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        int shared = 0;
        for (String g : a) {
            if (b.contains(g)) shared++;
        }
        return (double) shared / Math.min(a.size(), b.size());
    }

    static Set<String> trigrams(String s) {
        Set<String> out = new HashSet<>();
        String padded = "  " + s + " ";
        for (int i = 0; i + 3 <= padded.length(); i++) out.add(padded.substring(i, i + 3));
        return out;
    }

    // -- tokenization --

    static Set<String> tokens(String s) {
        Set<String> out = new HashSet<>();
        if (s == null) return out;
        for (String w : s.toLowerCase(Locale.ROOT).split("[^a-z0-9äöüß]+")) {
            if (w.length() >= 2 && !STOP.contains(w)) out.add(w);
        }
        return out;
    }

    /** Legal-form noise that would connect unrelated companies. */
    private static final Set<String> STOP = Set.of(
            "inc", "corp", "co", "ltd", "plc", "sa", "nv", "se", "ag", "kgaa", "gmbh",
            "the", "and", "of", "group", "holdings", "holding", "company", "corporation",
            "incorporated", "limited", "class", "common", "stock", "shares", "adr", "etf");
}
