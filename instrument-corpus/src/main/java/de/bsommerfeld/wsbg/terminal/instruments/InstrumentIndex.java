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
                       Map<String, List<String>> tokensByPrefix) {

    static final InstrumentIndex EMPTY = new InstrumentIndex(List.of(), Map.of(), Map.of());

    static InstrumentIndex build(List<InstrumentEntry> entries) {
        Map<String, List<Integer>> byToken = new HashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            InstrumentEntry e = entries.get(i);
            Set<String> ts = tokens(e.name());
            ts.addAll(tokens(e.symbol()));
            for (String t : ts) byToken.computeIfAbsent(t, k -> new ArrayList<>()).add(i);
        }
        Map<String, List<String>> byPrefix = new HashMap<>();
        for (String t : byToken.keySet()) {
            if (t.length() >= 2) {
                byPrefix.computeIfAbsent(t.substring(0, 2), k -> new ArrayList<>()).add(t);
            }
        }
        return new InstrumentIndex(List.copyOf(entries), byToken, byPrefix);
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
