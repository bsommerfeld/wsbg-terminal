package de.bsommerfeld.wsbg.terminal.ui;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Frame;

/**
 * Applies the window-control commands fired by the HTML title bar
 * ({@code close} / {@code minimize} / {@code maximize-toggle}) to the Swing
 * frame as <em>native</em> state changes, so minimize and maximize animate
 * exactly like any other OS window.
 *
 * <p>
 * Dragging, edge-resizing and Aero Snap are <b>not</b> handled here anymore —
 * they're driven entirely by Windows via {@link WindowsCustomChrome}'s
 * {@code WM_NCHITTEST} (the title-bar strip reports {@code HTCAPTION}, the top
 * edge {@code HTTOP}). The page no longer emulates any of that.
 */
@Singleton
public final class WindowDragHandler {

    private JFrame frame;

    @Inject
    public WindowDragHandler() {}

    public void bind(JFrame frame) {
        this.frame = frame;
    }

    public void handle(String command, String edge) {
        if (frame == null) return;
        switch (command) {
            case "close" -> SwingUtilities.invokeLater(frame::dispose);
            case "minimize" -> SwingUtilities.invokeLater(
                    () -> frame.setExtendedState(frame.getExtendedState() | Frame.ICONIFIED));
            case "maximize-toggle" -> SwingUtilities.invokeLater(this::toggleMaximize);
            default -> { /* drag/resize are native via WM_NCHITTEST */ }
        }
    }

    private void toggleMaximize() {
        int state = frame.getExtendedState();
        if ((state & Frame.MAXIMIZED_BOTH) != 0) {
            frame.setExtendedState(state & ~Frame.MAXIMIZED_BOTH);
        } else {
            frame.setExtendedState(state | Frame.MAXIMIZED_BOTH);
        }
    }
}
