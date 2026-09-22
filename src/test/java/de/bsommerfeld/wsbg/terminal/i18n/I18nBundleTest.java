package de.bsommerfeld.wsbg.terminal.i18n;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.PropertyResourceBundle;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** The German bundle and the English fallback carry the same keys. */
class I18nBundleTest {

    @Test
    void germanAndEnglishCarryTheSameKeys() throws IOException {
        Set<String> english = keys("messages.properties");
        Set<String> german = keys("messages_de.properties");
        assertFalse(english.isEmpty());
        assertEquals(english, german);
    }

    @Test
    void missingKeyFallsBackToTheKey() {
        I18n.localeProperty().set(Locale.GERMAN);
        assertEquals("Einstellungen", I18n.get("settings.title"));
        assertEquals("no.such.key", I18n.get("no.such.key"));
    }

    private static Set<String> keys(String file) throws IOException {
        try (InputStream in = I18nBundleTest.class.getResourceAsStream(file)) {
            return new PropertyResourceBundle(in).keySet();
        }
    }
}
