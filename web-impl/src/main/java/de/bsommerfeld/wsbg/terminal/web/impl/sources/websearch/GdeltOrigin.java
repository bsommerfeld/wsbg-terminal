package de.bsommerfeld.wsbg.terminal.web.impl.sources.websearch;

import de.bsommerfeld.wsbg.terminal.web.article.SourceOrigin;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * GDELT names a language and a source country per article in ENGLISH WORDS
 * ("Japanese", "Russia"). The house counts spheres as codes, so the words have
 * to become codes - and the table that does it is DERIVED, not maintained: the
 * JDK already knows every ISO language and country by its English display
 * name, so the map is built from {@code Locale} at class load and grows with
 * the platform instead of with a hand-edited list.
 *
 * <p>What cannot be resolved keeps the raw word, upper-cased. An unmapped
 * sphere is still a sphere - it just does not unify with a code from another
 * source, which is the honest reading: we could not establish that the two are
 * the same gatekeeping.
 */
final class GdeltOrigin {

    private static final Map<String, String> COUNTRIES = new HashMap<>();
    private static final Map<String, String> LANGUAGES = new HashMap<>();

    static {
        for (String cc : Locale.getISOCountries()) {
            String name = Locale.of("", cc).getDisplayCountry(Locale.ENGLISH);
            if (!name.isBlank()) COUNTRIES.putIfAbsent(key(name), cc);
        }
        for (String lc : Locale.getISOLanguages()) {
            String name = Locale.of(lc).getDisplayLanguage(Locale.ENGLISH);
            if (!name.isBlank()) LANGUAGES.putIfAbsent(key(name), lc);
        }
    }

    private GdeltOrigin() {
    }

    /**
     * One article's origin from GDELT's own metadata fields.
     *
     * <p>The country is GDELT's {@code sourcecountry}, which it derives partly
     * from the domain - a national outlet on a foreign TLD is filed under that
     * TLD's country. That noise is real and stays visible rather than being
     * silently smoothed: the card prints the domain beside the sphere, so a
     * misfiled seat can be read off the head instead of quietly counting as an
     * independent confirmation nobody can check.
     */
    static SourceOrigin of(String gdeltLanguage, String gdeltCountry) {
        return SourceOrigin.of(code(LANGUAGES, gdeltLanguage), code(COUNTRIES, gdeltCountry));
    }

    private static String code(Map<String, String> table, String word) {
        if (word == null || word.isBlank()) return "";
        String hit = table.get(key(word));
        return hit != null ? hit : word.strip().toUpperCase(Locale.ROOT);
    }

    private static String key(String s) {
        return s.strip().toLowerCase(Locale.ROOT);
    }
}
