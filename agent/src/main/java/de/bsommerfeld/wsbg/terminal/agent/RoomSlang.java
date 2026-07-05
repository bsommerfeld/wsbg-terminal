package de.bsommerfeld.wsbg.terminal.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The curated r/wallstreetbetsGER <b>sentiment/role</b> glossary — an editorial
 * prompt concern, nothing to do with ticker resolution. Slang → what it signals
 * about sentiment/role, so „kurze Hosen" reads as a short (bearish), „die Dicken"
 * as institutionals, 🚀 as momentum. Split out of {@code WsbgJargon}; rendered into
 * the headline stage's prompt in the prompt's own language.
 */
public final class RoomSlang {

    private RoomSlang() {}

    /** Slang → sentiment/role meaning for reading the room (English — base/fallback prompt). */
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

    /**
     * Slang → sentiment/role meaning in German — used by the German prompts so the
     * room-reading guidance is in the same language the model writes in (no English
     * leaking into a German-output context). Same keys as {@link #ROOM_SLANG}.
     */
    public static final Map<String, String> ROOM_SLANG_DE = ordered(
            "kurze Hosen / Höschen / Shorts", "eine Short-Position — bärische Wette",
            "lange Hosen / Höschen / Shorts", "eine Long-Position — bullische Wette",
            "Rakete / 🚀", "starkes bullisches Momentum / erwarteter Pump",
            "die Dicken / die Fettis / die Fetten", "US-Trader / Amerikaner / USA / reiche Amerikaner",
            "Affen / Monkey / Ape", "die WSBG-Kleinanleger-Meute selbst",
            "Spielgeld", "eine winzige Spaß-Position",
            "To the moon / zum Mond", "ein scharfer Kursanstieg",
            "Zum Erdkern", "ein scharfer Kurssturz",
            "Käfig / Gehege", "dieses Subreddit (r/wallstreetbetsGER)",
            "Gönnung", "ein realisierter Gewinn, den sich der Poster gönnt",
            "Affengeschrei", "FOMO-getriebener Inhalt ohne Substanz, von der WSBG-Meute gepostet",
            "Bumsbude", "eine Schrott- / Pump-and-Dump- / Scam-Aktie");

    /**
     * Renders the room slang in the prompt's language: German meanings for
     * {@code "de"}, English otherwise. The slang terms themselves are the room's
     * (German) words in both — only the explanations switch language.
     */
    public static String forPrompt(String langCode) {
        Map<String, String> meanings = "de".equalsIgnoreCase(langCode) ? ROOM_SLANG_DE : ROOM_SLANG;
        StringBuilder sb = new StringBuilder();
        meanings.forEach((slang, meaning) ->
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
