package de.bsommerfeld.wsbg.terminal.agent;

import java.util.Map;

/**
 * Facade over the two curated r/wallstreetbetsGER glossaries, split for
 * single-responsibility into {@link EntityAliases} (deterministic
 * canonicalisation + the alias source of truth, the resolver's pre-stage) and
 * {@link RoomSlang} (sentiment/role prompt rendering, an editorial concern).
 *
 * <p>Kept as a thin delegating facade so existing callers ({@code EditorialAgent},
 * {@code TickerResolver}) keep the same entry points while each concern lives in
 * its own class. Prefer calling the two classes directly in new code.
 */
public final class WsbgJargon {

    private WsbgJargon() {}

    /** Slang → canonical/English entity name for Yahoo lookup. */
    public static final Map<String, String> ENTITY_ALIASES = EntityAliases.ENTITY_ALIASES;

    /** Slang → sentiment/role meaning (English base/fallback). */
    public static final Map<String, String> ROOM_SLANG = RoomSlang.ROOM_SLANG;

    /** Slang → sentiment/role meaning (German). */
    public static final Map<String, String> ROOM_SLANG_DE = RoomSlang.ROOM_SLANG_DE;

    /** Deterministically maps a WSBG slang subject to its canonical entity, or returns it unchanged. */
    public static String canonicalize(String subject) {
        return EntityAliases.canonicalize(subject);
    }

    /** Renders the entity aliases as a prompt block for the subject stage. */
    public static String entityAliasesForPrompt() {
        return EntityAliases.forPrompt();
    }

    /** Renders the room slang (English) as a prompt block for the headline stage. */
    public static String roomSlangForPrompt() {
        return RoomSlang.forPrompt("en");
    }

    /** Renders the room slang in the prompt's language: German for {@code "de"}, English otherwise. */
    public static String roomSlangForPrompt(String langCode) {
        return RoomSlang.forPrompt(langCode);
    }
}
