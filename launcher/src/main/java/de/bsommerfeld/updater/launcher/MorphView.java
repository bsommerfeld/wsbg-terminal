package de.bsommerfeld.updater.launcher;

/**
 * A choice view the launcher window can morph into — the language choice
 * ({@link LanguageChoicePanel}) and the model choice ({@link ModelChoicePanel}).
 * Both paint the rounded window body themselves at an interpolated size, so
 * {@link LauncherWindow} only has to drive the clock.
 *
 * <h3>Flicker discipline</h3>
 * The morph performs ZERO native window mutations per frame — no setBounds,
 * no setShape (each native change applies on the compositor immediately while
 * the matching paint lands 1–2 display refreshes later; on a 120 Hz panel
 * every mismatch shows as flicker). The frame is sized and shaped to the final
 * target ONCE, and each animation frame is painted synchronously via
 * {@code paintImmediately} — one complete blit per frame. While morphing the
 * panel is transparent outside the painted body; at rest it flips opaque
 * ({@link #setRested}) so ordinary hover repaints can never clear-bleed the
 * desktop through. EDT-only, like all launcher widgets.
 */
interface MorphView {

    /**
     * Advances the morph WITHOUT scheduling a repaint: 0 = the normal window
     * body (only the logo painted, at its splash position), 1 = the fully
     * unfolded choice view.
     */
    void setMorphT(float t);

    /**
     * Flips between the morphing surface (transparent outside the body) and
     * the at-rest surface (fully opaque).
     */
    void setRested(boolean rested);
}
