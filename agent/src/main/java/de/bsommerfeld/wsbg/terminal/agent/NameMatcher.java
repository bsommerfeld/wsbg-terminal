package de.bsommerfeld.wsbg.terminal.agent;

import de.bsommerfeld.wsbg.terminal.agent.TickerResolver.ResolvedSubject;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Word-level subject/text matching engine (extracted from {@link SubjectAttributor}).
 *
 * <p>The subject is stored normalised ("Meta Platforms, Inc.", "Münchener
 * Rückversicherungs-Gesellschaft…") while the room writes the short/native form
 * ("Meta", "Münchener rück"). So matching is on <b>significant words</b> of the
 * name (+ the ticker via {@link TickerExtractor}), not a full-string substring.
 * Generic company words ({@code inc}, {@code holdings}, …) are filtered so they
 * never carry a match on their own. Matching is deliberately lenient — a missed
 * mention costs more than a loose one.
 *
 * <p>All methods are pure and static; the class carries no dependency on the
 * repository or the brain.
 */
final class NameMatcher {

    private NameMatcher() {
    }

    /** Generic words that must never carry a match by themselves. */
    private static final Set<String> STOP = Set.of(
            "inc", "incorporated", "corp", "corporation", "company", "holdings",
            "holding", "group", "the", "and", "für", "und", "fund", "trust",
            "plc", "ltd", "limited", "gmbh", "kgaa", "aktiengesellschaft",
            "gesellschaft", "technologies", "technology", "international",
            "systems", "solutions", "index", "etf");

    /** Significant words of the room's term + the canonical name (stop-words dropped). */
    static Set<String> nameWords(String query, String canonical) {
        Set<String> out = new LinkedHashSet<>();
        addWords(out, query);
        addWords(out, canonical);
        return out;
    }

    /** Significant words of a single string (stop-words + sub-3-char dropped). */
    static Set<String> significantWords(String s) {
        Set<String> out = new LinkedHashSet<>();
        addWords(out, s);
        return out;
    }

    private static void addWords(Set<String> out, String s) {
        if (s == null) return;
        for (String w : deUmlaut(s).split("[^a-z0-9]+")) {
            if (w.length() >= 3 && !STOP.contains(w)) out.add(w);
        }
    }

    /**
     * Lowercase + German umlaut transliteration (ä→ae, ö→oe, ü→ue, ß→ss) so the room's
     * "Muenchener" and a canonical "Münchener" tokenise to the SAME word ("muenchener") and
     * match. Applied symmetrically to both the name words and the scanned text.
     */
    static String deUmlaut(String s) {
        return s.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
    }

    /** Words shared by ≥2 of the cluster's subject names — ambiguous, can't carry a match alone. */
    static Set<String> ambiguousWords(List<ResolvedSubject> resolved) {
        Map<String, Integer> freq = new HashMap<>();
        for (ResolvedSubject rs : resolved) {
            for (String w : nameWords(rs.query(), rs.canonicalName())) {
                freq.merge(w, 1, Integer::sum);
            }
        }
        Set<String> out = new HashSet<>();
        for (Map.Entry<String, Integer> e : freq.entrySet()) {
            if (e.getValue() >= 2) out.add(e.getKey());
        }
        return out;
    }

    /**
     * The BEST line of an image transcript for the subject (most distinctive-word
     * overlap), so the evidence snippet is the subject's own row — "MSCI World"
     * gets the World row, not whichever "msci" line came first. Falls back to the
     * whole text.
     */
    static String matchingLine(String visionText, Set<String> nameWords, String ticker) {
        return matchingLine(visionText, nameWords, ticker, Set.of());
    }

    static String matchingLine(String visionText, Set<String> nameWords, String ticker, Set<String> ambiguous) {
        String best = null;
        int bestScore = 0;
        for (String line : visionText.split("\n")) {
            if (line.isBlank()) continue;
            if (ticker != null && TickerExtractor.extract(line).contains(ticker)) return line.strip();
            int[] ov = overlap(line, nameWords, ambiguous);
            int score = ov[0] * 100 + ov[1]; // distinctive words dominate, then total
            if ((ov[0] >= 1 || ov[1] >= 2) && score > bestScore) {
                bestScore = score;
                best = line.strip();
            }
        }
        return best != null ? best : visionText;
    }

    /** True if the text carries the ticker (as a symbol) or shares a significant name word. */
    static boolean matches(String text, Set<String> nameWords, String ticker) {
        return matches(text, nameWords, ticker, Set.of());
    }

    /**
     * Cluster-aware match: the text matches if it carries the ticker, OR shares a
     * DISTINCTIVE name word, OR shares ≥2 words total. A lone {@code ambiguous}
     * word (shared by ≥2 cluster subjects, e.g. "msci") is not enough — that's what
     * stops MSCI EM from matching the MSCI World row. Short forms still work because
     * "berkshire"/"meta"/"world" are distinctive.
     */
    static boolean matches(String text, Set<String> nameWords, String ticker, Set<String> ambiguous) {
        if (text == null || text.isBlank()) return false;
        if (ticker != null && TickerExtractor.extract(text).contains(ticker)) return true;
        int[] ov = overlap(text, nameWords, ambiguous);
        return ov[0] >= 1 || ov[1] >= 2;
    }

    /** {@code {distinctiveHits, totalHits}} of {@code nameWords} present in {@code text}. */
    private static int[] overlap(String text, Set<String> nameWords, Set<String> ambiguous) {
        Set<String> textWords = new HashSet<>(Arrays.asList(
                deUmlaut(text).split("[^a-z0-9]+")));
        int distinctive = 0;
        int total = 0;
        for (String w : nameWords) {
            if (textWords.contains(w)) {
                total++;
                if (!ambiguous.contains(w)) distinctive++;
            }
        }
        return new int[]{distinctive, total};
    }

    /** First non-blank line of a transcript — the vision lead clause used as an inherited-image snippet. */
    static String leadLine(String visionText) {
        for (String line : visionText.split("\n")) {
            if (!line.isBlank()) return line.strip();
        }
        return visionText;
    }
}
