package de.bsommerfeld.wsbg.terminal.agent;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The curated r/wallstreetbetsGER <b>entity</b> glossary and its deterministic
 * canonicalisation — the resolver's pre-stage. Slang → the canonical (English where
 * Yahoo indexes it that way) name of a real instrument/person/theme, so a post about
 * „Rheiner" is looked up as „Rheinmetall" (Yahoo news is English-indexed), not
 * fuzzy-matched to an unrelated ticker. Split out of {@code WsbgJargon} so the
 * resolver depends only on the canonicaliser, not the sentiment prompt renderer.
 */
public final class EntityAliases {

    private EntityAliases() {}

    /** Slang → canonical/English entity name for Yahoo lookup. */
    public static final Map<String, String> ENTITY_ALIASES = ordered(
            "Rheiner / Rhein / Rheini / RHEINI", "Rheinmetall",
            "Orangenmann", "Trump",
            "Obdukatze", "Obducat",
            // Recurring WSBG-GER nicknames for real instruments — extend as new ones appear.
            "Lederjacke / Lederjacken-Mann", "NVIDIA",      // Jensen Huang's leather jacket
            "Pala", "Palantir",
            "Coba", "Commerzbank",
            "Kranich", "Lufthansa",                         // the airline's crane logo
            "Saylor", "MicroStrategy",                      // Michael Saylor / Strategy
            "Daimler / Benz", "Mercedes-Benz Group",
            "Telekom / Magenta", "Deutsche Telekom",
            "Bumsbude", "(generic junk/penny stock — resolve the actual ticker the post names, not this word)");

    /**
     * Deterministic alias → canonical lookup, built by splitting the slash-listed
     * {@link #ENTITY_ALIASES} keys into individual aliases. The 4B extraction model applies
     * the prompt-rendered aliases UNRELIABLY (sometimes „Rheiner" → Rheinmetall, sometimes it
     * stays „Rheiner" → a tickerless split), so the resolver normalises every subject name
     * with THIS first for a 100%-consistent mapping. Parenthetical hint-only entries
     * (e.g. „Bumsbude") are skipped — they're guidance for the model, not a rename.
     */
    private static final Map<String, String> CANONICAL_BY_ALIAS = buildCanonicalMap();

    private static Map<String, String> buildCanonicalMap() {
        Map<String, String> m = new HashMap<>();
        ENTITY_ALIASES.forEach((slangList, canonical) -> {
            if (canonical.startsWith("(")) return; // a prompt-only hint, not a deterministic rename
            for (String alias : slangList.split("/")) {
                String key = alias.trim().toLowerCase(Locale.ROOT);
                if (!key.isEmpty()) m.putIfAbsent(key, canonical);
            }
        });
        return m;
    }

    /**
     * Deterministically maps a WSBG slang subject to its canonical entity (case-insensitive,
     * whole-name match), or returns it unchanged. So „Rheiner"/„Rheini" reliably become
     * „Rheinmetall" before resolution — no longer at the 4B model's mercy.
     */
    public static String canonicalize(String subject) {
        if (subject == null) return null;
        String c = CANONICAL_BY_ALIAS.get(subject.trim().toLowerCase(Locale.ROOT));
        return c != null ? c : subject;
    }

    /** Renders the entity aliases as a prompt block for the subject stage. */
    public static String forPrompt() {
        StringBuilder sb = new StringBuilder();
        ENTITY_ALIASES.forEach((slang, canonical) ->
                sb.append("- \"").append(slang).append("\" → ").append(canonical).append('\n'));
        return sb.toString();
    }

    private static Map<String, String> ordered(String... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("expected key/value pairs");
        }
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put(kv[i], kv[i + 1]);
        }
        return Collections.unmodifiableMap(m);
    }
}
