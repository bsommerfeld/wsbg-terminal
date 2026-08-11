package de.bsommerfeld.wsbg.terminal.instruments;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one spelling normalizer shared by the alias memory and everything else
 * that stores a name as a key — so a name written once can be
 * found again no matter which side wrote it.
 *
 * <p>Deliberately dumb: lower-case, fold the punctuation a name can be written
 * with into single spaces, keep letters (umlauts included) and digits. It does
 * NOT strip legal forms or any other "noise" — that would be a curated list,
 * and the short form is the {@link AliasStore}'s job to learn.
 */
public final class NameKey {

    private NameKey() {}

    /** The lookup key for a name: lower-cased, punctuation folded to single spaces. */
    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String lower = raw.toLowerCase(Locale.ROOT);
        StringBuilder b = new StringBuilder(lower.length());
        boolean pendingSpace = false;
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                if (pendingSpace && b.length() > 0) b.append(' ');
                pendingSpace = false;
                b.append(c);
            } else {
                pendingSpace = true;
            }
        }
        return b.toString();
    }

    /** The normalized name split into its words (empty for a blank name). */
    public static List<String> tokens(String raw) {
        String norm = normalize(raw);
        if (norm.isEmpty()) return List.of();
        List<String> out = new ArrayList<>();
        for (String t : norm.split(" ")) {
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
