package de.bsommerfeld.wsbg.terminal.agent;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves the display form of a subject to gild in a published headline — the
 * longest word-prefix of the canonical (Yahoo legal) name that actually appears
 * in the line, returned in the LINE's own spelling so the front-end regex finds
 * it verbatim. Extracted verbatim from {@link HeadlineWriter} (German
 * compound-head string logic) so the publish flow reads as a linear recipe.
 */
final class HeadlineGilder {

    private HeadlineGilder() {}

    /** Words that must never be gilded on their own (a one-word "match" like "The"). */
    private static final Set<String> GILD_STOP = Set.of(
            "the", "and", "der", "die", "das", "inc", "corp", "corporation", "company",
            "incorporated", "limited", "ltd", "plc", "group", "gruppe", "holding", "holdings",
            "aktiengesellschaft", "ucits", "etf");

    /** Minimum candidate length for a compound-head match ("Gold" in "Goldpreis") —
     *  short names as prefixes of unrelated words ("Bay" in "Bayern") must not bind. */
    private static final int COMPOUND_MIN_CAND = 4;

    /**
     * The longest word-prefix of {@code canonicalName} that appears in the headline
     * — the display form the UI gilds. Matching is CASE-INSENSITIVE (the line writes
     * "Nvidia", Yahoo's legal name is "NVIDIA Corporation") and the returned form is
     * the LINE's own spelling, so the front-end regex finds it verbatim.
     * "Salesforce, Inc." gilds "Salesforce"; "D-Wave Quantum Inc." gilds "D-Wave
     * Quantum" or "D-Wave", whichever the line wrote. Trailing commas/periods are
     * stripped per candidate; a lone generic word never gilds. {@code null} when no
     * form is in the line. Package-private for testing.
     */
    static String displayFormIn(String headline, String canonicalName) {
        if (headline == null || canonicalName == null || canonicalName.isBlank()) return null;
        // A leading article makes every prefix start with it, so "The Wendy's
        // Company" could never gild the line's "Wendy's" — strip it up front.
        // A domain suffix on the first word never appears in a written line
        // ("Amazon.com, Inc." — the line writes "Amazon"), so it goes too.
        String name = canonicalName.trim()
                .replaceFirst("(?i)^(the|der|die|das)\\s+", "")
                .replaceFirst("(?i)^([a-z0-9-]+)\\.(com|de|net|org)\\b", "$1");
        String form = prefixFormIn(headline, name.split("\\s+"));
        if (form != null) return form;
        // A line often drops a brand/wrapper first word entirely ("iShares Core MSCI
        // EM IMI …" is written as "Core MSCI EM IMI"): one retry without it. Only for
        // names long enough that the remainder still identifies the entity.
        String[] words = name.split("\\s+");
        return words.length >= 3
                ? prefixFormIn(headline, Arrays.copyOfRange(words, 1, words.length)) : null;
    }

    /** Longest word-prefix of {@code words} that appears in the headline, or null. */
    private static String prefixFormIn(String headline, String[] words) {
        String headlineLower = headline.toLowerCase(Locale.ROOT);
        for (int k = words.length; k >= 1; k--) {
            String cand = String.join(" ", Arrays.copyOfRange(words, 0, k))
                    .replaceAll("[,.]+$", "").trim();
            if (cand.length() < 3) continue;
            if (k == 1 && GILD_STOP.contains(cand.toLowerCase(Locale.ROOT))) continue;
            String candLower = cand.toLowerCase(Locale.ROOT);
            // Scan ALL occurrences: an exact word-boundary hit anywhere beats a
            // compound hit ("Goldman warnt, Goldpreis steigt" must bind "Goldpreis"
            // over "Goldman" — first-occurrence-wins would pick the wrong one).
            String compound = null;
            for (int idx = headlineLower.indexOf(candLower); idx >= 0;
                    idx = headlineLower.indexOf(candLower, idx + 1)) {
                if (idx > 0 && Character.isLetterOrDigit(headline.charAt(idx - 1))) {
                    continue; // start-boundary guard: "Aris" must not bind inside "Paris"
                }
                int end = idx + cand.length();
                // Word end, or the German genitive "s" ("Rheinmetalls Auftrag").
                if (isWordEndAt(headline, end)) {
                    return headline.substring(idx, end);
                }
                // German COMPOUND head: the subject as first constituent of a longer
                // word ("Goldpreis", "Goldposition") names the subject — the room
                // writes compounds, not phrases. Guarded: candidate ≥ COMPOUND_MIN_CAND
                // (never "Bay"→"Bayern"), continuation lowercase (a capital would be a
                // different name). Whole compound returned, so the gild wraps
                // "Goldpreis", not "Gold|preis". Kept only as the FALLBACK to an
                // exact hit elsewhere in the line.
                if (compound == null && cand.length() >= COMPOUND_MIN_CAND
                        && end < headline.length()
                        && Character.isLetter(headline.charAt(end))
                        && Character.isLowerCase(headline.charAt(end))) {
                    int wordEnd = end;
                    while (wordEnd < headline.length() && Character.isLetter(headline.charAt(wordEnd))) {
                        wordEnd++;
                    }
                    compound = headline.substring(idx, wordEnd);
                }
            }
            if (compound != null) return compound;
        }
        return null;
    }

    /** True when position {@code end} closes a word: end of string, a non-letter, or a
     *  lone genitive "s" followed by one of those ("Rheinmetalls", "Teslas"). */
    private static boolean isWordEndAt(String headline, int end) {
        if (end >= headline.length()) return true;
        char c = headline.charAt(end);
        if (!Character.isLetterOrDigit(c)) return true;
        return (c == 's' || c == 'S')
                && (end + 1 >= headline.length()
                    || !Character.isLetterOrDigit(headline.charAt(end + 1)));
    }
}
