package de.bsommerfeld.wsbg.terminal.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hardcoded r/wallstreetbetsGER slang glossary, injected into the editorial
 * prompts so the model reads the room correctly instead of guessing.
 *
 * <p>Two maps, two jobs:
 * <ul>
 *   <li>{@link #ENTITY_ALIASES} — slang → the canonical (English where Yahoo
 *       indexes it that way) name of a real instrument/person/theme. Used by
 *       the <b>subject-extraction</b> stage so a post about „Rheiner" is looked
 *       up as „Rheinmetall" (Yahoo news is English-indexed), not fuzzy-matched
 *       to an unrelated ticker. This is the structural fix for the
 *       „Rheiner → RMO" class of mis-resolution.</li>
 *   <li>{@link #ROOM_SLANG} — slang → what it signals about sentiment/role.
 *       Used by the <b>headline</b> stage so „kurze Hosen" reads as a short
 *       (bearish), „die Dicken" as institutionals, 🚀 as momentum.</li>
 * </ul>
 *
 * <p>Deliberately a curated map, not an exhaustive dictionary — it only needs
 * the terms that would otherwise mislead entity resolution or sentiment. Extend
 * as new recurring slang shows up in the wire.
 */
public final class WsbgJargon {

    private WsbgJargon() {}

    /** Slang → canonical/English entity name for Yahoo lookup. */
    public static final Map<String, String> ENTITY_ALIASES = ordered(
            "Rheiner / Rhein", "Rheinmetall",
            "Orangenmann", "Trump",
            "Obdukatze", "Obducat",
            "Bumsbude", "(generic junk/penny stock — resolve the actual ticker the post names, not this word)");

    /** Slang → sentiment/role meaning for reading the room. */
    public static final Map<String, String> ROOM_SLANG = ordered(
            "kurze Hosen / Höschen / Shorts", "a short position — bearish bet",
            "lange Hosen / Höschen / Shorts", "a long position — bullish bet",
            "Rakete / 🚀", "strong bullish momentum / expected pump",
            "die Dicken / die Fettis / die Fetten", "US traders / americans / USA / rich americans",
            "Affen / Monkey / Ape", "the WSBG retail crowd itself",
            "Spielgeld", "a tiny, for-fun position",
            "To the moon / zum Mond", "A sharp rise in the price",
            "Zum Erdkern", "A sharp drop in the price",
            "Käfig / Gehege", "this subreddit (r/wallstreetbetsGER)",
            "Gönnung", "a realized gain the poster is treating themselves to",
            "Affengeschrei", "FOMO driven content without substance posted by WSBG crowd",
            "Bumsbude", "a junk / pump-and-dump / scam stock");

    /** Renders the entity aliases as a prompt block for the subject stage. */
    public static String entityAliasesForPrompt() {
        StringBuilder sb = new StringBuilder();
        ENTITY_ALIASES.forEach((slang, canonical) ->
                sb.append("- \"").append(slang).append("\" → ").append(canonical).append('\n'));
        return sb.toString();
    }

    /** Renders the room slang as a prompt block for the headline stage. */
    public static String roomSlangForPrompt() {
        StringBuilder sb = new StringBuilder();
        ROOM_SLANG.forEach((slang, meaning) ->
                sb.append("- ").append(slang).append(" = ").append(meaning).append('\n'));
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
