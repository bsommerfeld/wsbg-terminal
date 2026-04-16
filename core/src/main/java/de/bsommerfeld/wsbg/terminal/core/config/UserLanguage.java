package de.bsommerfeld.wsbg.terminal.core.config;

import java.util.Locale;

/**
 * Encapsulates a user's language preference with derived locale information.
 * Resolved from the raw language code stored in {@link UserConfig}.
 *
 * @param code        ISO 639-1 code (e.g. "de", "en")
 * @param displayName English name of the language (e.g. "German", "English")
 * @param locale      fully resolved JDK locale
 */
public record UserLanguage(String code, String displayName, Locale locale) {

    /**
     * Creates a {@link UserLanguage} from an ISO 639-1 language code.
     * The display name is always resolved in English so it can be
     * injected into English-language AI prompts.
     */
    public static UserLanguage of(String code) {
        Locale locale = Locale.forLanguageTag(code);
        String displayName = locale.getDisplayLanguage(Locale.ENGLISH);
        return new UserLanguage(code, displayName, locale);
    }
}
