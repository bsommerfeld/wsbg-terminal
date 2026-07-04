package de.bsommerfeld.wsbg.terminal.core.i18n;

import com.google.inject.Singleton;
import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import jakarta.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Provides localized strings for the entire application. Resolves translations
 * from {@code i18n/messages_{locale}.properties} bundles on the classpath.
 *
 * <p>
 * The locale is determined by
 * {@link de.bsommerfeld.wsbg.terminal.core.config.UserConfig#getLanguage()}
 * at startup. Runtime locale changes are supported via
 * {@link #setLocale(Locale)},
 * which reloads the bundle immediately.
 *
 * <p>
 * Keys that are missing from the active bundle fall back gracefully to the
 * key itself (logged as a warning) rather than crashing — matching the
 * application-wide localization contract that a missing translation must
 * never bring down a user-facing path.
 */
@Singleton
public class I18nService {

    private static final Logger LOG = LoggerFactory.getLogger(I18nService.class);
    private static final String BUNDLE_NAME = "i18n.messages";

    private Locale currentLocale;
    private ResourceBundle resourceBundle;

    @Inject
    public I18nService(GlobalConfig config) {
        this.currentLocale = config.getUser().getUserLanguage().locale();
        loadBundle();
    }

    /**
     * Switches the active locale and reloads the resource bundle.
     * All subsequent {@link #get} calls will use the new locale.
     *
     * @param locale the new locale to activate
     */
    public void setLocale(Locale locale) {
        LOG.info("Switching locale from {} to {}", currentLocale, locale);
        this.currentLocale = locale;
        loadBundle();
    }

    /** Returns the currently active locale. */
    public Locale getCurrentLocale() {
        return currentLocale;
    }

    /**
     * Returns the localized string for the given key.
     *
     * @param key the message key as defined in the properties bundle
     * @return the translated string, or the key itself when no translation exists
     */
    public String get(String key) {
        try {
            return resourceBundle.getString(key);
        } catch (MissingResourceException e) {
            LOG.warn("Missing translation for key: {}", key);
            return key;
        }
    }

    /**
     * Returns the localized string with {@link MessageFormat} placeholders
     * resolved.
     *
     * @param key  the message key
     * @param args format arguments matching {@code {0}}, {@code {1}}, etc.
     * @return the formatted, translated string
     */
    public String get(String key, Object... args) {
        String pattern = get(key);
        try {
            MessageFormat formatter = new MessageFormat(pattern, currentLocale);
            return formatter.format(args);
        } catch (IllegalArgumentException e) {
            LOG.warn("Error formatting string for key: {}", key, e);
            return pattern;
        }
    }

    private void loadBundle() {
        try {
            this.resourceBundle = ResourceBundle.getBundle(BUNDLE_NAME, currentLocale);
        } catch (MissingResourceException e) {
            LOG.error("CRITICAL: Failed to load resource bundle '{}' for locale '{}'",
                    BUNDLE_NAME, currentLocale, e);
            throw new RuntimeException("Failed to load I18n Bundle: " + BUNDLE_NAME, e);
        }
    }
}
