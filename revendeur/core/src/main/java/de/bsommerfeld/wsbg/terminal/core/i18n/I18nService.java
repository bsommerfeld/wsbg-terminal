package de.bsommerfeld.wsbg.terminal.core.i18n;

import com.google.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;
import java.util.ResourceBundle;

/**
 * Service for handling Internationalization (I18N).
 * Manages the current locale and provides access to localized strings.
 */
@Singleton
public class I18nService {

    private static final Logger LOG = LoggerFactory.getLogger(I18nService.class);
    private static final String BUNDLE_NAME = "i18n.messages";

    // Default to System Locale, can be overridden by settings
    private Locale currentLocale = Locale.getDefault();
    private ResourceBundle resourceBundle;

    public I18nService() {
        loadBundle();
    }

    public void setLocale(Locale locale) {
        LOG.info("Switching locale from {} to {}", currentLocale, locale);
        this.currentLocale = locale;
        loadBundle();
    }

    public Locale getCurrentLocale() {
        return currentLocale;
    }

    public String get(String key) {
        try {
            return resourceBundle.getString(key);
        } catch (Exception e) {
            LOG.error("Missing translation for key: {}", key);
            throw new RuntimeException("Translation missing for key: " + key);
        }
    }

    public String get(String key, Object... args) {
        String pattern = get(key);
        try {
            java.text.MessageFormat formatter = new java.text.MessageFormat(pattern, currentLocale);
            return formatter.format(args);
        } catch (Exception e) {
            LOG.error("Error formatting string for key: {}", key, e);
            throw new RuntimeException("I18n Formatting Error for key: " + key, e);
        }
    }

    private void loadBundle() {
        try {
            // We use the classloader to find the bundle in the classpath
            this.resourceBundle = ResourceBundle.getBundle(BUNDLE_NAME, currentLocale);
        } catch (Exception e) {
            LOG.error("CRITICAL: Failed to load resource bundle '{}' for locale '{}'", BUNDLE_NAME, currentLocale, e);
            throw new RuntimeException("Failed to load I18n Bundle: " + BUNDLE_NAME, e);
        }
    }
}
