package de.bsommerfeld.wsbg.terminal.web.impl.text;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The house relevance match, ONCE — the semantics every feed client used to
 * carry privately: normalize (lower-case, umlauts unfolded), split a name
 * into its significant words (legal-form and filler words never carry a match
 * alone), and test a haystack for word-boundary containment.
 */
public final class TextMatch {

    /** Generic words that must never carry the relevance match alone. */
    private static final Set<String> NAME_STOP = Set.of(
            "the", "and", "und", "inc", "incorporated", "corp", "corporation", "co",
            "company", "ag", "se", "plc", "ltd", "limited", "nv", "sa", "spa",
            "holding", "holdings", "group", "international", "aktie", "aktien");

    private TextMatch() {
    }

    public static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
    }

    /** True when {@code word} is a generic/legal-form token that never carries meaning alone. */
    public static boolean generic(String word) {
        if (word == null) return false;
        return NAME_STOP.contains(normalize(word).replaceAll("[^a-z0-9]", ""));
    }

    /** The words of {@code name} that may carry a match: ≥3 chars, not generic. */
    public static Set<String> significantWords(String name) {
        if (name == null || name.isBlank()) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (String w : normalize(name).split("[^a-z0-9]+")) {
            if (w.length() >= 3 && !NAME_STOP.contains(w)) out.add(w);
        }
        return out;
    }

    /** True when {@code text} carries at least one of the words on a word boundary. */
    public static boolean matchesAny(String text, Set<String> words) {
        if (words.isEmpty() || text == null || text.isEmpty()) return false;
        String t = normalize(text);
        for (String w : words) {
            if (containsWord(t, w)) return true;
        }
        return false;
    }

    /** True when {@code text} carries EVERY word — the free-text query mode. */
    public static boolean matchesAll(String text, Set<String> words) {
        if (words.isEmpty() || text == null || text.isEmpty()) return false;
        String t = normalize(text);
        for (String w : words) {
            if (!containsWord(t, w)) return false;
        }
        return true;
    }

    private static boolean containsWord(String normalizedText, String word) {
        int from = 0;
        while (true) {
            int i = normalizedText.indexOf(word, from);
            if (i < 0) return false;
            boolean startOk = i == 0 || !isWordChar(normalizedText.charAt(i - 1));
            int end = i + word.length();
            boolean endOk = end >= normalizedText.length() || !isWordChar(normalizedText.charAt(end));
            if (startOk && endOk) return true;
            from = i + 1;
        }
    }

    private static boolean isWordChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }
}
