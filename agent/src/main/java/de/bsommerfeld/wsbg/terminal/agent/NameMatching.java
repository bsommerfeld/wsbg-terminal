package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.yahoofinance.YahooQuote;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Pure string/token algebra behind the resolver's identity matching — Jaccard
 * token overlap, umlaut/diacritic normalisation, stop-token filtering, the
 * name-set predicates (name-equivalent, same-company, shared-word) and the
 * prefix-trap guard. Zero resolver dependency: extracted verbatim from
 * {@code TickerResolver} so the empirically-tuned behaviour (accept "Amazon" →
 * "Amazon.com, Inc.", reject "Rheiner" → "Rheiner Management AG") is preserved,
 * and each predicate stays independently unit-testable.
 */
public final class NameMatching {

    private NameMatching() {}

    /**
     * Generic legal-form / suffix / theme tokens that never distinguish an
     * instrument. Lifted verbatim from the old {@code LookupTickerTool} matching.
     */
    public static final Set<String> STOP_TOKENS = Set.of(
            // "com" is the dotcom suffix glued onto a name ("Amazon.com",
            // "Salesforce.com", "Booking.com") — generic, like inc/corp, never a
            // distinguishing token. Without it a single-token query ("Amazon")
            // fails the strict match against "Amazon.com, Inc." and loses its ticker.
            "inc", "incorporated", "corp", "corporation", "co", "com", "company",
            "ag", "se", "kgaa", "gmbh", "ltd", "limited", "plc", "sa", "nv",
            "aktiengesellschaft", "kommanditgesellschaft", "gesellschaft",
            "the", "and",
            "technology", "technologies", "tech",
            "quantum", "semiconductor", "semiconductors",
            "pharmaceutical", "pharmaceuticals", "pharma",
            "bioscience", "biosciences", "therapeutic",
            "industries", "industrial",
            "interactive", "communications",
            "usd", "eur", "gbp", "jpy", "chf", "cad", "aud", "cny",
            "hkd", "krw", "sek", "nok", "dkk", "pln", "brl", "mxn",
            "usdt", "usdc",
            "etf", "fund", "trust", "shares");

    /**
     * "The name IS the name": a multi-word subject whose significant words (legal
     * forms stripped) are EXACTLY the candidate name's significant words. 'Lithium
     * Americas' ↔ "Lithium Americas Corp." holds; 'Trade Republic' ↔ "Solactive
     * Trade Republic Semiconductors Index" (extra words) and any single-word
     * subject do not.
     */
    public static boolean nameEquivalent(String subject, String candidateName) {
        Set<String> subj = significantNameWords(subject);
        if (subj.size() < 2) return false;
        Set<String> cand = significantNameWords(candidateName);
        return cand.size() == subj.size() && cand.containsAll(subj);
    }

    /**
     * Two candidate names denote the same company when one's significant word set
     * contains the other's (non-empty): "Nokia Oyj" ↔ "Nokia Corporation" holds,
     * "Boyd Gaming" ↔ "BYD Company" does not.
     */
    public static boolean sameCompanyName(String a, String b) {
        Set<String> wa = significantNameWords(a);
        Set<String> wb = significantNameWords(b);
        if (wa.isEmpty() || wb.isEmpty()) return false;
        return wa.containsAll(wb) || wb.containsAll(wa);
    }

    /**
     * Deterministic guard for a judge REDIRECT: the target must carry the subject in
     * its NAME or SYMBOL (case-insensitive word overlap, words ≥ 2 chars). 'BYD' ↔
     * "BYD Company Limited" passes; 'KO' ↔ "Kohl's Corporation" does not.
     */
    public static boolean sharesSignificantWord(String subject, String targetName, String targetSymbol) {
        Set<String> subjectWords = words(subject);
        if (subjectWords.isEmpty()) return false;
        Set<String> targetWords = words(targetName);
        targetWords.addAll(words(targetSymbol));
        for (String w : subjectWords) {
            if (targetWords.contains(w)) return true;
        }
        return false;
    }

    /**
     * Surface-similarity trap: a subject word is a strict PREFIX of a longer
     * candidate word while sharing no FULL word — the judge mistakes the
     * spelling-bleed for a shorthand. "Polen" ⊂ "Polenergia", "Meta" ⊂
     * "Metaplanet" (both live/documented mis-picks). A genuine cross-name identity
     * ("Google" ↔ "Alphabet" — disjoint strings) is NOT a trap and passes; a real
     * shorthand ("Meta" ↔ "Meta Platforms" — shares the full word "meta") is not a
     * trap either.
     */
    public static boolean isPrefixTrap(String subject, String candidateName) {
        Set<String> subj = words(subject);
        Set<String> cand = words(candidateName);
        if (subj.isEmpty() || cand.isEmpty()) return false;
        if (!Collections.disjoint(subj, cand)) return false; // shares a full word → not a trap
        for (String s : subj) {
            for (String c : cand) {
                if (c.length() > s.length() && c.startsWith(s)) return true;
            }
        }
        return false;
    }

    public static Set<String> significantNameWords(String s) {
        Set<String> out = words(s);
        out.removeAll(STOP_TOKENS);
        return out;
    }

    public static Set<String> words(String s) {
        Set<String> out = new HashSet<>();
        if (s == null) return out;
        for (String w : s.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (w.length() >= 2) out.add(w);
        }
        return out;
    }

    public static Set<String> tokenize(String s) {
        if (s == null) return Set.of();
        // German umlauts → their ae/oe/ue transliteration (ß→ss) FIRST, so "Münchener" and the
        // expanded spelling "Muenchener" both normalise to "muenchener". Bare NFD would only
        // strip the dots ("münchener"→"munchener" ≠ "muenchener") and miss the match — German
        // names are exactly where one source carries the umlaut and another the ue-form.
        String deUmlaut = s.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
        // Then strip the REMAINING diacritics (é→e, è→e, ç→c, à→a …). Without this the
        // [^a-z0-9 ] filter below turns an accented letter into a SPACE, splitting "Hermès" →
        // "herm"+"s" so it never matches Yahoo's "Hermes International" (RMS.PA) — no ticker, no
        // price. NFD decomposes each accent into a combining mark, which \p{M} then removes.
        String deAccented = java.text.Normalizer.normalize(deUmlaut, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        String norm = deAccented.replaceAll("[^a-z0-9 ]", " ");
        Set<String> out = new HashSet<>();
        for (String t : Arrays.asList(norm.trim().split("\\s+"))) {
            if (t.length() < 3) continue;
            if (STOP_TOKENS.contains(t)) continue;
            out.add(t);
        }
        return out;
    }

    public static double bestSimilarity(Set<String> queryTokens, YahooQuote q) {
        return Math.max(jaccard(queryTokens, tokenize(q.shortName())),
                jaccard(queryTokens, tokenize(q.longName())));
    }

    public static boolean hasOnlyQueryTokens(Set<String> queryTokens, YahooQuote q) {
        return tokenize(q.shortName()).equals(queryTokens)
                || tokenize(q.longName()).equals(queryTokens);
    }

    public static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        if (inter.isEmpty()) return 0.0;
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }
}
