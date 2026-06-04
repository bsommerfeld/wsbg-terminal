package de.bsommerfeld.wsbg.terminal.ui.scroll;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.event.MouseWheelEvent;

/**
 * Diagnostic decorator: logs the raw AWT wheel fields, then delegates to a real
 * policy. This is the verification instrument — run on macOS and Windows, scroll
 * a list, and read off what the OS/JDK actually delivers per notch so the pixel
 * scaling factor can be set empirically rather than guessed.
 *
 * <p>Logged: {@code scrollType} (UNIT vs BLOCK), {@code wheelRotation} (coarse,
 * sign-only), {@code preciseWheelRotation} (fractional, trackpad-aware),
 * {@code scrollAmount} (the OS "lines per notch" setting), {@code unitsToScroll}
 * (= amount × rotation, what JCEF forwards today) and the resulting delegated
 * {@link WheelScroll}. Rate-limited so a fast scroll doesn't flood the log.
 */
public final class LoggingWheelScrollPolicy implements WheelScrollPolicy {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingWheelScrollPolicy.class);
    private static final long MIN_INTERVAL_MS = 200;

    private final WheelScrollPolicy delegate;
    private long lastLogMs = 0;

    public LoggingWheelScrollPolicy(WheelScrollPolicy delegate) {
        this.delegate = delegate;
    }

    @Override
    public WheelScroll translate(MouseWheelEvent raw) {
        WheelScroll result = delegate.translate(raw);
        long now = System.currentTimeMillis();
        if (now - lastLogMs >= MIN_INTERVAL_MS) {
            lastLogMs = now;
            LOG.info("WHEEL-DIAG type={} rotation={} precise={} amount={} units={} mods={} -> {}",
                    raw.getScrollType() == MouseWheelEvent.WHEEL_UNIT_SCROLL ? "UNIT" : "BLOCK",
                    raw.getWheelRotation(),
                    raw.getPreciseWheelRotation(),
                    raw.getScrollAmount(),
                    raw.getUnitsToScroll(),
                    raw.getModifiersEx(),
                    result);
        }
        return result;
    }
}
