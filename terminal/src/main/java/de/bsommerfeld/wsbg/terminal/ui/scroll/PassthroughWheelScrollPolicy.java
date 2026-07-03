package de.bsommerfeld.wsbg.terminal.ui.scroll;

import java.awt.event.InputEvent;
import java.awt.event.MouseWheelEvent;

/**
 * Reproduces the existing native behaviour exactly: forward the same delta JCEF
 * would have read, routed to the same axis. This is the zero-risk baseline — it
 * changes nothing about how scrolling feels and exists as the fallback if the
 * scaled policy is ever disabled.
 *
 * <p>It mirrors JCEF's {@code N_SendMouseWheelEvent}: in {@code WHEEL_UNIT_SCROLL}
 * mode the delta is {@link MouseWheelEvent#getUnitsToScroll()} (OS scroll-lines),
 * otherwise the coarse {@link MouseWheelEvent#getWheelRotation()}. The native
 * routes to the horizontal axis when SHIFT is held, so we do the same.
 */
public final class PassthroughWheelScrollPolicy implements WheelScrollPolicy {

    @Override
    public WheelScroll translate(MouseWheelEvent raw) {
        int units = raw.getScrollType() == MouseWheelEvent.WHEEL_UNIT_SCROLL
                ? raw.getUnitsToScroll()
                : raw.getWheelRotation();
        if (units == 0) {
            return WheelScroll.NONE;
        }
        boolean horizontal = (raw.getModifiersEx() & InputEvent.SHIFT_DOWN_MASK) != 0;
        return horizontal ? new WheelScroll(units, 0) : new WheelScroll(0, units);
    }
}
