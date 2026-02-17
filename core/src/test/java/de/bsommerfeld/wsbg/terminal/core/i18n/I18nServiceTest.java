package de.bsommerfeld.wsbg.terminal.core.i18n;

import de.bsommerfeld.wsbg.terminal.core.config.GlobalConfig;
import de.bsommerfeld.wsbg.terminal.core.config.UserConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class I18nServiceTest {

    private I18nService service;

    @BeforeEach
    void setUp() {
        GlobalConfig config = mock(GlobalConfig.class);
        UserConfig userConfig = mock(UserConfig.class);
        when(config.getUser()).thenReturn(userConfig);
        when(userConfig.getLanguage()).thenReturn("en");
        service = new I18nService(config);
    }

    @Test
    void getCurrentLocale_shouldReturnConfiguredLocale() {
        assertEquals(Locale.ENGLISH, service.getCurrentLocale());
    }

    @Test
    void get_shouldReturnTranslatedString() {
        String value = service.get("system.ready");
        assertNotNull(value);
        assertFalse(value.isBlank());
    }

    @Test
    void get_shouldThrowForMissingKey() {
        assertThrows(RuntimeException.class,
                () -> service.get("nonexistent.key.that.does.not.exist"));
    }

    @Test
    void getFormatted_shouldReplacePlaceholders() {
        // "log.cleanup.message=Cycle executed. Removed {0} outdated records."
        String result = service.get("log.cleanup.message", 42);
        assertTrue(result.contains("42"));
    }

    @Test
    void setLocale_shouldSwitchLanguage() {
        service.setLocale(Locale.GERMAN);
        assertEquals(Locale.GERMAN, service.getCurrentLocale());

        String value = service.get("system.ready");
        assertNotNull(value);
    }

    @Test
    void setLocale_shouldFallbackToDefaultBundleForUnsupportedLocale() {
        // Japanese is not available, ResourceBundle falls back to default
        service.setLocale(Locale.JAPANESE);
        String value = service.get("system.ready");
        assertNotNull(value);
    }
}
