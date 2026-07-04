package de.bsommerfeld.updater.launcher;

import java.awt.Color;

/**
 * Shared visual constants for the launcher splash. Hoisted here so the
 * background and accent colours are defined once instead of being redeclared
 * (with "must match" comments) across {@link LauncherWindow} and
 * {@link IslandIndicator}.
 */
final class LauncherTheme {

    /**
     * The matte dark window background. Painted as the opaque fill by both the
     * window root and every child that needs to hide the transparent frame
     * (otherwise Swing's dirty-region clear briefly exposes the desktop).
     */
    static final Color BG = new Color(0x1A, 0x1A, 0x1A);

    /** Terminal accent (--amber oklch(0.82 0.14 75) ≈ #F9B64F). */
    static final Color ACCENT = new Color(0xF9, 0xB6, 0x4F);

    private LauncherTheme() {
    }
}
