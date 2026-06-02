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
            // Dispatch WINDOW_CLOSING (not frame.dispose()) so the HTML close
            // button runs BrowserWindow's full graceful shutdown — CEF teardown,
            // CefApp dispose, the spawned Ollama, System.exit. A bare dispose()
            // skips windowClosing and leaks the JVM + jcef helpers + Ollama.
            case "close" -> SwingUtilities.invokeLater(() ->
                    frame.dispatchEvent(new java.awt.event.WindowEvent(
                            frame, java.awt.event.WindowEvent.WINDOW_CLOSING)));
            case "minimize" -> SwingUtilities.invokeLater(() -> {
                // SC_MINIMIZE animates natively; setExtendedState(ICONIFIED) on a
                // caption-less window skips the genie/slide. Fall back if not Win.
                if (!WindowsCustomChrome.minimize(frame)) {
                    frame.setExtendedState(frame.getExtendedState() | Frame.ICONIFIED);
                }
            });
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
