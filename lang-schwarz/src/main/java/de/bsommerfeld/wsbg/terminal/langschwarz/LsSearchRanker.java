package de.bsommerfeld.wsbg.terminal.langschwarz;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Ranks L&amp;S name-search hits against the FULL subject name and returns the best
 * trustworthy instrument, or empty. Module-private helper split out of
 * {@link LangSchwarzClient} so the fuzzy name-matching + ranking is one cohesive,
 * network-free unit.
 *
 * <p>Ranking is by prefix-tolerant token <b>coverage</b> of the subject's significant
 * tokens (so „THERAP." covers „Therapeutics") gated at {@link #MIN_NAME_COVERAGE};
 * among qualifiers the tightest wins (coverage, then equity-first, then fewest extra
 * tokens), so „MSCI World" picks a plain World tracker, not „MSCI World Quality Factor"
 * or „MSCI USA", and a wrong twin is dropped rather than taken blindly as the first hit.
 *
 * <p>The threshold, stop-word set and tie-breaks are tuned wrong-twin guards — do not
 * change them without live re-validation.
 */
final class LsSearchRanker {

    private LsSearchRanker() {}

    /**
     * Minimum fraction of a subject's significant name tokens a search hit must
     * cover to be trusted. Below this the hit is a same-named <em>twin</em>
     * („Mullen Group" for „Mullen Automotive") and is rejected — better no L&S
     * price (fall through) than a confident wrong one.
     */
    static final double MIN_NAME_COVERAGE = 0.6;

    /**
     * Noise tokens that carry no identity — issuer/legal/share-class cruft + venue
     * abbreviations. Includes the FULL-word legal/connector forms ("corporation",
     * "company", "and"), not just the abbreviations: a Yahoo subject name carries
     * them ("Microsoft Corporation", "Eli Lilly and Company") while L&S abbreviates
     * ("MICROSOFT", "ELI LILLY"), so without stripping them the coverage gate would
     * wrongly reject the megacap.
     */
    private static final Set<String> NAME_STOP = Set.of(
            "etf", "ucits", "the", "and", "und", "com",
            "inc", "incorporated", "corp", "corporation", "co", "company",
            "ag", "se", "plc", "ltd", "limited", "nv", "sa", "spa",
            "holding", "holdings", "group", "international",
            "technology", "technologies", "industries", "industrial",
            "pharmaceutical", "pharmaceuticals", "pharma",
            "ord", "reg", "shs", "share", "shares", "dl", "trn",
            "index", "etr", "acc", "dist", "fund", "trust", "cdr");

    /** One search hit, pre-ranking. */
    record Cand(long id, String isin, String name, boolean stk) {}

    /** Ranks pre-parsed candidates against {@code fullName}; see the class doc. */
    static Optional<LsInstrument> pickBest(String fullName, List<Cand> cands) {
        List<String> want = nameTokens(fullName);
        Cand best = null;
        double bestCov = 0;
        int bestStk = 0, bestExtra = 0;
        for (Cand c : cands) {
            List<String> have = nameTokens(c.name());
            double cov = coverage(want, have);
            if (cov < MIN_NAME_COVERAGE) continue;
            int stk = c.stk() ? 0 : 1;                       // equities edge out funds on a tie
            int extra = Math.max(0, have.size() - want.size()); // fewest extras = tightest match
            if (best == null || cov > bestCov
                    || (cov == bestCov && stk < bestStk)
                    || (cov == bestCov && stk == bestStk && extra < bestExtra)) {
                best = c; bestCov = cov; bestStk = stk; bestExtra = extra;
            }
        }
        return best == null ? Optional.empty()
                : Optional.of(new LsInstrument(best.id(), best.isin(), best.name()));
    }

    /** Significant (≥3-char, non-stop) lower-case tokens of a name. */
    static List<String> nameTokens(String s) {
        if (s == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String t : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 ]", " ").trim().split("\\s+")) {
            if (t.length() < 3 || NAME_STOP.contains(t)) continue;
            if (t.chars().allMatch(Character::isDigit)) continue; // share-nominal noise ("001")
            out.add(t);
        }
        return out;
    }

    /** Fraction of {@code want} tokens that prefix-match some {@code have} token. */
    static double coverage(List<String> want, List<String> have) {
        if (want.isEmpty()) return 0.0;
        int hit = 0;
        for (String w : want) {
            for (String h : have) {
                if (prefixMatch(w, h)) { hit++; break; }
            }
        }
        return (double) hit / want.size();
    }

    /**
     * True when one token is a ≥3-char prefix of the other — tolerates the heavy
     * abbreviation L&S uses in its display names ("def"↔"defense", "sec"↔"security",
     * "therap"↔"therapeutics"). The 3-char floor still keeps distinct words apart
     * ("group" ≠ "automotive"), so a wrong same-named twin stays rejected.
     */
    private static boolean prefixMatch(String a, String b) {
        if (a.equals(b)) return true;
        int min = Math.min(a.length(), b.length());
        return min >= 3 && a.regionMatches(0, b, 0, min);
    }
}
