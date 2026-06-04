package de.bsommerfeld.wsbg.terminal.ui.scroll;

import java.awt.event.MouseWheelEvent;

/**
 * The single seam that decides how one raw AWT wheel notch becomes the scroll
 * intent forwarded to the off-screen browser.
 *
 * <p><b>Why this exists.</b> The terminal renders Chromium in <em>windowless</em>
 * (OSR) mode — there is no native OS window, so Chromium never receives the
 * OS-transformed wheel message ({@code WM_MOUSEWHEEL} / {@code NSEvent}). JCEF's
 * native bridge instead forwards {@link MouseWheelEvent#getUnitsToScroll()}
 * (the OS scroll-lines count) straight into CEF, which interprets it as
 * <em>pixels</em> — so a notch moves ~3px and scrolling feels "sticky". This
 * interface is the one place where that delta is re-scaled and, if needed,
 * inverted; the browser stays oblivious and only delivers the result.
 *
 * <p><b>SRP.</b> An implementation decides <em>direction + magnitude</em> only.
 * Building the synthetic event and calling into CEF is the browser's job, not
 * the policy's.
 */
public interface WheelScrollPolicy {

    /**
     * Translate one raw wheel event into the intent to forward. Never returns
     * {@code null}; return {@link WheelScroll#NONE} to swallow the notch.
     */
    WheelScroll translate(MouseWheelEvent raw);
}
