package de.bsommerfeld.wsbg.terminal.agent.tagging;

import de.bsommerfeld.wsbg.terminal.instruments.InstrumentEntry;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Entity-DF over the instrument universe — the statistic that REPLACES every
 * curated stop-word list in the assignment path. The question is never "is
 * 'bank' a stop word" but "across how many instruments does the token 'bank'
 * spread": a token hundreds of papers carry cannot identify one of them, a
 * token exactly one paper carries can. Derived from the corpus (stock master
 * data), rebuilt whenever the corpus snapshot changes.
 *
 * <p>Also the corpus join: ISIN / base-symbol / normalized-name lookups so an
 * instrument query arriving with partial keys is enriched to its canonical
 * identity before judging.
 */
final class UniverseStats {

    static final UniverseStats EMPTY = new UniverseStats(List.of());

    private final Map<String, Integer> entityDf = new HashMap<>();
    private final Map<String, InstrumentEntry> byIsin = new HashMap<>();
    private final Map<String, InstrumentEntry> bySymbol = new HashMap<>();
    private final Map<String, InstrumentEntry> byName = new HashMap<>();
    private final int size;

    private UniverseStats(List<InstrumentEntry> entries) {
        this.size = entries.size();
        // Entity-DF counts DISTINCT NAMES, not listing lines: "TUI AG NA O.N."
        // appears on eight lines (venues, share lines) but is ONE company —
        // eight lines must not make "tui" look like a generic word.
        java.util.Set<String> seenNames = new java.util.HashSet<>();
        for (InstrumentEntry e : entries) {
            if (e == null || e.name() == null || e.name().isBlank()) continue;
            List<String> tokens = TagText.tokens(e.name());
            if (seenNames.add(String.join(" ", tokens))) {
                for (String t : tokens.stream().distinct().toList()) {
                    entityDf.merge(t, 1, Integer::sum);
                }
            }
            if (e.isin() != null && !e.isin().isBlank()) {
                byIsin.putIfAbsent(e.isin().trim().toUpperCase(Locale.ROOT), e);
            }
            if (e.symbol() != null && !e.symbol().isBlank()) {
                bySymbol.putIfAbsent(baseOf(e.symbol()), e);
            }
            byName.putIfAbsent(String.join(" ", tokens), e);
        }
    }

    static UniverseStats over(List<InstrumentEntry> entries) {
        return entries == null || entries.isEmpty() ? EMPTY : new UniverseStats(entries);
    }

    /** Across how many instrument names the token spreads (0 = none known). */
    int entityDf(String token) {
        return entityDf.getOrDefault(token, 0);
    }

    /** Whether a universe is loaded at all — without one, only phrase evidence counts. */
    boolean known() {
        return size > 0;
    }

    int size() {
        return size;
    }

    Optional<InstrumentEntry> findByIsin(String isin) {
        if (isin == null || isin.isBlank()) return Optional.empty();
        return Optional.ofNullable(byIsin.get(isin.trim().toUpperCase(Locale.ROOT)));
    }

    Optional<InstrumentEntry> findBySymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) return Optional.empty();
        return Optional.ofNullable(bySymbol.get(baseOf(symbol)));
    }

    Optional<InstrumentEntry> findByName(String name) {
        if (name == null || name.isBlank()) return Optional.empty();
        return Optional.ofNullable(byName.get(String.join(" ", TagText.tokens(name))));
    }

    static String baseOf(String symbol) {
        String s = symbol.trim().toUpperCase(Locale.ROOT);
        int dot = s.indexOf('.');
        return dot > 0 ? s.substring(0, dot) : s;
    }
}
