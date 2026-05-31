package de.bsommerfeld.wsbg.terminal.core.config;

import java.util.Locale;

/**
 * Detected operating system of the current runtime.
 * Resolved once at class-load time from {@code os.name} and cached
 * as a constant — repeated queries are free.
 */
public enum OperatingSystem {

    MACOS,
    WINDOWS,
    LINUX;

    private static final OperatingSystem CURRENT = detect();

    /**
     * Returns the OS the JVM is running on.
     */
    public static OperatingSystem current() {
        return CURRENT;
    }

    private static OperatingSystem detect() {
        String name = System.getProperty("os.name", "generic")
                .toLowerCase(Locale.ENGLISH);

        if (name.contains("mac") || name.contains("darwin")) {
            return MACOS;
        }
        if (name.contains("win")) {
            return WINDOWS;
        }
        return LINUX;
    }
}
