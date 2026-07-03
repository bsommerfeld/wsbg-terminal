package de.bsommerfeld.wsbg.terminal.ui.scroll;

import java.awt.event.InputEvent;
import java.awt.event.MouseWheelEvent;

/**
 * The real fix: turn the OS-scaled wheel delta into a sensible pixel delta for
 * the windowless browser, while inheriting the OS scroll speed and direction.
 *
 * <p>The live bound policy (see {@code AppModule}); {@link #pixelsPerLine} was
 * calibrated from real per-notch field values on macOS and Windows.
 *
 * <p><b>How it inherits the OS settings.</b> In {@code WHEEL_UNIT_SCROLL} mode
 * AWT delivers {@link MouseWheelEvent#getScrollAmount()} = the OS "lines per
 * notch" setting and {@link MouseWheelEvent#getPreciseWheelRotation()} = the
 * (fractional, already OS-inverted) notch count. Their product is "OS lines to
 * scroll"; multiplying by {@link #pixelsPerLine} yields pixels. So a faster OS
 * setting (more lines/notch) scrolls proportionally further, and macOS "natural
 * scrolling" / a Windows driver flip arrives with the correct sign for free.
 * The {@code precise} rotation (vs the integer {@code unitsToScroll} JCEF uses
 * today) is what keeps trackpads smooth.
 *
 * <p>The {@code invert*} flags are the manual escape hatch for third-party
 * reverse-scroll tools whose flip happens below AWT and therefore can't be
 * auto-detected.
 */
public final class PixelScaledWheelScrollPolicy implements WheelScrollPolicy {

    /** Pixels per OS scroll-line. ~40 targets the conventional ~120px/notch. */
    private final double pixelsPerLine;
    /** Pixels per notch in block/page mode (rare; macOS/Windows use UNIT mode). */
    private final double pixelsPerBlock;
    private final boolean invertVertical;
    private final boolean invertHorizontal;

    // Sub-pixel carry per axis. AWT exposes only line-based (fractional) wheel
    // deltas, never the pixel-precise trackpad track, so the scaled value is a
    // real number that must be quantised to whole pixels. Rounding each event
    // independently turns a slow drag into a stream of equal 1px hops (visible
    // micro-jerk); instead we accumulate the remainder and emit only whole
    // pixels as they're crossed. EDT-only, so no synchronisation needed.
    private double residualX = 0;
    private double residualY = 0;

    public PixelScaledWheelScrollPolicy(double pixelsPerLine, double pixelsPerBlock,
            boolean invertVertical, boolean invertHorizontal) {
        this.pixelsPerLine = pixelsPerLine;
        this.pixelsPerBlock = pixelsPerBlock;
        this.invertVertical = invertVertical;
        this.invertHorizontal = invertHorizontal;
    }

    @Override
    public WheelScroll translate(MouseWheelEvent raw) {
        double pixels;
        if (raw.getScrollType() == MouseWheelEvent.WHEEL_UNIT_SCROLL) {
            // precise rotation (fractional, OS-inverted) × OS lines-per-notch.
            pixels = raw.getPreciseWheelRotation() * raw.getScrollAmount() * pixelsPerLine;
        } else {
            pixels = raw.getWheelRotation() * pixelsPerBlock;
        }
        if (pixels == 0) {
            return WheelScroll.NONE;
        }
        boolean horizontal = (raw.getModifiersEx() & InputEvent.SHIFT_DOWN_MASK) != 0;
        if (horizontal) {
            int out = accumulate(invertHorizontal ? -pixels : pixels, true);
            return out == 0 ? WheelScroll.NONE : new WheelScroll(out, 0);
        }
        int out = accumulate(invertVertical ? -pixels : pixels, false);
        return out == 0 ? WheelScroll.NONE : WheelScroll.vertical(out);
    }

    /** Add a signed pixel value to the axis carry; return whole pixels crossed. */
    private int accumulate(double pixels, boolean horizontal) {
        double residual = horizontal ? residualX : residualY;
        // A reversal shouldn't be braked by leftover carry from the old
        // direction — drop it so the flip is immediate.
        if (residual != 0 && Math.signum(residual) != Math.signum(pixels)) {
            residual = 0;
        }
        residual += pixels;
        int whole = (int) residual; // truncates toward zero; keeps the fraction
        residual -= whole;
        if (horizontal) {
            residualX = residual;
        } else {
            residualY = residual;
        }
        return whole;
    }
}
