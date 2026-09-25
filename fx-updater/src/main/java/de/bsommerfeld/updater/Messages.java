package de.bsommerfeld.updater;

import de.bsommerfeld.tinyupdate.api.UpdatePhase;

import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * The updater's text, in the system language: {@code messages.properties}
 * (English) and one {@code messages_<language>.properties} per translation. A
 * key without a translation falls back to English, a key missing altogether
 * shows as itself.
 */
final class Messages {

    private final ResourceBundle bundle;
    private final Locale locale;

    private Messages(ResourceBundle bundle, Locale locale) {
        this.bundle = bundle;
        this.locale = locale;
    }

    static Messages forDefaultLocale() {
        Locale locale = Locale.getDefault();
        return new Messages(ResourceBundle.getBundle("de.bsommerfeld.updater.messages", locale), locale);
    }

    /** The text for {@code key}, with {@code {0}}, {@code {1}}, ... filled in. */
    String get(String key, Object... arguments) {
        String pattern;
        try {
            pattern = bundle.getString(key);
        } catch (MissingResourceException e) {
            return key;
        }
        return arguments.length == 0 ? pattern : new MessageFormat(pattern, locale).format(arguments);
    }

    /** The text for a TinyUpdate phase token; an unknown token shows as itself. */
    String phase(String token) {
        return Arrays.stream(UpdatePhase.values())
                .filter(phase -> phase.token().equals(token))
                .findFirst()
                .map(phase -> get("phase." + phase.name()))
                .orElse(token);
    }

    /** A transfer speed, e.g. {@code 3.4 MB/s}. */
    String speed(long bytesPerSecond) {
        double megabytes = bytesPerSecond / 1_000_000.0;
        return megabytes >= 1
                ? get("speed.megabytes", megabytes)
                : get("speed.kilobytes", bytesPerSecond / 1_000.0);
    }
}
