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

    /** The raised surface every choice screen puts its rows and cards on. */
    static final Color SURFACE = new Color(38, 38, 43);

    /** The same surface under the pointer. */
    static final Color SURFACE_HOVER = new Color(48, 48, 54);

    /** Accent wash over the selected surface — a tint, never a fill. */
    static final Color SELECTED_TINT = new Color(0xF9, 0xB6, 0x4F, 26);

    /** Body text on the dark background. */
    static final Color TEXT_PRIMARY = new Color(222, 222, 226);

    /** Secondary text — side notes, legends, hints. */
    static final Color TEXT_DIM = new Color(145, 145, 152);

    /** Text ON the accent (the confirm button's label). */
    static final Color ON_ACCENT = new Color(0x1A, 0x1A, 0x1A);

    /** The 1px outline along the window body. */
    static final Color HAIRLINE = new Color(255, 255, 255, 10);

    private LauncherTheme() {
    }
}
