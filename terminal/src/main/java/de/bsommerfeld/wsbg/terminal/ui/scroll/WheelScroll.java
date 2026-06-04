package de.bsommerfeld.wsbg.terminal.ui.scroll;

/**
 * The scroll intent forwarded to the windowless (OSR) browser, expressed in
 * CEF "unit-scroll" units. Under off-screen rendering Chromium interprets these
 * units as <em>pixels</em>, so the magnitude is effectively a pixel delta and
 * the sign is the (already OS-corrected) direction.
 *
 * <p>Pure data — it carries the decision a {@link WheelScrollPolicy} made, with
 * no knowledge of how the browser delivers it. {@code unitsX} is only relevant
 * for horizontal scrolling (rare in this app, which is vertical lists).
 *
 * @param unitsX horizontal delta; positive scrolls right
 * @param unitsY vertical delta; positive scrolls down
 */
public record WheelScroll(int unitsX, int unitsY) {

    /** A no-op scroll — the policy chose to swallow the notch. */
    public static final WheelScroll NONE = new WheelScroll(0, 0);

    public boolean isEmpty() {
        return unitsX == 0 && unitsY == 0;
    }

    /** Convenience for the common vertical-only case. */
    public static WheelScroll vertical(int unitsY) {
        return new WheelScroll(0, unitsY);
    }
}
