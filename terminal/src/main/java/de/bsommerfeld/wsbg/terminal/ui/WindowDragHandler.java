package de.bsommerfeld.wsbg.terminal.ui;

import com.google.inject.Inject;
import com.google.inject.Singleton;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;

/**
 * Translates window-management commands coming from the page (sent over
 * the WebSocket push hub) into Swing frame operations. The HTML's custom
 * titlebar fires {@code window:drag-start}, {@code window:close},
 * {@code window:minimize}, {@code window:maximize-toggle}; this handler
 * applies them on the EDT.
 *
 * <p>
 * Drag and resize are implemented on the Java side rather than via CSS
 * {@code -webkit-app-region} because that flag only works in Electron-
 * style frameworks. A {@code drag-start} / {@code resize-start} message
 * triggers a poll loop that follows the OS cursor until the matching
 * {@code -stop} arrives.
 *
 * <p>
 * Resize only matters on Windows/Linux: there the frame is undecorated
 * ({@code setUndecorated(true)}), which strips the native resize border,
 * so the page paints invisible edge hit-zones and forwards the gesture
 * here. macOS keeps a decorated NSWindow and resizes natively, so the
 * page never sends {@code resize-start} on that platform.
 */
@Singleton
public final class WindowDragHandler {

    private JFrame frame;
    private Thread dragThread;
    private volatile boolean dragging;
    private Thread resizeThread;
    private volatile boolean resizing;

    @Inject
    public WindowDragHandler() {}

    public void bind(JFrame frame) {
        this.frame = frame;
    }

    public void handle(String command, String edge) {
        if (frame == null) return;
        switch (command) {
            case "drag-start" -> startDrag();
            case "drag-stop" -> dragging = false;
            case "resize-start" -> startResize(edge);
            case "resize-stop" -> resizing = false;
            case "close" -> SwingUtilities.invokeLater(() -> frame.dispose());
            case "minimize" -> SwingUtilities.invokeLater(() -> frame.setState(Frame.ICONIFIED));
            case "maximize-toggle" -> SwingUtilities.invokeLater(this::toggleMaximize);
            default -> {}
        }
    }

    private void toggleMaximize() {
        int state = frame.getExtendedState();
        if ((state & Frame.MAXIMIZED_BOTH) != 0) {
            frame.setExtendedState(state & ~Frame.MAXIMIZED_BOTH);
        } else {
            // Clamp the maximized size to the screen's *usable* area
            // (minus taskbar / dock / menu bar). An undecorated frame
            // otherwise covers the whole display incl. the panel, because
            // without window-manager decoration the WM struts are ignored.
            frame.setMaximizedBounds(usableScreenBounds());
            frame.setExtendedState(state | Frame.MAXIMIZED_BOTH);
        }
    }

    private Rectangle usableScreenBounds() {
        GraphicsConfiguration gc = frame.getGraphicsConfiguration();
        Rectangle screen = gc.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(gc);
        return new Rectangle(
                screen.x + insets.left,
                screen.y + insets.top,
                screen.width - insets.left - insets.right,
                screen.height - insets.top - insets.bottom);
    }

    private void startDrag() {
        if (dragging) return;
        Point cursor = MouseInfo.getPointerInfo().getLocation();
        Point frameOrigin = frame.getLocationOnScreen();
        int offsetX = cursor.x - frameOrigin.x;
        int offsetY = cursor.y - frameOrigin.y;

        dragging = true;
        dragThread = new Thread(() -> {
            while (dragging) {
                Point now = MouseInfo.getPointerInfo().getLocation();
                int x = now.x - offsetX;
                int y = now.y - offsetY;
                SwingUtilities.invokeLater(() -> frame.setLocation(x, y));
                try {
                    Thread.sleep(8); // ~120Hz follow rate
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "window-drag");
        dragThread.setDaemon(true);
        dragThread.start();
    }

    private void startResize(String edge) {
        if (resizing || dragging || edge == null) return;
        boolean west = edge.contains("w");
        boolean east = edge.contains("e");
        boolean north = edge.contains("n");
        boolean south = edge.contains("s");
        if (!(west || east || north || south)) return;

        Point startCursor = MouseInfo.getPointerInfo().getLocation();
        Rectangle startBounds = frame.getBounds();
        Dimension min = frame.getMinimumSize();

        resizing = true;
        resizeThread = new Thread(() -> {
            while (resizing) {
                Point now = MouseInfo.getPointerInfo().getLocation();
                Rectangle b = computeBounds(startBounds, min, now.x - startCursor.x,
                        now.y - startCursor.y, west, east, north, south);
                SwingUtilities.invokeLater(() -> frame.setBounds(b));
                try {
                    Thread.sleep(8); // ~120Hz follow rate, matches the drag loop
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "window-resize");
        resizeThread.setDaemon(true);
        resizeThread.start();
    }

    /**
     * Derives the new frame bounds from the cursor delta and the dragged
     * edge(s). West/north edges move the origin <em>and</em> resize, so they
     * clamp the delta against the minimum size to keep the opposite edge
     * pinned; east/south only grow/shrink the width/height.
     */
    private static Rectangle computeBounds(Rectangle start, Dimension min, int dx, int dy,
                                           boolean west, boolean east, boolean north, boolean south) {
        Rectangle b = new Rectangle(start);
        if (west) {
            int newW = start.width - dx;
            if (newW < min.width) dx = start.width - min.width;
            b.x = start.x + dx;
            b.width = start.width - dx;
        } else if (east) {
            b.width = Math.max(min.width, start.width + dx);
        }
        if (north) {
            int newH = start.height - dy;
            if (newH < min.height) dy = start.height - min.height;
            b.y = start.y + dy;
            b.height = start.height - dy;
        } else if (south) {
            b.height = Math.max(min.height, start.height + dy);
        }
        return b;
    }
}
