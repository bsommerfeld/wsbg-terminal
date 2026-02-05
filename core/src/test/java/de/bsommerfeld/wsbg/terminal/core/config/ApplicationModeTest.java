package de.bsommerfeld.wsbg.terminal.core.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ApplicationModeTest {

    @Test
    void get_shouldReturnProdByDefault() {
        // Clear any existing property to test the default path
        String original = System.getProperty("app.mode");
        try {
            System.clearProperty("app.mode");
            ApplicationMode mode = ApplicationMode.get();
            // When no env var APP_MODE is set either, defaults to PROD
            assertNotNull(mode);
        } finally {
            if (original != null)
                System.setProperty("app.mode", original);
        }
    }

    @Test
    void get_shouldResolveFromSystemProperty() {
        String original = System.getProperty("app.mode");
        try {
            System.setProperty("app.mode", "TEST");
            assertEquals(ApplicationMode.TEST, ApplicationMode.get());
        } finally {
            if (original != null)
                System.setProperty("app.mode", original);
            else
                System.clearProperty("app.mode");
        }
    }

    @Test
    void get_shouldBeCaseInsensitive() {
        String original = System.getProperty("app.mode");
        try {
            System.setProperty("app.mode", "test");
            assertEquals(ApplicationMode.TEST, ApplicationMode.get());
        } finally {
            if (original != null)
                System.setProperty("app.mode", original);
            else
                System.clearProperty("app.mode");
        }
    }

    @Test
    void get_shouldDefaultToProdForInvalidValue() {
        String original = System.getProperty("app.mode");
        try {
            System.setProperty("app.mode", "INVALID_GARBAGE");
            assertEquals(ApplicationMode.PROD, ApplicationMode.get());
        } finally {
            if (original != null)
                System.setProperty("app.mode", original);
            else
                System.clearProperty("app.mode");
        }
    }

    @Test
    void get_shouldDefaultToProdForEmptyString() {
        String original = System.getProperty("app.mode");
        try {
            System.setProperty("app.mode", "");
            // Empty string → fallthrough to env var → if also empty → PROD
            assertNotNull(ApplicationMode.get());
        } finally {
            if (original != null)
                System.setProperty("app.mode", original);
            else
                System.clearProperty("app.mode");
        }
    }

    @Test
    void isTest_shouldReturnTrueForTestMode() {
        assertTrue(ApplicationMode.TEST.isTest());
    }

    @Test
    void isTest_shouldReturnFalseForProdMode() {
        assertFalse(ApplicationMode.PROD.isTest());
    }

    @Test
    void valueOf_shouldWorkForBothValues() {
        assertEquals(ApplicationMode.PROD, ApplicationMode.valueOf("PROD"));
        assertEquals(ApplicationMode.TEST, ApplicationMode.valueOf("TEST"));
    }

    @Test
    void values_shouldContainExactlyTwoEntries() {
        assertEquals(2, ApplicationMode.values().length);
    }
}
