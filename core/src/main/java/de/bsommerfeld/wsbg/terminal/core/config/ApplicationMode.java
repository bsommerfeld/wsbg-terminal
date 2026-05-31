package de.bsommerfeld.wsbg.terminal.core.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enumeration representing the running mode of the application.
 * Controls behavior like database persistence and external API calls.
 */
public enum ApplicationMode {

    PROD,
    TEST;

    private static final Logger LOG = LoggerFactory.getLogger(ApplicationMode.class);

    /**
     * Resolves the current application mode from system properties ("app.mode")
     * or environment variables ("APP_MODE"). Defaults to PROD if not set or
     * invalid.
     */
    public static ApplicationMode get() {
        String mode = System.getProperty("app.mode");
        if (mode == null || mode.isEmpty()) {
            mode = System.getenv("APP_MODE");
        }

        if (mode == null || mode.isEmpty()) {
            return PROD; // Default
        }

        try {
            return ApplicationMode.valueOf(mode.toUpperCase());
        } catch (IllegalArgumentException e) {
            LOG.warn("Unknown Application Mode '{}'. Defaulting to PROD.", mode);
            return PROD;
        }
    }

    public boolean isTest() {
        return this == TEST;
    }
}
